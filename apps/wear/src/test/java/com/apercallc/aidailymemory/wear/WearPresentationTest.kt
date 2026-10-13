package com.apercallc.aidailymemory.wear

import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.wear.WatchControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearPresentationTest {
    @Test
    fun `an unreachable phone is offline even with a cached recording`() {
        assertEquals(WearTone.OFFLINE, WearPresentation.tone(state(connected = false)))
        assertEquals(WearTone.OFFLINE, WearPresentation.tone(WearControlState(phoneConnected = true)))
    }

    @Test
    fun `healthy recording is the only recording tone`() {
        assertEquals(WearTone.RECORDING, WearPresentation.tone(state()))
        assertEquals(WearTone.ATTENTION, WearPresentation.tone(state(health = "CRITICAL")))
        assertEquals(WearTone.ATTENTION, WearPresentation.tone(state(problem = "STORAGE_LOW")))
    }

    @Test
    fun `every phone status maps to a tone and unknown ones read as offline`() {
        val expected = mapOf(
            "PAUSED" to WearTone.PAUSED,
            "STARTING" to WearTone.BUSY,
            "STOPPING" to WearTone.BUSY,
            "INTERRUPTED" to WearTone.ATTENTION,
            "RECOVERING" to WearTone.ATTENTION,
            "IDLE" to WearTone.IDLE,
            "COMPLETED" to WearTone.IDLE,
            "FUTURE_STATE" to WearTone.OFFLINE,
        )
        expected.forEach { (status, tone) -> assertEquals(tone, WearPresentation.tone(state(status = status)), status) }
        RecordingStatus.entries.forEach { WearPresentation.tone(state(status = it.name)) }
    }

    @Test
    fun `session start shows only for a live, connected session`() {
        assertTrue(WearPresentation.showsSessionStart(state(startedAt = 1L)))
        assertFalse(WearPresentation.showsSessionStart(state(startedAt = null)))
        assertFalse(WearPresentation.showsSessionStart(state(startedAt = 1L, connected = false)))
        assertFalse(WearPresentation.showsSessionStart(state(startedAt = 1L, status = "IDLE")))
        assertFalse(WearPresentation.showsSessionStart(WearControlState(phoneConnected = true)))
    }

    @Test
    fun `who started a session shows for a live connected one even with no start time`() {
        assertTrue(WearPresentation.showsSessionStart(state(startedAt = null), ignoreStartTime = true))
        assertFalse(WearPresentation.showsSessionStart(state(startedAt = null, connected = false), ignoreStartTime = true))
        assertFalse(WearPresentation.showsSessionStart(state(startedAt = null, status = "IDLE"), ignoreStartTime = true))
    }

    @Test
    fun `while recording a marker comes first and Stop comes last`() {
        val shown = WearPresentation.displayOrder(WatchControls.availableCommands(snapshot()))
        assertEquals(
            listOf(WatchCommand.Highlight, WatchCommand.QuickNote, WatchCommand.Pause, WatchCommand.Stop),
            shown,
        )
    }

    @Test
    fun `reordering never adds or drops a command`() {
        RecordingStatus.entries.forEach { status ->
            val offered = WatchControls.availableCommands(snapshot(status = status.name))
            assertEquals(offered.toSet(), WearPresentation.displayOrder(offered).toSet(), status.name)
        }
    }

    @Test
    fun `Stop is the only danger action and starting is primary`() {
        WatchCommand.entries.forEach { command ->
            assertEquals(command == WatchCommand.Stop, WearPresentation.emphasis(command) == Emphasis.DANGER)
        }
        assertEquals(Emphasis.PRIMARY, WearPresentation.emphasis(WatchCommand.Start))
    }

    @Test
    fun `only the two markers carry an explanation`() {
        WatchCommand.entries.forEach { command ->
            val hasHint = WearPresentation.hint(command) != null
            assertEquals(command == WatchCommand.Highlight || command == WatchCommand.QuickNote, hasHint)
        }
        assertNull(WearPresentation.hint(WatchCommand.Stop))
    }

    private fun snapshot(status: String = "RECORDING", health: String = "OK", problem: String? = null, startedAt: Long? = null) =
        PhoneRecordingSnapshot(
            revision = 1,
            status = status,
            sessionStartedAt = startedAt,
            problem = problem,
            captureHealth = health,
        )

    private fun state(
        connected: Boolean = true,
        status: String = "RECORDING",
        health: String = "OK",
        problem: String? = null,
        startedAt: Long? = null,
    ) = WearControlState(phoneConnected = connected, snapshot = snapshot(status, health, problem, startedAt))
}
