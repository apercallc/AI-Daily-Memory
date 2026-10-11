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
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.RecordingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
    ) { model, overview, pendingStopDecision ->
        CaptureUiState(model ?: initialModel(), overview, pendingStopDecision)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        CaptureUiState(initialModel(), overview = null, stopDecisionSessionId = null),
    )

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** One-shot user feedback (string resources), shown as snackbars. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.controller.rejections.collect { _messages.send(R.string.command_rejected) }
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
            _messages.send(messageFor(container.placeMarker(kind)))
        }
    }

    val storageSnapshot get() = uiState.value.overview?.storage

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
