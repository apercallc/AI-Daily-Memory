package com.apercallc.aidailymemory.wear

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WearStringsTest {
    @Test
    fun `disconnected watch does not present a cached recording as current`() {
        assertEquals(R.string.phone_disconnected, WearStrings.headline(state(connected = false)))
    }

    @Test
    fun `missing snapshot never implies a recording state`() {
        assertEquals(R.string.phone_waiting, WearStrings.headline(WearControlState(phoneConnected = true)))
    }

    @Test
    fun `critical capture health does not claim healthy recording`() {
        assertEquals(R.string.status_check_recording, WearStrings.headline(state(health = "CRITICAL")))
        assertEquals(R.string.status_recording, WearStrings.headline(state()))
    }

    @Test
    fun `every phone recording state has a readable label`() {
        RecordingStatus.entries.forEach { status ->
            assertNotEquals(R.string.status_unknown, WearStrings.status(status.name))
        }
        assertEquals(R.string.status_unknown, WearStrings.status("FUTURE_STATE"))
    }

    @Test
    fun `every phone issue has an actionable explanation without wire identifiers`() {
        CaptureProblem.entries.filter { it != CaptureProblem.UNKNOWN }.forEach { problem ->
            assertNotEquals(R.string.problem_unknown, WearStrings.problem(problem.name))
        }
        assertEquals(R.string.problem_unknown, WearStrings.problem("FUTURE_PROBLEM"))
    }

    @Test
    fun `acknowledgements distinguish accepted unchanged and unavailable actions`() {
        assertEquals(R.string.command_applied, WearStrings.acknowledgement(CommandOutcome.Applied))
        assertEquals(R.string.command_ignored, WearStrings.acknowledgement(CommandOutcome.Ignored))
        assertEquals(R.string.command_rejected, WearStrings.acknowledgement(CommandOutcome.Rejected))
    }

    private fun state(connected: Boolean = true, health: String = "OK") = WearControlState(
        phoneConnected = connected,
        snapshot = PhoneRecordingSnapshot(
            revision = 1,
            status = "RECORDING",
            sessionStartedAt = null,
            problem = null,
            captureHealth = health,
        ),
    )
}
