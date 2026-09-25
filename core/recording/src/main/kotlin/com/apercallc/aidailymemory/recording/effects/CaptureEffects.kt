package com.apercallc.aidailymemory.recording.effects

import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition

/**
 * Side effect the recorder service must perform after a transition has been persisted.
 *
 * The reducer decides *what* state holds; this decides *what the service does about it*. Keeping
 * the mapping here means the Android service only executes a list; it never branches on status.
 */
sealed interface CaptureEffect {
    /** Keep (or put) the microphone foreground service in the foreground. Idempotent. */
    data object EnsureForeground : CaptureEffect

    /**
     * Open the microphone and start a new segment. The engine answers with `Started` (or
     * `Recovered` when [recovery] is true) once the first buffer arrives, or with a failure.
     *
     * The first segment after an open never continues the previous one: every open is an
     * intentional boundary that is already in the journal, so it is not a gap.
     */
    data class OpenMicrophone(val sessionId: SessionId, val recovery: Boolean) : CaptureEffect

    /** Close the microphone and seal the in-progress segment if its bytes are intact. */
    data class CloseMicrophone(val sessionId: SessionId) : CaptureEffect

    /**
     * Close the microphone, seal, then report `Stopped` for [sessionId]. The service reports
     * `Stopped` even when sealing fails; the lost segment is recorded as a gap instead.
     */
    data class FinishSession(val sessionId: SessionId) : CaptureEffect

    /** No session needs the service any more. Remove the notification and stop. */
    data object StopForeground : CaptureEffect
}

object CaptureEffectPlanner {
    /**
     * Effects for an applied transition, in execution order. Ignored and rejected transitions
     * have no effects: the service only re-publishes the unchanged state.
     */
    fun effectsFor(transition: Transition): List<CaptureEffect> {
        if (transition !is Transition.Applied) return emptyList()
        val state = transition.state
        // Only IDLE has no session (a RecordingState invariant).
        val sessionId = state.sessionId ?: return listOf(CaptureEffect.StopForeground)
        return when (state.status) {
            RecordingStatus.STARTING, RecordingStatus.RESUMING -> listOf(
                CaptureEffect.EnsureForeground,
                CaptureEffect.OpenMicrophone(sessionId, recovery = false),
            )
            RecordingStatus.RECOVERING -> listOf(
                CaptureEffect.EnsureForeground,
                CaptureEffect.OpenMicrophone(sessionId, recovery = true),
            )
            // Paused keeps the service (and its notification) alive with the mic closed, so a
            // later resume from the watch or notification is allowed (ADR-0010). An interrupted
            // session keeps it too: its notification is how the user sees the gap.
            RecordingStatus.PAUSED, RecordingStatus.INTERRUPTED -> listOf(
                CaptureEffect.EnsureForeground,
                CaptureEffect.CloseMicrophone(sessionId),
            )
            RecordingStatus.STOPPING ->
                listOf(CaptureEffect.FinishSession(sessionId))
            RecordingStatus.COMPLETED, RecordingStatus.IDLE ->
                listOf(CaptureEffect.StopForeground)
            // Audio is already flowing; entering RECORDING is a confirmation, not a command.
            RecordingStatus.RECORDING -> emptyList()
        }
    }
}
