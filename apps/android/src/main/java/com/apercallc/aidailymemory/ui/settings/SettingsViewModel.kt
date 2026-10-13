package com.apercallc.aidailymemory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.OffState
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.schedule.RhythmPlanner
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.settings.ProcessingSettings
import com.apercallc.aidailymemory.net.ConnectError
import com.apercallc.aidailymemory.net.ServerLink
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage mode as the settings screen sees it: loading, loaded, updating, or failed. */
internal data class StorageModeUi(
    val mode: String? = null,
    val updating: Boolean = false,
    val error: StorageModeError? = null,
)

private data class UrlDrafts(
    val transcription: String? = null,
    val extraction: String? = null,
    val sync: String? = null,
)

/** The next thing the schedule will do, for the "Next: …" line. */
data class NextChange(val action: RhythmAction, val at: ZonedDateTime, val quietLabel: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val rhythm: StateFlow<DailyRhythm> = settings.rhythm
    val retention: StateFlow<AudioRetention> = settings.retention
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

    private val linker = container.accountLinker
    private val connecting = MutableStateFlow(false)
    private val connectError = MutableStateFlow<ConnectError?>(null)
    private val storage = MutableStateFlow(StorageModeUi())

    init {
        // Refetched from scratch on every link change: a mode fetched under an old token must
        // never be shown as if it were the new account's.
        viewModelScope.launch {
            container.links.link.collectLatest { link ->
                storage.value = StorageModeUi()
                if (link != null) fetchStorageMode(link)
            }
        }
    }

    private suspend fun fetchStorageMode(link: ServerLink) {
        storage.update { it.copy(error = null) }
        try {
            val mode = withContext(Dispatchers.IO) { container.accountApi.me(link) }
            storage.update { it.copy(mode = mode, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(TAG, "storage_mode_fetch_failed", e)
            // Distinct from "still loading" (mode == null, no error): the UI offers a retry.
            storage.update { it.copy(mode = null, error = StorageModeError.LOAD_FAILED) }
        }
    }

    /** Retry after a failed fetch. */
    fun reloadStorageMode() {
        val link = container.links.link.value ?: return
        if (storage.value.updating) return
        viewModelScope.launch { fetchStorageMode(link) }
    }

    val account: StateFlow<AccountUi> = combine(
        combine(
            container.links.link,
            container.links.problem,
            combine(container.links.revokePending, container.links.revokeManual, ::Pair),
            ::Triple,
        ),
        connecting,
        connectError,
        container.links.link.flatMapLatest {
            container.database.clips().observeProblems(container.links.uploadSinceEpochMs)
        },
        storage,
    ) { (link, problem, revoke), busy, error, problems, storageUi ->
        AccountUi(
            host = link?.let { hostOf(it.baseUrl) },
            problem = problem,
            connecting = busy,
            error = error,
            uploadProblems = problems,
            revokePending = revoke.first,
            revokeManual = revoke.second,
            storageMode = storageUi.mode,
            storageModeUpdating = storageUi.updating,
            storageModeError = storageUi.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUi())

    // --- Processing & sync settings (ADR-0020) ---

    private val pSettings = container.processingSettings

    /** Text the user typed that is not a valid endpoint yet; shown as typed, never stored. */
    private val urlDrafts = MutableStateFlow(UrlDrafts())

    val processing: StateFlow<ProcessingUi> = combine(
        pSettings.processingEnabled,
        pSettings.transcriptionProvider,
        pSettings.deleteAudioAfterTranscription,
        pSettings.configRevision,
        urlDrafts,
    ) { enabled, tp, deleteAudio, _, drafts ->
        ProcessingUi(
            enabled = enabled,
            transcriptionProvider = tp,
            transcriptionApiKey = pSettings.transcriptionApiKey ?: "",
            transcriptionApiUrl = drafts.transcription ?: pSettings.transcriptionApiUrl ?: "",
            transcriptionApiUrlInvalid = drafts.transcription != null,
            extractionProvider = pSettings.extractionProvider.value,
            extractionApiKey = pSettings.extractionApiKey ?: "",
            extractionApiUrl = drafts.extraction ?: pSettings.extractionApiUrl ?: "",
            extractionApiUrlInvalid = drafts.extraction != null,
            extractionModel = pSettings.extractionModel ?: "",
            deleteAudioAfterTranscription = deleteAudio,
            configGaps = pSettings.configGaps(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProcessingUi())

    val sync: StateFlow<SyncUi> = combine(
        pSettings.syncEnabled,
        pSettings.syncIncludeTranscripts,
        pSettings.lastSyncFailed,
        pSettings.configRevision,
        urlDrafts,
    ) { enabled, includeTranscripts, lastSyncFailed, _, drafts ->
        SyncUi(
            enabled = enabled,
            url = drafts.sync ?: pSettings.syncUrl ?: "",
            urlInvalid = drafts.sync != null,
            token = pSettings.syncToken ?: "",
            includeTranscripts = includeTranscripts,
            lastSyncEpochMs = pSettings.lastSyncEpochMs,
            lastSyncFailed = lastSyncFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUi())

    val processingActions = ProcessingActions(
        onEnableChange = { enabled ->
            if (enabled) {
                // The screen no longer asks for a provider: turning processing on means "use my
                // own API key", so a provider left on "off" would only strand the setup.
                if (pSettings.transcriptionProvider.value == ProcessingSettings.PROVIDER_DISABLED) {
                    pSettings.setTranscriptionProvider(ProcessingSettings.PROVIDER_USER_API)
                }
                if (pSettings.extractionProvider.value == ProcessingSettings.PROVIDER_DISABLED) {
                    pSettings.setExtractionProvider(ProcessingSettings.PROVIDER_USER_API)
                }
            }
            pSettings.setProcessingEnabled(enabled)
            onProcessingSettingsChanged()
        },
        onTranscriptionProviderChange = { provider ->
            pSettings.setTranscriptionProvider(provider)
            onProcessingSettingsChanged()
        },
        onTranscriptionApiKeyChange = { value ->
            pSettings.transcriptionApiKey = value
            onProcessingSettingsChanged()
        },
        onTranscriptionApiUrlChange = { value ->
            applyUrl(value, { d, v -> d.copy(transcription = v) }) { pSettings.transcriptionApiUrl = it }
        },
        onExtractionProviderChange = { provider ->
            pSettings.setExtractionProvider(provider)
            onProcessingSettingsChanged()
        },
        onExtractionApiKeyChange = { value ->
            pSettings.extractionApiKey = value
            onProcessingSettingsChanged()
        },
        onExtractionApiUrlChange = { value ->
            applyUrl(value, { d, v -> d.copy(extraction = v) }) { pSettings.extractionApiUrl = it }
        },
        onExtractionModelChange = { value ->
            pSettings.extractionModel = value
            onProcessingSettingsChanged()
        },
        onDeleteAudioChange = pSettings::setDeleteAudioAfterTranscription,
    )

    val syncActions = SyncActions(
        onEnableChange = { enabled ->
            pSettings.setSyncEnabled(enabled)
            onProcessingSettingsChanged()
        },
        onUrlChange = { value ->
            applyUrl(value, { d, v -> d.copy(sync = v) }) { pSettings.syncUrl = it }
        },
        onTokenChange = { value ->
            pSettings.syncToken = value
            onProcessingSettingsChanged()
        },
        onIncludeTranscriptsChange = { include ->
            pSettings.setSyncIncludeTranscripts(include)
            onProcessingSettingsChanged()
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
        if (pSettings.syncEnabled.value) container.syncScheduler.schedule()
        else container.syncScheduler.cancel()
    }

    /** Switches storage mode server-side; audio already uploaded is unaffected (ADR-0014). */
    fun changeStorageMode(mode: String) {
        val link = container.links.link.value ?: return
        if (storage.value.updating) return
        storage.update { it.copy(updating = true, error = null) }
        viewModelScope.launch {
            val applied = try {
                withContext(Dispatchers.IO) { container.accountApi.setStorageMode(link, mode) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "storage_mode_change_failed", e)
                false
            }
            storage.update {
                if (applied) {
                    it.copy(mode = mode, updating = false)
                } else {
                    it.copy(updating = false, error = StorageModeError.CHANGE_FAILED)
                }
            }
        }
    }

    /** Verifies the token with the server, then stores it sealed. Off the main thread. */
    fun connect(url: String, token: String, includeBacklog: Boolean) {
        if (connecting.value) return
        connecting.value = true
        connectError.value = null
        viewModelScope.launch {
            connectError.value = withContext(Dispatchers.IO) { linker.connect(url, token, includeBacklog) }
            connecting.value = false
        }
    }

    fun clearConnectError() {
        connectError.value = null
    }

    /** Outlives the screen: the server-side revoke must not be cancelled by leaving it. */
    fun disconnect() {
        container.appScope.launch(Dispatchers.IO) { linker.disconnect() }
    }

    /** Failed clips start over; unreadable segments are read again. */
    fun retryUploads() {
        container.appScope.launch(Dispatchers.IO) {
            container.database.clips().retryProblems()
            container.uploadScheduler.schedule()
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

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
