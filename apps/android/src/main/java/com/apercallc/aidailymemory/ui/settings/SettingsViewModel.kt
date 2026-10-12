package com.apercallc.aidailymemory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.OffState
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.schedule.RhythmPlanner
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.net.ConnectError
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val storageMode = MutableStateFlow<String?>(null)
    private val storageModeUpdating = MutableStateFlow(false)

    init {
        // Refetched from scratch on every link change: a mode fetched under an old token must
        // never be shown as if it were the new account's.
        viewModelScope.launch {
            container.links.link.collectLatest { link ->
                storageMode.value = null
                if (link == null) return@collectLatest
                storageMode.value = try {
                    withContext(Dispatchers.IO) { container.accountApi.me(link) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(TAG, "storage_mode_fetch_failed", e)
                    null
                }
            }
        }
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
        combine(storageMode, storageModeUpdating, ::Pair),
    ) { (link, problem, revoke), busy, error, problems, (mode, modeUpdating) ->
        AccountUi(
            host = link?.let { hostOf(it.baseUrl) },
            problem = problem,
            connecting = busy,
            error = error,
            uploadProblems = problems,
            revokePending = revoke.first,
            revokeManual = revoke.second,
            storageMode = mode,
            storageModeUpdating = modeUpdating,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUi())

    // --- Processing & sync settings (ADR-0020) ---

    private val pSettings = container.processingSettings

    val processing: StateFlow<ProcessingUi> = combine(
        pSettings.processingEnabled,
        pSettings.transcriptionProvider,
        pSettings.deleteAudioAfterTranscription,
        pSettings.configRevision,
    ) { enabled, tp, deleteAudio, _ ->
        ProcessingUi(
            enabled = enabled,
            transcriptionProvider = tp,
            transcriptionApiKey = pSettings.transcriptionApiKey ?: "",
            transcriptionApiUrl = pSettings.transcriptionApiUrl ?: "",
            extractionProvider = pSettings.extractionProvider.value,
            extractionApiKey = pSettings.extractionApiKey ?: "",
            extractionApiUrl = pSettings.extractionApiUrl ?: "",
            extractionModel = pSettings.extractionModel ?: "",
            deleteAudioAfterTranscription = deleteAudio,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProcessingUi())

    val sync: StateFlow<SyncUi> = combine(
        pSettings.syncEnabled,
        pSettings.syncIncludeTranscripts,
        pSettings.lastSyncFailed,
        pSettings.configRevision,
    ) { enabled, includeTranscripts, lastSyncFailed, _ ->
        SyncUi(
            enabled = enabled,
            url = pSettings.syncUrl ?: "",
            token = pSettings.syncToken ?: "",
            includeTranscripts = includeTranscripts,
            lastSyncEpochMs = pSettings.lastSyncEpochMs,
            lastSyncFailed = lastSyncFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUi())

    val processingActions = ProcessingActions(
        onEnableChange = { enabled ->
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
            pSettings.transcriptionApiUrl = value
            onProcessingSettingsChanged()
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
            pSettings.extractionApiUrl = value
            onProcessingSettingsChanged()
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
            pSettings.syncUrl = value
            onProcessingSettingsChanged()
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
        if (storageModeUpdating.value) return
        storageModeUpdating.value = true
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
            if (applied) storageMode.value = mode
            storageModeUpdating.value = false
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
