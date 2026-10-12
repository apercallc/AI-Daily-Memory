package com.apercallc.aidailymemory.recording.journal

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition

/**
 * Storage-neutral form of one applied transition in the append-only journal.
 *
 * Enum values are stored by name, so renaming an enum constant is a schema migration. The
 * journal is keyed by [revision]: a second write of the same revision is a bug, not an update.
 */
data class JournalRecord(
    val revision: Long,
    val status: String,
    val sessionId: String?,
    val problem: String?,
    /** What caused the transition, e.g. `command:Pause` or `signal:Interrupted`. */
    val cause: String,
)

sealed interface RestoredState {
    val state: RecordingState

    data class Intact(override val state: RecordingState) : RestoredState

    /**
     * The newest record could not be decoded. Capture state is unknown, so it restarts idle
     * with a visible problem, keeping the revision monotonic (ADR-0008 staleness rule).
     */
    data class Corrupt(override val state: RecordingState, val revision: Long) : RestoredState
}

object JournalCodec {
    private const val SCHEDULED_PAUSE_CAUSE = "command:ScheduledPause"

    /**
     * A scheduled pause applied during an interruption (see the reducer): quiet time must not
     * let its end reopen the microphone by itself, so this pause is *not* a schedule pause.
     * It needs its own cause, because [restore] derives `pausedBySchedule` from the cause.
     */
    private const val SCHEDULED_PAUSE_AFTER_INTERRUPT_CAUSE = "command:ScheduledPauseAfterInterrupt"

    fun encode(transition: Transition.Applied): JournalRecord = JournalRecord(
        revision = transition.state.revision,
        status = transition.state.status.name,
        sessionId = transition.state.sessionId?.value,
        problem = transition.state.problem?.name,
        cause = causeOf(transition.event, transition.state),
    )

    /** @param latest the record with the highest revision, or null for a fresh install. */
    fun restore(latest: JournalRecord?): RestoredState {
        if (latest == null) return RestoredState.Intact(RecordingState.Initial)
        // Unknown enum names and invariant violations both throw IllegalArgumentException.
        // Anything else is a real bug and must crash loudly, so only that type is caught.
        val decoded = try {
            RecordingState(
                status = RecordingStatus.valueOf(latest.status),
                sessionId = latest.sessionId?.let(::SessionId),
                revision = latest.revision,
                problem = latest.problem?.let(CaptureProblem::valueOf),
            )
        } catch (_: IllegalArgumentException) {
            null
        }?.let { state ->
            // Derived, not stored: the newest record is the schedule pause itself exactly
            // while that pause holds, so the flag survives process death without a column.
            state.copy(
                pausedBySchedule = state.status == RecordingStatus.PAUSED &&
                    latest.cause == SCHEDULED_PAUSE_CAUSE,
            )
        }
        return decoded?.let(RestoredState::Intact) ?: RestoredState.Corrupt(
            state = RecordingState.Initial.copy(
                revision = latest.revision.coerceAtLeast(0),
                problem = CaptureProblem.UNKNOWN,
            ),
            revision = latest.revision,
        )
    }

    fun causeOf(event: RecordingEvent, appliedState: RecordingState? = null): String {
        // The same event can mean two things: a ScheduledPause applied during an interruption
        // becomes an ordinary user-resumable pause, unlike one from an active capture state.
        if (event == RecordingCommand.ScheduledPause && appliedState != null &&
            !appliedState.pausedBySchedule
        ) {
            return SCHEDULED_PAUSE_AFTER_INTERRUPT_CAUSE
        }
        return when (event) {
            is RecordingCommand.Start -> "command:Start"
            RecordingCommand.Pause -> "command:Pause"
            RecordingCommand.Resume -> "command:Resume"
            RecordingCommand.Stop -> "command:Stop"
            RecordingCommand.Reset -> "command:Reset"
            RecordingCommand.ScheduledPause -> SCHEDULED_PAUSE_CAUSE
            RecordingCommand.ScheduledResume -> "command:ScheduledResume"
            RecordingCommand.ScheduledStop -> "command:ScheduledStop"
            is CaptureSignal.Started -> "signal:Started"
            is CaptureSignal.StartFailed -> "signal:StartFailed"
            is CaptureSignal.Interrupted -> "signal:Interrupted"
            is CaptureSignal.RecoveryStarted -> "signal:RecoveryStarted"
            is CaptureSignal.Recovered -> "signal:Recovered"
            is CaptureSignal.Stopped -> "signal:Stopped"
        }
    }
}
