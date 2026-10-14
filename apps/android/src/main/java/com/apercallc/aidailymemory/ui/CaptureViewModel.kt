package com.apercallc.aidailymemory.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.capture.CaptureOverview
import com.apercallc.aidailymemory.capture.SessionDataDeletionResult
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin
import com.apercallc.aidailymemory.recording.wear.RejectionExplainer
import com.apercallc.aidailymemory.recording.wear.RejectionReason
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.wear.intentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import com.apercallc.aidailymemory.ondevice.ModelState
import com.apercallc.aidailymemory.settings.ProcessingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class CaptureUiState(
    val model: CaptureStatusModel,
    val overview: CaptureOverview?,
    val stopDecisionSessionId: String?,
    /** Which device started the open session; null when idle or not known. */
    val origin: RecordingOrigin? = null,
)

/**
 * Screen state for the capture screen. Holds no recording state of its own: it only observes
 * the controller and sends commands to the service, so the notification, the app, and later
 * the watch can never disagree.
 */
class CaptureViewModel(
    private val container: AppContainer,
    private val sendCommand: (String) -> Unit,
) : ViewModel() {
    private val handledStopSessionId = MutableStateFlow(container.handledStopDecisionSessionId())
    private val stopDecisionSessionId = combine(
        container.controller.state,
        handledStopSessionId,
    ) { recording, handled ->
        recording.sessionId?.value?.takeIf {
            recording.status == RecordingStatus.COMPLETED && it != handled
        }
    }

    val uiState: StateFlow<CaptureUiState> = combine(
        container.statusModels,
        container.overview,
        stopDecisionSessionId,
        container.controller.state,
    ) { model, overview, pendingStopDecision, recording ->
        CaptureUiState(
            model = model ?: initialModel(),
            overview = overview,
            stopDecisionSessionId = pendingStopDecision,
            origin = container.sessionOrigins.originOf(recording.sessionId),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        CaptureUiState(initialModel(), overview = null, stopDecisionSessionId = null),
    )

    /**
     * The free speech model, while transcribing on the phone is wanted but the model isn't
     * installed yet; null when there is nothing to ask for.
     */
    val speechModelPrompt: StateFlow<ModelState?> = combine(
        container.speechModel.state,
        container.processingSettings.processingEnabled,
        container.processingSettings.transcriptionProvider,
    ) { model, enabled, provider ->
        model.takeIf {
            enabled && provider == ProcessingSettings.PROVIDER_ON_DEVICE && it != ModelState.Installed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val summaryConfigGaps = setOf(
        ConfigGap.EXTRACTION_URL,
        ConfigGap.EXTRACTION_KEY,
        ConfigGap.EXTRACTION_MODEL,
    )

    /** Whether the optional extraction service has all settings needed to create summaries. */
    val summariesConfigured: StateFlow<Boolean> = combine(
        container.processingSettings.extractionProvider,
        container.processingSettings.configRevision,
    ) { provider, _ -> summariesAreConfigured(provider) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = summariesAreConfigured(),
    )

    private fun summariesAreConfigured(
        provider: String = container.processingSettings.extractionProvider.value,
    ): Boolean {
        if (provider != ProcessingSettings.PROVIDER_USER_API) return false
        val configGaps = container.processingSettings.configGaps()
        return summaryConfigGaps.none(configGaps::contains)
    }

    fun downloadSpeechModel() = container.downloadSpeechModel()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** One-shot user feedback (string resources), shown as snackbars. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.controller.rejections.collect { _messages.send(rejectionMessage(it)) }
        }
        // The watch is a remote for this phone: say so when it just did something, so a pause
        // or stop that nobody pressed here is never a mystery.
        viewModelScope.launch {
            container.watchActivity.collect { command -> watchMessage(command)?.let { _messages.send(it) } }
        }
    }

    fun send(action: String) = sendCommand(action)

    fun keepStoppedSession(sessionId: String) {
        markStopDecisionHandled(sessionId)
    }

    fun deleteStoppedSession(sessionId: String) {
        viewModelScope.launch {
            try {
                when (withContext(Dispatchers.IO) { container.sessionDataDeletion.delete(sessionId) }) {
                    SessionDataDeletionResult.Deleted -> markStopDecisionHandled(sessionId)
                    SessionDataDeletionResult.SessionActive -> _messages.send(R.string.session_delete_active)
                    SessionDataDeletionResult.RemoteCopyExists ->
                        _messages.send(R.string.session_delete_remote_copy)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "session_delete_failed", e)
                _messages.send(R.string.session_delete_failed)
            }
        }
    }

    private fun markStopDecisionHandled(sessionId: String) {
        container.markStopDecisionHandled(sessionId)
        handledStopSessionId.value = sessionId
    }

    fun mark(kind: MarkerKind) {
        viewModelScope.launch {
            val message = try {
                messageFor(container.placeMarker(kind))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database failure must not crash the app mid-recording; the mark is not saved.
                logFailure(TAG, "marker_failed", e)
                R.string.marker_failed
            }
            _messages.send(message)
        }
    }

    val storageSnapshot get() = uiState.value.overview?.storage

    @StringRes
    private fun rejectionMessage(rejected: Transition.Rejected): Int {
        val intent = (rejected.event as? RecordingCommand)?.intentOrNull() ?: return R.string.command_rejected
        return when (RejectionExplainer.explain(intent, rejected.state.status)) {
            RejectionReason.ALREADY_RECORDING -> R.string.command_rejected_already_recording
            RejectionReason.NOT_RECORDING -> R.string.command_rejected_not_recording
            RejectionReason.SESSION_STARTING -> R.string.command_rejected_starting
            RejectionReason.SESSION_ENDING -> R.string.command_rejected_ending
            RejectionReason.OTHER -> R.string.command_rejected
        }
    }

    @StringRes
    private fun watchMessage(command: WatchCommand): Int? = when (command) {
        WatchCommand.Start -> R.string.watch_started
        WatchCommand.Pause -> R.string.watch_paused
        WatchCommand.Resume -> R.string.watch_resumed
        WatchCommand.Stop -> R.string.watch_stopped
        // Markers already confirm themselves on both screens.
        WatchCommand.Highlight, WatchCommand.QuickNote -> null
    }

    @StringRes
    private fun messageFor(decision: MarkerDecision): Int = when (decision) {
        is MarkerDecision.Place -> when (decision.kind) {
            MarkerKind.HIGHLIGHT -> R.string.marker_highlighted
            MarkerKind.QUICK_NOTE -> R.string.marker_quick_note
        }
        MarkerDecision.Duplicate -> R.string.marker_duplicate
        MarkerDecision.NotCapturing -> R.string.marker_not_capturing
    }

    private fun initialModel() =
        CaptureStatusPresenter.present(container.controller.state.value, CaptureHealth.Unknown)

    class Factory(
        private val container: AppContainer,
        private val sendCommand: (String) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CaptureViewModel(container, sendCommand) as T
    }

    private companion object {
        const val TAG = "CaptureViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
