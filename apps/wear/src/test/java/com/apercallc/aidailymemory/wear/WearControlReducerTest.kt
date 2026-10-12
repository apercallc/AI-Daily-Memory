package com.apercallc.aidailymemory.wear

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearControlReducerTest {
    @Test
    fun `sending a command leaves the last phone snapshot unchanged until acknowledgement`() {
        val initial = WearControlState(phoneConnected = true, snapshot = snapshot(4, "RECORDING"))

        val pending = WearControlReducer.commandPending(initial, "cmd-1", WatchCommand.Start)

        assertEquals(initial.snapshot, pending.snapshot)
        assertEquals("cmd-1", pending.pendingCommandId)
        assertEquals(WatchCommand.Start, pending.pendingCommand)
        assertEquals("RECORDING", pending.snapshot?.status)
    }

    @Test
    fun `stale phone snapshot cannot move acknowledged state backward`() {
        val current = WearControlState(phoneConnected = true, snapshot = snapshot(8, "PAUSED"))

        val reduced = WearControlReducer.phoneSnapshot(current, snapshot(7, "RECORDING"))

        assertEquals(current.snapshot, reduced.snapshot)
    }

    @Test
    fun `only matching ack updates the state and clears the pending command`() {
        val current = WearControlState(
            phoneConnected = true,
            snapshot = snapshot(4, "RECORDING"),
            pendingCommandId = "cmd-1",
            pendingCommand = WatchCommand.Pause,
        )
        val wrongAck = CommandAcknowledgement("other", CommandOutcome.Applied, 5)
        val matchingAck = CommandAcknowledgement("cmd-1", CommandOutcome.Applied, 5)

        assertEquals(current, WearControlReducer.acknowledgement(current, wrongAck, snapshot(5, "PAUSED")))
        val reduced = WearControlReducer.acknowledgement(current, matchingAck, snapshot(5, "PAUSED"))

        assertEquals("PAUSED", reduced.snapshot?.status)
        assertEquals(5, reduced.snapshot?.revision)
        assertNull(reduced.pendingCommandId)
        assertNull(reduced.pendingCommand)
        assertEquals(matchingAck, reduced.lastAcknowledgement)
    }

    @Test
    fun `unconfirmed command remains pending so a retry can reuse its idempotency key`() {
        val current = WearControlState(
            phoneConnected = true,
            snapshot = snapshot(4, "RECORDING"),
            pendingCommandId = "cmd-1",
            pendingCommand = WatchCommand.Highlight,
        )

        val reduced = WearControlReducer.commandUnconfirmed(current, "cmd-1", "No phone confirmation")

        assertEquals(current.snapshot, reduced.snapshot)
        assertEquals("cmd-1", reduced.pendingCommandId)
        assertEquals(WatchCommand.Highlight, reduced.pendingCommand)
        assertEquals("No phone confirmation", reduced.error)
    }

    @Test
    fun `available controls follow only phone-confirmed state`() {
        assertEquals(
            listOf(WatchCommand.Start),
            WearControlReducer.availableCommands(snapshot(1, "IDLE")),
        )
        assertEquals(
            listOf(WatchCommand.Pause, WatchCommand.Stop, WatchCommand.Highlight, WatchCommand.QuickNote),
            WearControlReducer.availableCommands(snapshot(2, "RECORDING")),
        )
        assertEquals(
            listOf(WatchCommand.Resume, WatchCommand.Stop),
            WearControlReducer.availableCommands(snapshot(3, "PAUSED")),
        )
        assertEquals(emptyList(), WearControlReducer.availableCommands(snapshot(4, "STOPPING")))
    }

    @Test
    fun `a way forward exists while disconnected and until the first snapshot arrives`() {
        // Fresh watch, phone not checked yet.
        assertTrue(WearControlReducer.needsPhoneCheck(WearControlState()))
        // First run dead-end fix: connected, but no phone-acknowledged state yet.
        assertTrue(WearControlReducer.needsPhoneCheck(WearControlState(phoneConnected = true)))
        // With an acknowledged snapshot the controls take over instead.
        assertFalse(
            WearControlReducer.needsPhoneCheck(
                WearControlState(phoneConnected = true, snapshot = snapshot(1, "IDLE")),
            ),
        )
    }

    private fun snapshot(revision: Long, status: String) = PhoneRecordingSnapshot(
        revision = revision,
        status = status,
        sessionStartedAt = null,
        problem = null,
        captureHealth = "UNKNOWN",
    )
}
