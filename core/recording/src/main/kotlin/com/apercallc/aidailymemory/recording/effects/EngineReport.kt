package com.apercallc.aidailymemory.recording.effects

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId

/**
 * What the capture engine observed, without knowing why the microphone was opened. The same
 * observation means different things in different states, and that decision belongs here.
 */
sealed interface EngineReport {
    val sessionId: SessionId

    /** The first audio buffer arrived after an open. */
    data class AudioFlowing(override val sessionId: SessionId) : EngineReport

    /** The microphone could not be opened, or stopped delivering real audio. */
    data class Failed(override val sessionId: SessionId, val problem: CaptureProblem) :
        EngineReport
}

object EngineSignals {
    fun signalFor(state: RecordingState, report: EngineReport): CaptureSignal = when (report) {
        is EngineReport.AudioFlowing ->
            if (state.status == RecordingStatus.RECOVERING) {
                CaptureSignal.Recovered(report.sessionId)
            } else {
                CaptureSignal.Started(report.sessionId)
            }
        // A failed first start drops the session; anything later keeps it, visibly interrupted.
        is EngineReport.Failed ->
            if (state.status == RecordingStatus.STARTING) {
                CaptureSignal.StartFailed(report.sessionId, report.problem)
            } else {
                CaptureSignal.Interrupted(report.sessionId, report.problem)
            }
    }
}
