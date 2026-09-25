package com.apercallc.aidailymemory.recording.effects

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSignalsTest {
    private val session = SessionId("s")

    private fun state(status: RecordingStatus) = RecordingState(status, session, 1, null)

    @Test
    fun `audio flowing confirms a start, a resume, or a recovery`() {
        val flowing = EngineReport.AudioFlowing(session)

        assertEquals(
            CaptureSignal.Started(session),
            EngineSignals.signalFor(state(RecordingStatus.STARTING), flowing),
        )
        assertEquals(
            CaptureSignal.Started(session),
            EngineSignals.signalFor(state(RecordingStatus.RESUMING), flowing),
        )
        assertEquals(
            CaptureSignal.Recovered(session),
            EngineSignals.signalFor(state(RecordingStatus.RECOVERING), flowing),
        )
    }

    @Test
    fun `failure before the first start drops the session, later failures interrupt it`() {
        val failed = EngineReport.Failed(session, CaptureProblem.PHONE_CALL)

        assertEquals(
            CaptureSignal.StartFailed(session, CaptureProblem.PHONE_CALL),
            EngineSignals.signalFor(state(RecordingStatus.STARTING), failed),
        )
        listOf(RecordingStatus.RECORDING, RecordingStatus.RESUMING, RecordingStatus.RECOVERING)
            .forEach { status ->
                assertEquals(
                    CaptureSignal.Interrupted(session, CaptureProblem.PHONE_CALL),
                    EngineSignals.signalFor(state(status), failed),
                    "$status",
                )
            }
    }
}
