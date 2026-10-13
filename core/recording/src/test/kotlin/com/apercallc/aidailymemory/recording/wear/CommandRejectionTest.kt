package com.apercallc.aidailymemory.recording.wear

import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandRejectionTest {
    @Test
    fun `a second start while a session is open says it is already recording`() {
        listOf(
            RecordingStatus.STARTING,
            RecordingStatus.RECORDING,
            RecordingStatus.PAUSED,
            RecordingStatus.RESUMING,
            RecordingStatus.INTERRUPTED,
            RecordingStatus.RECOVERING,
        ).forEach {
            assertEquals(RejectionReason.ALREADY_RECORDING, RejectionExplainer.explain(CommandIntent.START, it), "$it")
        }
    }

    @Test
    fun `commands while nothing is open say it is not recording`() {
        listOf(CommandIntent.PAUSE, CommandIntent.RESUME, CommandIntent.STOP, CommandIntent.MARKER).forEach { intent ->
            assertEquals(RejectionReason.NOT_RECORDING, RejectionExplainer.explain(intent, RecordingStatus.IDLE))
            assertEquals(RejectionReason.NOT_RECORDING, RejectionExplainer.explain(intent, RecordingStatus.COMPLETED))
        }
    }

    @Test
    fun `a start that is refused while idle has no better reason`() {
        assertEquals(RejectionReason.OTHER, RejectionExplainer.explain(CommandIntent.START, RecordingStatus.IDLE))
        assertEquals(RejectionReason.OTHER, RejectionExplainer.explain(CommandIntent.START, RecordingStatus.COMPLETED))
    }

    @Test
    fun `a session that is still starting or already ending is called out`() {
        assertEquals(RejectionReason.SESSION_STARTING, RejectionExplainer.explain(CommandIntent.PAUSE, RecordingStatus.STARTING))
        CommandIntent.entries.forEach {
            assertEquals(RejectionReason.SESSION_ENDING, RejectionExplainer.explain(it, RecordingStatus.STOPPING))
        }
    }

    @Test
    fun `a marker while paused says it is not recording`() {
        assertEquals(RejectionReason.NOT_RECORDING, RejectionExplainer.explain(CommandIntent.MARKER, RecordingStatus.PAUSED))
        assertEquals(RejectionReason.OTHER, RejectionExplainer.explain(CommandIntent.PAUSE, RecordingStatus.RECORDING))
    }

    @Test
    fun `status strings from a snapshot are understood and unknown ones are not guessed`() {
        assertEquals(RejectionReason.ALREADY_RECORDING, RejectionExplainer.explain(CommandIntent.START, "RECORDING"))
        assertEquals(RejectionReason.OTHER, RejectionExplainer.explain(CommandIntent.START, "SOMETHING_NEW"))
    }

    @Test
    fun `watch commands and phone commands map to the same intents`() {
        assertEquals(CommandIntent.START, WatchCommand.Start.intent)
        assertEquals(CommandIntent.PAUSE, WatchCommand.Pause.intent)
        assertEquals(CommandIntent.RESUME, WatchCommand.Resume.intent)
        assertEquals(CommandIntent.STOP, WatchCommand.Stop.intent)
        assertEquals(CommandIntent.MARKER, WatchCommand.Highlight.intent)
        assertEquals(CommandIntent.MARKER, WatchCommand.QuickNote.intent)

        assertEquals(CommandIntent.START, RecordingCommand.Start(SessionId("s")).intentOrNull())
        assertEquals(CommandIntent.PAUSE, RecordingCommand.Pause.intentOrNull())
        assertEquals(CommandIntent.RESUME, RecordingCommand.Resume.intentOrNull())
        assertEquals(CommandIntent.STOP, RecordingCommand.Stop.intentOrNull())
        assertNull(RecordingCommand.ScheduledPause.intentOrNull())
        assertNull(RecordingCommand.Reset.intentOrNull())
    }

    @Test
    fun `origin names parse and unknown ones are null`() {
        assertEquals(RecordingOrigin.WATCH, RecordingOrigin.parse("WATCH"))
        assertEquals(RecordingOrigin.PHONE, RecordingOrigin.parse("PHONE"))
        assertNull(RecordingOrigin.parse(null))
        assertNull(RecordingOrigin.parse("TABLET"))
    }
}
