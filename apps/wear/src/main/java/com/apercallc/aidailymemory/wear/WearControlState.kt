package com.apercallc.aidailymemory.wear

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.SnapshotRevisionPolicy
import com.apercallc.aidailymemory.recording.wear.WatchCommand

/** Why the watch cannot show or confirm something. Mapped to strings at render time. */
enum class WearError {
    PHONE_NOT_CONNECTED,
    CONNECTION_CHECK_FAILED,
    SNAPSHOT_READ_FAILED,
    SNAPSHOT_UNCONFIRMED,
    COMMAND_NOT_SENT,
    COMMAND_REMAINS_UNCONFIRMED,
    COMMAND_UNCONFIRMED,
    PHONE_REJECTED,
}

/** Watch state contains only snapshots and outcomes confirmed by the phone. */
data class WearControlState(
    val phoneConnected: Boolean = false,
    val snapshot: PhoneRecordingSnapshot? = null,
    val pendingCommandId: String? = null,
    val pendingCommand: WatchCommand? = null,
    val lastAcknowledgement: CommandAcknowledgement? = null,
    /** The command [lastAcknowledgement] answers, so a refusal can say why. */
    val lastCommand: WatchCommand? = null,
    val error: WearError? = null,
)

object WearControlReducer {
    fun connection(state: WearControlState, connected: Boolean): WearControlState =
        state.copy(phoneConnected = connected, error = if (connected) null else state.error)

    /**
     * The watch offers a way forward whenever it cannot show phone-confirmed state: a
     * disconnected phone, or one that is connected but has not shared a snapshot yet. The
     * second case is the fresh-watch first run — without a retry it is a dead-end screen.
     */
    fun needsPhoneCheck(state: WearControlState): Boolean =
        !state.phoneConnected || state.snapshot == null

    fun phoneSnapshot(
        state: WearControlState,
        incoming: PhoneRecordingSnapshot,
    ): WearControlState {
        if (!SnapshotRevisionPolicy.shouldAccept(
                currentRevision = state.snapshot?.revision,
                incomingRevision = incoming.revision,
                reset = incoming.reset,
            )
        ) {
            return state
        }
        return state.copy(snapshot = incoming, error = null)
    }

    fun commandPending(
        state: WearControlState,
        commandId: String,
        command: WatchCommand,
    ): WearControlState = state.copy(
        pendingCommandId = commandId,
        pendingCommand = command,
        lastAcknowledgement = null,
        lastCommand = null,
        error = null,
    )

    fun acknowledgement(
        state: WearControlState,
        acknowledgement: CommandAcknowledgement,
        phoneSnapshot: PhoneRecordingSnapshot,
    ): WearControlState {
        if (state.pendingCommandId != acknowledgement.commandId) return state
        val updated = phoneSnapshot(state, phoneSnapshot)
        return updated.copy(
            pendingCommandId = null,
            pendingCommand = null,
            lastAcknowledgement = acknowledgement,
            lastCommand = state.pendingCommand,
            error = if (acknowledgement.outcome == CommandOutcome.Rejected) {
                WearError.PHONE_REJECTED
            } else {
                null
            },
        )
    }

    fun commandUnconfirmed(
        state: WearControlState,
        commandId: String,
        reason: WearError,
    ): WearControlState = if (state.pendingCommandId == commandId) {
        state.copy(error = reason)
    } else {
        state
    }

    /**
     * The user gives up on an unconfirmed command. The command is forgotten on the watch only;
     * if the phone did receive it, the next snapshot shows the result. A later command gets a
     * fresh id, so the abandoned one can never be resent by mistake.
     */
    fun commandDismissed(state: WearControlState): WearControlState =
        state.copy(pendingCommandId = null, pendingCommand = null, error = null)
}
