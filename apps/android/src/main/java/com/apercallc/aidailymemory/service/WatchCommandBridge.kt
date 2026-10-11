package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgements
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Executes one watch command and caches the exact phone-authored acknowledgement. */
class WatchCommandBridge(
    private val revision: () -> Long,
    private val dispatch: suspend (RecordingCommand) -> Transition,
    private val placeMarker: suspend (MarkerKind) -> MarkerDecision,
    private val newSessionId: () -> SessionId,
    private val awaitReady: suspend () -> Unit = {},
    private val acknowledgements: CommandAcknowledgements<CommandAcknowledgement> =
        CommandAcknowledgements(128),
) {
    private val commandLock = Mutex()

    suspend fun execute(commandId: String, command: WatchCommand): CommandAcknowledgement =
        commandLock.withLock {
            awaitReady()
            require(commandId.isNotBlank())
            val cached = acknowledgements.get(commandId)
            if (cached != null) {
                cached
            } else {
                val acknowledgement = when (command) {
                    WatchCommand.Start -> fromTransition(
                        commandId,
                        dispatch(RecordingCommand.Start(newSessionId())),
                    )
                    WatchCommand.Pause -> fromTransition(commandId, dispatch(RecordingCommand.Pause))
                    WatchCommand.Resume -> fromTransition(commandId, dispatch(RecordingCommand.Resume))
                    WatchCommand.Stop -> fromTransition(commandId, dispatch(RecordingCommand.Stop))
                    WatchCommand.Highlight -> fromMarker(commandId, placeMarker(MarkerKind.HIGHLIGHT))
                    WatchCommand.QuickNote -> fromMarker(commandId, placeMarker(MarkerKind.QUICK_NOTE))
                }
                acknowledgements.remember(commandId, acknowledgement)
                acknowledgement
            }
        }

    private fun fromTransition(commandId: String, transition: Transition) =
        CommandAcknowledgement(commandId, transition.outcome(), revision())

    private fun fromMarker(commandId: String, decision: MarkerDecision) =
        CommandAcknowledgement(
            commandId = commandId,
            outcome = when (decision) {
                is MarkerDecision.Place -> CommandOutcome.Applied
                MarkerDecision.Duplicate -> CommandOutcome.Ignored
                MarkerDecision.NotCapturing -> CommandOutcome.Rejected
            },
            revision = revision(),
        )

    private fun Transition.outcome(): CommandOutcome = when (this) {
        is Transition.Applied -> CommandOutcome.Applied
        is Transition.Ignored -> CommandOutcome.Ignored
        is Transition.Rejected -> CommandOutcome.Rejected
    }
}
