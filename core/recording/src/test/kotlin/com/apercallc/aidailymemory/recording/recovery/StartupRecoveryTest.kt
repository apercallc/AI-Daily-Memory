package com.apercallc.aidailymemory.recording.recovery

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StartupRecoveryTest {
    private val session = SessionId("s1")

    private fun persisted(status: RecordingStatus) = RecordingState(
        status = status,
        sessionId = if (status == RecordingStatus.IDLE) null else session,
        revision = 41,
        problem = null,
    )

    private fun recover(status: RecordingStatus, cause: RestartCause): RecordingState =
        StartupRecovery.eventsFor(persisted(status), cause).fold(persisted(status)) { state, e ->
            val result = RecordingStateMachine.reduce(state, e)
            // Recovery must only produce events the reducer accepts, never silent rejections.
            assertIs<Transition.Applied>(result, "$status + $e")
            result.state
        }

    @Test
    fun `capturing sessions become visibly interrupted with the restart cause`() {
        listOf(
            RecordingStatus.RECORDING,
            RecordingStatus.RESUMING,
            RecordingStatus.RECOVERING,
        ).forEach { status ->
            val afterCrash = recover(status, RestartCause.PROCESS_RESTARTED)
            val afterReboot = recover(status, RestartCause.DEVICE_REBOOTED)

            assertEquals(RecordingStatus.INTERRUPTED, afterCrash.status)
            assertEquals(CaptureProblem.PROCESS_RESTARTED, afterCrash.problem)
            assertEquals(CaptureProblem.DEVICE_REBOOTED, afterReboot.problem)
            assertEquals(42, afterCrash.revision)
        }
    }

    @Test
    fun `a start that never finished becomes a failed start`() {
        val state = recover(RecordingStatus.STARTING, RestartCause.DEVICE_REBOOTED)

        assertEquals(RecordingStatus.IDLE, state.status)
        assertEquals(CaptureProblem.DEVICE_REBOOTED, state.problem)
    }

    @Test
    fun `an interrupted stop is completed`() {
        assertEquals(
            RecordingStatus.COMPLETED,
            recover(RecordingStatus.STOPPING, RestartCause.PROCESS_RESTARTED).status,
        )
    }

    @Test
    fun `stable states are left exactly as persisted`() {
        listOf(
            RecordingStatus.IDLE,
            RecordingStatus.PAUSED,
            RecordingStatus.INTERRUPTED,
            RecordingStatus.COMPLETED,
        ).forEach { status ->
            RestartCause.entries.forEach { cause ->
                assertEquals(persisted(status), recover(status, cause), "$status/$cause")
            }
        }
    }
}
