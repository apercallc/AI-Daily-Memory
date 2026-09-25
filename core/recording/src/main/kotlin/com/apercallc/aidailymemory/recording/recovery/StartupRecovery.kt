package com.apercallc.aidailymemory.recording.recovery

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus

enum class RestartCause {
    /** The app process died (OOM, crash, force stop) and was started again. */
    PROCESS_RESTARTED,

    /** The device rebooted. */
    DEVICE_REBOOTED,
}

/**
 * Decides which signals to dispatch when the app starts and finds a persisted session state.
 *
 * Nothing survives a restart silently. A session that claimed to be capturing becomes
 * INTERRUPTED with the cause, so the gap is visible. Capture is never auto-restarted (the
 * platform forbids starting the mic from boot or background, per ADR-0010); the user resumes
 * it from the notification or the app.
 */
object StartupRecovery {
    fun eventsFor(persisted: RecordingState, cause: RestartCause): List<RecordingEvent> {
        val sessionId = persisted.sessionId ?: return emptyList()
        val problem = when (cause) {
            RestartCause.PROCESS_RESTARTED -> CaptureProblem.PROCESS_RESTARTED
            RestartCause.DEVICE_REBOOTED -> CaptureProblem.DEVICE_REBOOTED
        }
        return when (persisted.status) {
            RecordingStatus.STARTING -> listOf(CaptureSignal.StartFailed(sessionId, problem))
            RecordingStatus.RECORDING, RecordingStatus.RECOVERING ->
                listOf(CaptureSignal.Interrupted(sessionId, problem))
            // Finish the stop the user already asked for. Before dispatching, the engine seals
            // the in-progress segment if its file is intact; otherwise it is a reported gap.
            RecordingStatus.STOPPING -> listOf(CaptureSignal.Stopped(sessionId))
            // Already a stable, user-visible state: PAUSED stays paused, INTERRUPTED keeps
            // its original cause, COMPLETED is done.
            RecordingStatus.PAUSED,
            RecordingStatus.INTERRUPTED,
            RecordingStatus.COMPLETED,
            RecordingStatus.IDLE,
            -> emptyList()
        }
    }
}
