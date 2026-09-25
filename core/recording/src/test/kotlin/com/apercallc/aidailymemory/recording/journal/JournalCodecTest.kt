package com.apercallc.aidailymemory.recording.journal

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JournalCodecTest {
    private val session = SessionId("0192-session")

    @Test
    fun `every applied transition round-trips through the journal`() {
        val events = listOf(
            RecordingCommand.Start(session),
            CaptureSignal.Started(session),
            RecordingCommand.Pause,
            RecordingCommand.Resume,
            CaptureSignal.Interrupted(session, CaptureProblem.PHONE_CALL),
            CaptureSignal.RecoveryStarted(session),
            CaptureSignal.Recovered(session),
            RecordingCommand.ScheduledPause,
            RecordingCommand.ScheduledResume,
            CaptureSignal.Started(session),
            RecordingCommand.ScheduledPause,
            RecordingCommand.Pause, // becomes the user's pause
            RecordingCommand.ScheduledStop,
            CaptureSignal.Stopped(session),
            RecordingCommand.Reset,
        )
        var state = RecordingState.Initial
        events.forEach { event ->
            val applied = assertIs<Transition.Applied>(RecordingStateMachine.reduce(state, event))
            val record = JournalCodec.encode(applied)

            assertEquals(applied.state.revision, record.revision)
            assertEquals(RestoredState.Intact(applied.state), JournalCodec.restore(record))
            state = applied.state
        }
    }

    @Test
    fun `a schedule pause survives process death, a user pause never turns into one`() {
        val bySchedule = JournalCodec.restore(
            JournalRecord(9, "PAUSED", "s", null, "command:ScheduledPause"),
        )
        val byUser = JournalCodec.restore(JournalRecord(9, "PAUSED", "s", null, "command:Pause"))
        // A schedule-pause cause on a non-paused record can't mark it (and must not throw).
        val odd = JournalCodec.restore(JournalRecord(9, "RECORDING", "s", null, "command:ScheduledPause"))

        assertEquals(true, bySchedule.state.pausedBySchedule)
        assertEquals(false, byUser.state.pausedBySchedule)
        assertIs<RestoredState.Intact>(odd)
        assertEquals(false, odd.state.pausedBySchedule)
    }

    @Test
    fun `an empty journal restores the initial state`() {
        assertEquals(RestoredState.Intact(RecordingState.Initial), JournalCodec.restore(null))
    }

    @Test
    fun `an undecodable newest record restores idle with a visible problem and its revision`() {
        val corruptRecords = listOf(
            JournalRecord(12, "FLYING", "s", null, "command:Start"),
            JournalRecord(12, "PAUSED", "s", "GREMLINS", "command:Pause"),
            JournalRecord(12, "PAUSED", null, null, "command:Pause"), // invariant broken
            JournalRecord(12, "PAUSED", " ", null, "command:Pause"), // blank session
        )
        corruptRecords.forEach { record ->
            val restored = assertIs<RestoredState.Corrupt>(JournalCodec.restore(record), "$record")
            assertEquals(12, restored.revision)
            assertEquals(
                RecordingState.Initial.copy(revision = 12, problem = CaptureProblem.UNKNOWN),
                restored.state,
            )
        }
    }

    @Test
    fun `a negative revision cannot poison the restored state`() {
        val restored = JournalCodec.restore(JournalRecord(-5, "IDLE", null, null, "x"))

        val corrupt = assertIs<RestoredState.Corrupt>(restored)
        assertEquals(0, corrupt.state.revision)
        assertEquals(-5, corrupt.revision)
    }

    @Test
    fun `every event has a distinct stable cause name`() {
        val events: List<RecordingEvent> = listOf(
            RecordingCommand.Start(session),
            RecordingCommand.Pause,
            RecordingCommand.Resume,
            RecordingCommand.Stop,
            RecordingCommand.Reset,
            CaptureSignal.Started(session),
            CaptureSignal.StartFailed(session, CaptureProblem.UNKNOWN),
            CaptureSignal.Interrupted(session, CaptureProblem.UNKNOWN),
            CaptureSignal.RecoveryStarted(session),
            CaptureSignal.Recovered(session),
            CaptureSignal.Stopped(session),
            RecordingCommand.ScheduledPause,
            RecordingCommand.ScheduledResume,
            RecordingCommand.ScheduledStop,
        )
        val causes = events.map(JournalCodec::causeOf)

        assertEquals(causes.size, causes.toSet().size)
        assertEquals("command:Pause", JournalCodec.causeOf(RecordingCommand.Pause))
        assertEquals("signal:Interrupted", causes[7])
    }
}
