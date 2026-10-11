package com.apercallc.aidailymemory.wear.data

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgements
import com.apercallc.aidailymemory.recording.wear.SnapshotRevisionPolicy
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchProtocolTest {
    @Test
    fun acceptsOnlyPhoneSnapshotsThatDoNotMoveRevisionBackward() {
        assertTrue(SnapshotRevisionPolicy.shouldAccept(currentRevision = null, incomingRevision = 4))
        assertTrue(SnapshotRevisionPolicy.shouldAccept(currentRevision = 4, incomingRevision = 4))
        assertTrue(SnapshotRevisionPolicy.shouldAccept(currentRevision = 4, incomingRevision = 5))
        assertFalse(SnapshotRevisionPolicy.shouldAccept(currentRevision = 5, incomingRevision = 4))
        assertTrue(SnapshotRevisionPolicy.shouldAccept(currentRevision = 5, incomingRevision = 0, reset = true))
    }

    @Test
    fun mapsOnlyAllowedControllerCommandsToPhoneActions() {
        assertEquals("START", WatchCommand.Start.serviceAction)
        assertEquals("PAUSE", WatchCommand.Pause.serviceAction)
        assertEquals("RESUME", WatchCommand.Resume.serviceAction)
        assertEquals("STOP", WatchCommand.Stop.serviceAction)
        assertEquals("HIGHLIGHT", WatchCommand.Highlight.serviceAction)
        assertEquals("QUICK_NOTE", WatchCommand.QuickNote.serviceAction)
    }

    @Test
    fun duplicateCommandIdsReturnTheOriginalAcknowledgement() {
        val outcomes = CommandAcknowledgements<String>(capacity = 2)
        outcomes.remember("id-1", "Applied:4")
        outcomes.remember("id-2", "Ignored:4")

        assertEquals("Applied:4", outcomes.get("id-1"))
        outcomes.remember("id-3", "Rejected:4")
        assertEquals("Applied:4", outcomes.get("id-1"))
        assertEquals(null, outcomes.get("id-2"))
    }

    @Test
    fun quickNoteAndHighlightAreExplicitlyCaptureLayerActions() {
        assertTrue(WatchCommand.Highlight.isMarker)
        assertTrue(WatchCommand.QuickNote.isMarker)
        assertFalse(WatchCommand.Start.isMarker)
        assertFalse(WatchCommand.Pause.isMarker)
    }
}
