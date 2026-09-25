package com.apercallc.aidailymemory.recording.marker

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkerPolicyTest {
    private val policy = MarkerPolicy.Default

    private fun state(status: RecordingStatus) = RecordingState(
        status = status,
        sessionId = if (status == RecordingStatus.IDLE) null else SessionId("s"),
        revision = 1,
        problem = null,
    )

    private fun decide(
        status: RecordingStatus = RecordingStatus.RECORDING,
        capturing: Boolean = true,
        kind: MarkerKind = MarkerKind.HIGHLIGHT,
        now: Long = 100_000,
        last: Long? = null,
    ) = policy.decide(state(status), capturing, kind, now, last)

    @Test
    fun `highlights look back and quick notes look ahead`() {
        assertEquals(
            MarkerDecision.Place(MarkerKind.HIGHLIGHT, policy.highlightWindow),
            decide(kind = MarkerKind.HIGHLIGHT),
        )
        assertEquals(
            MarkerDecision.Place(MarkerKind.QUICK_NOTE, policy.quickNoteWindow),
            decide(kind = MarkerKind.QUICK_NOTE),
        )
        assertTrue(policy.highlightWindow.beforeMs > policy.highlightWindow.afterMs)
        assertTrue(policy.quickNoteWindow.afterMs > policy.quickNoteWindow.beforeMs)
    }

    @Test
    fun `markers are refused unless audio is really being captured`() {
        RecordingStatus.entries.filter { it != RecordingStatus.RECORDING }.forEach { status ->
            assertEquals(MarkerDecision.NotCapturing, decide(status = status), "$status")
        }
        // RECORDING but health says audio is not being saved.
        assertEquals(MarkerDecision.NotCapturing, decide(capturing = false))
    }

    @Test
    fun `a double tap inside the debounce window is acknowledged once`() {
        assertEquals(MarkerDecision.Duplicate, decide(now = 101_000, last = 100_000))
        assertEquals(MarkerDecision.Duplicate, decide(now = 102_000, last = 100_000))
        assertEquals(
            MarkerDecision.Place(MarkerKind.HIGHLIGHT, policy.highlightWindow),
            decide(now = 102_001, last = 100_000),
        )
    }

    @Test
    fun `a previous marker from the future (other boot) never suppresses a new one`() {
        assertEquals(
            MarkerDecision.Place(MarkerKind.HIGHLIGHT, policy.highlightWindow),
            decide(now = 1_000, last = 50_000),
        )
    }

    @Test
    fun `invalid windows and debounce are rejected`() {
        assertFailsWith<IllegalArgumentException> { MarkerWindow(-1, 10) }
        assertFailsWith<IllegalArgumentException> { MarkerWindow(10, -1) }
        assertFailsWith<IllegalArgumentException> { MarkerWindow(0, 0) }
        assertFailsWith<IllegalArgumentException> {
            policy.copy(debounceMs = -1)
        }
    }
}
