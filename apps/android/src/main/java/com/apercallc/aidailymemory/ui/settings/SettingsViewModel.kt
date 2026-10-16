package com.apercallc.aidailymemory.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.ondevice.ModelState
import com.apercallc.aidailymemory.processing.ProviderHealthCheck
import com.apercallc.aidailymemory.processing.ProviderHealthResult
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import com.apercallc.aidailymemory.recording.processing.ProviderPresets
import com.apercallc.aidailymemory.recording.processing.ProviderCostEstimate
import com.apercallc.aidailymemory.recording.processing.ProviderCostEstimator
import com.apercallc.aidailymemory.recording.processing.ProviderUsageSample
import com.apercallc.aidailymemory.recording.server.ConnectLink
import com.apercallc.aidailymemory.recording.server.ServerAddress
import com.apercallc.aidailymemory.recording.server.ConnectionCheck
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.OffState
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.schedule.RhythmPlanner
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.settings.ProcessingSettings
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.ConnectError
import com.apercallc.aidailymemory.net.ServerLink
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class UrlDrafts(
    val transcription: String? = null,
    val extraction: String? = null,
)

private data class ProcessingDiagnostics(
    val modelState: ModelState,
    val failedSessions: Int,
    val failureCode: String?,
    val providerHealth: Pair<ProviderHealthResult, ProviderHealthResult>,
    val costEstimate: ProviderCostEstimate,
)

