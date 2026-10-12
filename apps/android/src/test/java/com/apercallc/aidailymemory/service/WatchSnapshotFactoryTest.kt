package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a journal that restored below what a watch saw is flagged reset`() {
        // Fresh journal (first install, or app data cleared): always reset.
        assertTrue(WatchSnapshotFactory.needsReset(0, lastPublishedRevision = null))
        assertTrue(WatchSnapshotFactory.needsReset(0, lastPublishedRevision = 50))
        // A revision at or past everything published: ordinary, comparable snapshots.
        assertFalse(WatchSnapshotFactory.needsReset(42, lastPublishedRevision = 42))
        assertFalse(WatchSnapshotFactory.needsReset(43, lastPublishedRevision = 42))
        // Deleting the newest session removed its journal rows: the restored revision is
        // below what a watch has already shown, so the snapshot must be accepted unconditionally.
        assertTrue(WatchSnapshotFactory.needsReset(30, lastPublishedRevision = 42))
    }
}
