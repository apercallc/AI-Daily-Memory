package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSnapshotFactoryTest {
    @Test
    fun `snapshot preserves current phone status problem revision and health`() {
        val state = RecordingState(
            status = RecordingStatus.INTERRUPTED,
            sessionId = SessionId("session"),
            revision = 7,
            problem = CaptureProblem.FOREGROUND_START_NOT_ALLOWED,
        )

        val snapshot = WatchSnapshotFactory.from(state, "CRITICAL")

        assertEquals(7L, snapshot.revision)
        assertEquals("INTERRUPTED", snapshot.status)
        assertEquals("FOREGROUND_START_NOT_ALLOWED", snapshot.problem)
        assertEquals("CRITICAL", snapshot.captureHealth)
        assertEquals(false, snapshot.reset)
    }

    @Test
    fun `fresh phone snapshot is marked reset for a watch with older state`() {
        val snapshot = WatchSnapshotFactory.from(RecordingState.Initial, "UNKNOWN", reset = true)

        assertEquals(0L, snapshot.revision)
        assertEquals("IDLE", snapshot.status)
        assertEquals(true, snapshot.reset)
    }
}
