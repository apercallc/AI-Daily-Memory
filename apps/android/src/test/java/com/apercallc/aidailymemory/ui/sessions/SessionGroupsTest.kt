package com.apercallc.aidailymemory.ui.sessions

import com.apercallc.aidailymemory.data.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure mapping tests for SessionGroups.group — no Android dependency needed. */
class SessionGroupsTest {

    private fun row(
        sessionId: String,
        day: String = "2026-10-14",
        startedAtEpochMs: Long = 1_000,
        retainedAudioMs: Long = 0,
    ) = SessionRow(
        sessionId = sessionId,
        memoryDay = day,
        zoneId = "UTC",
        startedAtEpochMs = startedAtEpochMs,
        customTitle = null,
        lastActivityEpochMs = startedAtEpochMs + 1_000,
        capturedMs = 1_000,
        retainedAudioMs = retainedAudioMs,
    )

    @Test
    fun `retained audio carries through from the row to the session item`() {
        val groups = SessionGroups.group(listOf(row("with-audio", retainedAudioMs = 60_000)), null)

        assertEquals(60_000L, groups.single().sessions.single().retainedAudioMs)
    }

    @Test
    fun `a session with everything already deleted reports zero retained audio`() {
        val groups = SessionGroups.group(listOf(row("deleted", retainedAudioMs = 0)), null)

        assertEquals(0L, groups.single().sessions.single().retainedAudioMs)
    }

    @Test
    fun `retained audio is independent per session within the same day`() {
        val groups = SessionGroups.group(
            listOf(
                row("first", startedAtEpochMs = 1_000, retainedAudioMs = 30_000),
                row("second", startedAtEpochMs = 2_000, retainedAudioMs = 0),
            ),
            null,
        )

        val bySession = groups.single().sessions.associateBy { it.sessionId }
        assertEquals(30_000L, bySession.getValue("first").retainedAudioMs)
        assertEquals(0L, bySession.getValue("second").retainedAudioMs)
    }
}