/** The next thing the schedule will do, for the "Next: …" line. */
data class NextChange(val action: RhythmAction, val at: ZonedDateTime, val quietLabel: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val rhythm: StateFlow<DailyRhythm> = settings.rhythm
    val retention: StateFlow<AudioRetention> = settings.retention
    val memoryRetention: StateFlow<com.apercallc.aidailymemory.recording.storage.MemoryRetention> = settings.memoryRetention
    val hadUnreadableRhythm: Boolean get() = settings.hadUnreadableRhythm

    /** Phone storage per recorded hour at the current capture settings. */
    val bytesPerHour: Long get() = container.storageBytesPerHour

    fun updateRhythm(rhythm: DailyRhythm) {
        val previous = settings.rhythm.value
        settings.setRhythm(rhythm)
        viewModelScope.launch {
            try {
                container.rhythm.onRhythmChanged(previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Saved already; the service's next check or the alarm applies it.
                logFailure(TAG, "rhythm_apply_failed", e)
            }
        }
    }

    fun updateRetention(retention: AudioRetention) = settings.setRetention(retention)

    fun updateMemoryRetention(retention: com.apercallc.aidailymemory.recording.storage.MemoryRetention) =
        settings.setMemoryRetention(retention)

    private val linker = container.accountLinker
    private val connecting = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)
    private val connectError = MutableStateFlow<ConnectError?>(null)

    /** Server text that is not a usable address yet; shown as typed, never stored. */
    private val serverDraft = MutableStateFlow<String?>(null)

    /** Blank returns to the hosted server; a valid https address is stored; anything else is kept as a draft. */
    fun changeServer(input: String) {
        when (val parsed = ServerAddress.parse(input)) {
            is ServerAddress.Valid -> {
                serverDraft.value = null
                container.links.setServerUrl(parsed.origin)
            }
            ServerAddress.Blank -> {
                serverDraft.value = null
                container.links.setServerUrl(null)
            }
            ServerAddress.Invalid -> serverDraft.value = input
        }
        connectError.value = null
    }

    val account: StateFlow<AccountUi> = combine(
        combine(
            container.links.link,
            container.links.problem,
            combine(container.links.revokePending, container.links.revokeManual, ::Pair),
            ::Triple,
        ),
        combine(connecting, testing, ::Pair),
        connectError,
        combine(container.links.serverUrl, serverDraft, ::Pair),
    ) { (link, problem, revoke), (busy, testingNow), error, (server, draft) ->
        AccountUi(
            host = link?.let { hostOf(it.baseUrl) },
            serverUrl = server,
            serverDraft = draft,
            problem = problem,
            connecting = busy,
            testing = testingNow,
            error = error,
            revokePending = revoke.first,
            revokeManual = revoke.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUi())

    // --- Processing & sync settings (ADR-0020) ---

    private val pSettings = container.processingSettings

    /** Text the user typed that is not a valid endpoint yet; shown as typed, never stored. */
    private val urlDrafts = MutableStateFlow(UrlDrafts())
    private val providerHealth = MutableStateFlow(
        ProviderHealthResult.IDLE to ProviderHealthResult.IDLE,
    )
    private val emptyCostEstimate = ProviderCostEstimator.estimate(emptyList(), System.currentTimeMillis())
    private val costEstimate: StateFlow<ProviderCostEstimate> = container.database.providerUsage()
        .observeSince(System.currentTimeMillis() - USAGE_WINDOW_MS)
        .map { rows ->
            ProviderCostEstimator.estimate(
                rows.mapNotNull { row ->
                    val stage = when (row.stage) {
                        "TRANSCRIPTION" -> ProviderUsageSample.Stage.TRANSCRIPTION
                        "SUMMARY" -> ProviderUsageSample.Stage.SUMMARY
                        else -> return@mapNotNull null
                    }
                    ProviderUsageSample(
                        sessionId = row.sessionId,
                        stage = stage,
                        providerId = row.providerId,
                        model = row.model,
                        requestAudioMs = row.requestAudioMs,
                        sessionAudioMs = row.sessionAudioMs,
                        inputTokens = row.inputTokens,
                        outputTokens = row.outputTokens,
                        recordedAtEpochMs = row.recordedAtEpochMs,
                    )
                },
                System.currentTimeMillis(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyCostEstimate)

    val processing: StateFlow<ProcessingUi> = combine(
        pSettings.processingEnabled,
        pSettings.transcriptionProvider,
        pSettings.deleteAudioAfterTranscription,
        urlDrafts,
        // Any provider edit or model download refreshes the section.
        combine(
            pSettings.configRevision,
            container.speechModel.state,
            combine(
                combine(
                    // Start with empty diagnostics so the section renders before Room answers.
                    container.database.processingState().observeFailedCount().onStart { emit(0) },
                    container.database.processingState().observeMostRecentFailedError().onStart { emit(null) },
                    ::Pair,
                ),
                combine(providerHealth, costEstimate, ::Pair),
                ::Pair,
            ),
        ) { _, model, (failure, healthAndCost) ->
            ProcessingDiagnostics(model, failure.first, failure.second, healthAndCost.first, healthAndCost.second)
        },
    ) { enabled, tp, deleteAudio, drafts, diagnostics ->
        val transcriptionPreset = ProviderPresets.match(ProviderPresets.transcription, pSettings.transcriptionApiUrl)
        ProcessingUi(
            enabled = enabled,
            transcriptionOwnKey = tp == ProcessingSettings.PROVIDER_USER_API,
            summaries = pSettings.extractionProvider.value == ProcessingSettings.PROVIDER_USER_API,
            modelState = diagnostics.modelState,
            modelSizeMb = (container.speechModel.spec.zipBytes / BYTES_PER_MB).toInt(),
            transcriptionApiKey = pSettings.transcriptionApiKey ?: "",
            transcriptionApiUrl = drafts.transcription ?: pSettings.transcriptionApiUrl ?: "",
            transcriptionApiUrlInvalid = drafts.transcription != null,
            extractionApiKey = pSettings.extractionApiKey ?: "",
            extractionApiUrl = drafts.extraction ?: pSettings.extractionApiUrl ?: "",
            extractionApiUrlInvalid = drafts.extraction != null,
            extractionModel = pSettings.extractionModel ?: "",
            transcriptionPresetId = transcriptionPreset?.id ?: ProviderPresets.CUSTOM_ID,
            transcriptionModel = ProviderPresets.modelInUrl(pSettings.transcriptionApiUrl)
                ?: transcriptionPreset?.defaultModel.orEmpty(),
            extractionPresetId = ProviderPresets.match(ProviderPresets.summary, pSettings.extractionApiUrl)?.id
                ?: ProviderPresets.CUSTOM_ID,
            deleteAudioAfterTranscription = deleteAudio,
            failedSessions = diagnostics.failedSessions,
            failureCode = diagnostics.failureCode,
            transcriptionHealth = diagnostics.providerHealth.first,
            extractionHealth = diagnostics.providerHealth.second,
            costEstimate = diagnostics.costEstimate,
            configGaps = pSettings.configGaps(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProcessingUi())

    private val pendingSyncDays: Flow<List<String>> = combine(
        pSettings.reportCursor,
        container.links.link,
    ) { cursor, link ->
        maxOf(cursor ?: 0L, if (link == null) 0L else container.links.uploadSinceEpochMs)
    }.flatMapLatest { since -> container.database.dailyReports().observePendingDays(since) }
        .onStart { emit(emptyList()) }

    val sync: StateFlow<SyncUi> = combine(
        pSettings.syncEnabled,
        pSettings.syncIncludeTranscripts,
        pSettings.lastSyncFailed,
        combine(
            pendingSyncDays,
            container.database.syncDeletions().observeCount().onStart { emit(0) },
            ::Pair,
        ),
        combine(container.links.link, pSettings.lastSyncSucceededAtEpochMs) { link, lastSyncSucceededAt ->
            link to lastSyncSucceededAt
        },
    ) { enabled, includeTranscripts, lastSyncFailed, pending, linkAndSuccess ->
        SyncUi(
            enabled = enabled,
            connected = linkAndSuccess.first != null,
            includeTranscripts = includeTranscripts,
            lastSyncSucceededAtEpochMs = linkAndSuccess.second,
            lastSyncFailed = lastSyncFailed,
            pendingDays = pending.first,
            pendingDeletions = pending.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUi())

    val processingActions = ProcessingActions(
        onEnableChange = { enabled ->
            pSettings.setProcessingEnabled(enabled)
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onTranscriptionOwnKeyChange = { enabled ->
            pSettings.setTranscriptionProvider(
                if (enabled) ProcessingSettings.PROVIDER_USER_API else ProcessingSettings.PROVIDER_ON_DEVICE,
            )
            // Pre-filled so the only thing left to add is a key.
            if (enabled) prefillTranscription()
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onSummariesChange = { enabled ->
            pSettings.setExtractionProvider(
                if (enabled) ProcessingSettings.PROVIDER_USER_API else ProcessingSettings.PROVIDER_DISABLED,
            )
            if (enabled) prefillSummaries()
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onDownloadModel = container::downloadSpeechModel,
        onRemoveModel = {
            container.speechModel.delete()
            onProcessingSettingsChanged()
        },
        onTranscriptionApiKeyChange = { value ->
            pSettings.transcriptionApiKey = value
            if (pSettings.secretSaveFailed) say(R.string.key_not_saved)
            onProcessingSettingsChanged()
        },
        onTranscriptionApiUrlChange = { value ->
            applyUrl(value, { d, v -> d.copy(transcription = v) }) {
                dropKeyIfHostChanges(pSettings.transcriptionApiUrl, it) { pSettings.transcriptionApiKey = null }
                pSettings.transcriptionApiUrl = it
            }
        },
        onExtractionApiKeyChange = { value ->
            pSettings.extractionApiKey = value
            if (pSettings.secretSaveFailed) say(R.string.key_not_saved)
            onProcessingSettingsChanged()
        },
        onExtractionApiUrlChange = { value ->
            applyUrl(value, { d, v -> d.copy(extraction = v) }) {
                dropKeyIfHostChanges(pSettings.extractionApiUrl, it) { pSettings.extractionApiKey = null }
                pSettings.extractionApiUrl = it
            }
        },
        onExtractionModelChange = { value ->
            pSettings.extractionModel = value
            onProcessingSettingsChanged()
        },
        onDeleteAudioChange = { enabled ->
            pSettings.setDeleteAudioAfterTranscription(enabled)
            say(R.string.settings_saved)
        },
        onRetryFailed = {
            container.appScope.launch(Dispatchers.IO) {
                container.database.processingState().retryFailed(System.currentTimeMillis())
                container.processingScheduler.schedule()
            }
            say(R.string.processing_retrying)
        },
        onTranscriptionPresetChange = { id ->
            urlDrafts.update { it.copy(transcription = null) }
            val preset = ProviderPresets.transcription.firstOrNull { it.id == id }
            if (preset != null) {
                dropKeyIfHostChanges(pSettings.transcriptionApiUrl, preset.endpoint) { pSettings.transcriptionApiKey = null }
                pSettings.transcriptionApiUrl = preset.endpointFor(preset.defaultModel)
            } else if (ProviderPresets.match(ProviderPresets.transcription, pSettings.transcriptionApiUrl) != null) {
                pSettings.transcriptionApiKey = null
                pSettings.transcriptionApiUrl = null
            }
            onProcessingSettingsChanged()
        },
        onTranscriptionModelChange = { model ->
            val preset = ProviderPresets.match(ProviderPresets.transcription, pSettings.transcriptionApiUrl)
            if (preset != null) pSettings.transcriptionApiUrl = preset.endpointFor(model)
            onProcessingSettingsChanged()
        },
        onExtractionPresetChange = { id ->
            urlDrafts.update { it.copy(extraction = null) }
            val preset = ProviderPresets.summary.firstOrNull { it.id == id }
            if (preset != null) {
                dropKeyIfHostChanges(pSettings.extractionApiUrl, preset.endpoint) { pSettings.extractionApiKey = null }
                pSettings.extractionApiUrl = preset.endpoint
                if (pSettings.extractionModel !in preset.models) pSettings.extractionModel = preset.defaultModel
            } else if (ProviderPresets.match(ProviderPresets.summary, pSettings.extractionApiUrl) != null) {
                pSettings.extractionApiKey = null
                pSettings.extractionApiUrl = null
                pSettings.extractionModel = null
            }
            onProcessingSettingsChanged()
        },
        onSaveProvider = {
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onTestTranscriptionProvider = {
            providerHealth.value = ProviderHealthResult.CHECKING to providerHealth.value.second
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ProviderHealthCheck().checkTranscription(
                        pSettings.transcriptionApiUrl.orEmpty(),
                        pSettings.transcriptionApiKey.orEmpty(),
                    )
                }
                providerHealth.value = result to providerHealth.value.second
            }
        },
        onTestExtractionProvider = {
            providerHealth.value = providerHealth.value.first to ProviderHealthResult.CHECKING
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ProviderHealthCheck().checkSummary(
                        pSettings.extractionApiUrl.orEmpty(),
                        pSettings.extractionApiKey.orEmpty(),
                        pSettings.extractionModel.orEmpty(),
                    )
                }
                providerHealth.value = providerHealth.value.first to result
            }
        },
    )

    val syncActions = SyncActions(
        onEnableChange = { enabled ->
            pSettings.setSyncEnabled(enabled)
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onIncludeTranscriptsChange = { include ->
            pSettings.setSyncIncludeTranscripts(include)
            onProcessingSettingsChanged()
            say(R.string.settings_saved)
        },
        onRetry = {
            container.syncScheduler.schedule()
            say(R.string.sync_retry_scheduled)
        },
    )

    /**
     * Stores [input] only when it is blank (clears) or a valid https endpoint; otherwise the raw
     * text stays as a draft so the field can show an error and the saved value is untouched.
     */
    private fun applyUrl(
        input: String,
        withDraft: (UrlDrafts, String?) -> UrlDrafts,
        store: (String?) -> Unit,
    ) {
        when (val parsed = EndpointUrl.parse(input)) {
            is EndpointUrl.Valid -> {
                urlDrafts.update { withDraft(it, null) }
                store(parsed.value)
                onProcessingSettingsChanged()
            }
            EndpointUrl.Blank -> {
                urlDrafts.update { withDraft(it, null) }
                store(null)
                onProcessingSettingsChanged()
            }
            EndpointUrl.Invalid -> urlDrafts.update { withDraft(it, input) }
        }
    }

    /**
     * An API key belongs to the service it was issued by. When the address moves to another host,
     * the old key is forgotten instead of being sent there.
     */
    private fun dropKeyIfHostChanges(oldUrl: String?, newUrl: String?, clearKey: () -> Unit) {
        if (oldUrl != null && hostOf(oldUrl) != newUrl?.let(::hostOf)) clearKey()
    }

    /** Fills the first ready-made provider into any stage that has no address yet. */
    private fun prefillTranscription() {
        if (pSettings.transcriptionApiUrl == null) {
            ProviderPresets.transcription.first().let { pSettings.transcriptionApiUrl = it.endpointFor(it.defaultModel) }
        }
    }

    private fun prefillSummaries() {
        if (pSettings.extractionApiUrl == null) {
            ProviderPresets.summary.first().let {
                pSettings.extractionApiUrl = it.endpoint
                if (pSettings.extractionModel.isNullOrBlank()) pSettings.extractionModel = it.defaultModel
            }
        }
    }

    /** Schedules or cancels background work as processing and sync settings change. */
    fun onProcessingSettingsChanged() {
        if (pSettings.processingEnabled.value) {
            container.appScope.launch(Dispatchers.IO) {
                container.database.processingState().retryFailed(System.currentTimeMillis())
                container.processingScheduler.schedule()
            }
        } else {
            container.processingScheduler.cancel()
        }
        // The manager filters out reports while disabled, but must still drain deletions.
        container.syncScheduler.schedule()
    }

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** One-shot confirmations and errors, shown as toasts; they wait for the next collector. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    private fun say(@StringRes message: Int) {
        _messages.trySend(message)
    }

    /** Verifies a pasted token (or connect link) with the server, then stores it sealed. */
    fun connect(tokenOrLink: String, includeBacklog: Boolean) {
        val token = ConnectLink.tokenFromPaste(tokenOrLink) ?: tokenOrLink
        runConnect { linker.connect(token, includeBacklog) }
    }

    /** Creates an account on the hosted server and connects this phone. */
    fun signUp(email: String, password: String, includeBacklog: Boolean) =
        runConnect { linker.signUp(email, password, Build.MODEL.orEmpty().ifBlank { "Android phone" }, includeBacklog) }

    /** Signs in to an existing account and connects this phone. */
    fun logIn(email: String, password: String, includeBacklog: Boolean) =
        runConnect { linker.logIn(email, password, Build.MODEL.orEmpty().ifBlank { "Android phone" }, includeBacklog) }

    private fun runConnect(attempt: () -> ConnectError?) {
        if (connecting.value) return
        connecting.value = true
        connectError.value = null
        viewModelScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { attempt() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "connect_failed_unexpectedly", e)
                ConnectError.SAVE_FAILED
            } finally {
                connecting.value = false
            }
            connectError.value = error
            say(if (error == null) R.string.connect_success else R.string.connect_failed)
        }
    }

    /** Asks the hosted server who this phone is and reports the result as a toast. */
    fun testConnection() {
        if (testing.value) return
        testing.value = true
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { linker.testConnection() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "connection_test_failed_unexpectedly", e)
                ConnectionCheck.UNREACHABLE
            } finally {
                testing.value = false
            }
            say(
                when (result) {
                    ConnectionCheck.OK -> R.string.test_ok
                    ConnectionCheck.SIGN_IN_REJECTED -> R.string.test_rejected
                    ConnectionCheck.SERVER_ERROR -> R.string.test_server_error
                    ConnectionCheck.UNREACHABLE -> R.string.test_unreachable
                    ConnectionCheck.NOT_CONNECTED -> R.string.test_not_connected
                },
            )
        }
    }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    fun updateThemeMode(mode: ThemeMode) {
        settings.setThemeMode(mode)
        say(R.string.settings_saved)
    }

    fun clearConnectError() {
        connectError.value = null
    }

    /** Outlives the screen: the server-side revoke must not be cancelled by leaving it. */
    fun disconnect() {
        container.appScope.launch(Dispatchers.IO) { linker.disconnect() }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val BYTES_PER_MB = 1_000_000L
        private const val USAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val HTTP_UNAUTHORIZED = 401

        fun nextChange(rhythm: DailyRhythm, now: Instant, zone: ZoneId): NextChange? =
            RhythmPlanner.nextAction(rhythm, now, zone)?.let { (transition, action) ->
                NextChange(
                    action = action,
                    at = transition.at.atZone(zone),
                    quietLabel = (transition.to as? OffState.Quiet)?.label,
                )
            }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
    }
}
