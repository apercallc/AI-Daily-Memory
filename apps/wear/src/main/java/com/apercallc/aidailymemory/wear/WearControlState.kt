package com.apercallc.aidailymemory.wear

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.SnapshotRevisionPolicy
import com.apercallc.aidailymemory.recording.wear.WatchCommand

/** Watch state contains only snapshots and outcomes confirmed by the phone. */
data class WearControlState(
    val phoneConnected: Boolean = false,
    val snapshot: PhoneRecordingSnapshot? = null,
    val pendingCommandId: String? = null,
    val pendingCommand: WatchCommand? = null,
    val lastAcknowledgement: CommandAcknowledgement? = null,
    val error: String? = null,
)

object WearControlReducer {
    fun availableCommands(snapshot: PhoneRecordingSnapshot): List<WatchCommand> {
        val stateCommands = when (snapshot.status) {
            "IDLE", "COMPLETED" -> listOf(WatchCommand.Start)
            "RECORDING" -> listOf(WatchCommand.Pause, WatchCommand.Stop)
            "RESUMING", "RECOVERING" -> listOf(WatchCommand.Pause, WatchCommand.Stop)
            "STARTING" -> listOf(WatchCommand.Stop)
            "PAUSED", "INTERRUPTED" -> listOf(WatchCommand.Resume, WatchCommand.Stop)
            "STOPPING" -> emptyList()
            else -> emptyList()
        }
        if (snapshot.status != "RECORDING" || snapshot.captureHealth == "CRITICAL") {
            return stateCommands
        }
        return stateCommands + listOf(WatchCommand.Highlight, WatchCommand.QuickNote)
    }

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
            error = if (acknowledgement.outcome == CommandOutcome.Rejected) {
                "Phone rejected the command"
            } else {
                null
            },
        )
    }

    fun commandUnconfirmed(
        state: WearControlState,
        commandId: String,
        reason: String,
    ): WearControlState = if (state.pendingCommandId == commandId) {
        state.copy(error = reason)
    } else {
        state
    }
}
