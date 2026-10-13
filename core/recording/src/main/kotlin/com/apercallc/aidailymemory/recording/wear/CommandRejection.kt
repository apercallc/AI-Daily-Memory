package com.apercallc.aidailymemory.recording.wear

import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus

/** What a person was trying to do, regardless of which device they pressed it on. */
enum class CommandIntent { START, PAUSE, RESUME, STOP, MARKER }

/** Why a command was refused, in terms the user can act on. */
enum class RejectionReason {
    /** A session is already open: there is one microphone, so a second start is refused. */
    ALREADY_RECORDING,
    NOT_RECORDING,
    SESSION_STARTING,
    SESSION_ENDING,
    OTHER,
}

val WatchCommand.intent: CommandIntent
    get() = when (this) {
        WatchCommand.Start -> CommandIntent.START
        WatchCommand.Pause -> CommandIntent.PAUSE
        WatchCommand.Resume -> CommandIntent.RESUME
        WatchCommand.Stop -> CommandIntent.STOP
        WatchCommand.Highlight, WatchCommand.QuickNote -> CommandIntent.MARKER
    }

/** The intent behind a person's command; null for schedule and housekeeping commands. */
fun RecordingCommand.intentOrNull(): CommandIntent? = when (this) {
    is RecordingCommand.Start -> CommandIntent.START
    RecordingCommand.Pause -> CommandIntent.PAUSE
    RecordingCommand.Resume -> CommandIntent.RESUME
    RecordingCommand.Stop -> CommandIntent.STOP
    else -> null
}

/**
 * Turns "the phone refused" into a reason. Pure and shared, so the phone and the watch tell the
 * user the same thing when both are pressed at once (for example Start on both within a second).
 */
object RejectionExplainer {
    fun explain(intent: CommandIntent, status: RecordingStatus): RejectionReason = when (status) {
        RecordingStatus.STOPPING -> RejectionReason.SESSION_ENDING
        RecordingStatus.IDLE, RecordingStatus.COMPLETED ->
            if (intent == CommandIntent.START) RejectionReason.OTHER else RejectionReason.NOT_RECORDING
        RecordingStatus.STARTING ->
            if (intent == CommandIntent.START) RejectionReason.ALREADY_RECORDING else RejectionReason.SESSION_STARTING
        RecordingStatus.RECORDING,
        RecordingStatus.PAUSED,
        RecordingStatus.RESUMING,
        RecordingStatus.INTERRUPTED,
        RecordingStatus.RECOVERING,
        -> when (intent) {
            CommandIntent.START -> RejectionReason.ALREADY_RECORDING
            CommandIntent.MARKER -> RejectionReason.NOT_RECORDING
            else -> RejectionReason.OTHER
        }
    }

    /** For a status string from a snapshot; an unknown status (a newer phone) is [RejectionReason.OTHER]. */
    fun explain(intent: CommandIntent, status: String): RejectionReason {
        val parsed = RecordingStatus.entries.firstOrNull { it.name == status } ?: return RejectionReason.OTHER
        return explain(intent, parsed)
    }
}
