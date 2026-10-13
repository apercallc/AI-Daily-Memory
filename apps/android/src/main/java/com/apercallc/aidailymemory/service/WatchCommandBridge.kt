package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.wear.AcknowledgementCodec
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgements
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import kotlin.coroutines.cancellation.CancellationException
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
    /**
     * Called with the encoded recent acknowledgements after each new one, so a restarted phone
     * still answers a retried command with its original outcome instead of running it twice.
     */
    private val persist: (String) -> Unit = {},
    /** Called before a Start is dispatched, so the new session's first snapshot knows its origin. */
    private val recordOrigin: (SessionId, RecordingOrigin) -> Unit = { _, _ -> },
    /** Called when that Start was not applied, so a refused start leaves no entry behind. */
    private val forgetOrigin: (SessionId) -> Unit = {},
    /**
     * Called once for each command the phone actually acted on (never for a redelivered one),
     * so the phone can tell the person that their watch just did something.
     */
    private val onApplied: (WatchCommand) -> Unit = {},
) {
    private val commandLock = Mutex()

    suspend fun execute(commandId: String, command: WatchCommand): CommandAcknowledgement =
        commandLock.withLock {
            awaitReady()
            require(AcknowledgementCodec.isEncodable(commandId))
            val cached = acknowledgements.get(commandId)
            if (cached != null) {
                cached
            } else {
                val acknowledgement = when (command) {
                    WatchCommand.Start -> {
                        val sessionId = newSessionId()
                        recordOrigin(sessionId, RecordingOrigin.WATCH)
                        val transition = try {
                            dispatch(RecordingCommand.Start(sessionId))
                        } catch (e: CancellationException) {
                            forgetOrigin(sessionId)
                            throw e
                        } catch (e: RuntimeException) {
                            // No session was opened, so the origin must not outlive the failed Start.
                            forgetOrigin(sessionId)
                            throw e
                        }
                        if (transition !is Transition.Applied) forgetOrigin(sessionId)
                        fromTransition(commandId, transition)
                    }
                    WatchCommand.Pause -> fromTransition(commandId, dispatch(RecordingCommand.Pause))
                    WatchCommand.Resume -> fromTransition(commandId, dispatch(RecordingCommand.Resume))
                    WatchCommand.Stop -> fromTransition(commandId, dispatch(RecordingCommand.Stop))
                    WatchCommand.Highlight -> fromMarker(commandId, placeMarker(MarkerKind.HIGHLIGHT))
                    WatchCommand.QuickNote -> fromMarker(commandId, placeMarker(MarkerKind.QUICK_NOTE))
                }
                acknowledgements.remember(commandId, acknowledgement)
                persist(AcknowledgementCodec.encode(acknowledgements.snapshot().map { it.second }))
                if (acknowledgement.outcome == CommandOutcome.Applied) onApplied(command)
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

/** Rebuilds the bounded cache from what an earlier process persisted. */
fun restoreAcknowledgements(stored: String?, capacity: Int = 128): CommandAcknowledgements<CommandAcknowledgement> =
    CommandAcknowledgements<CommandAcknowledgement>(capacity).also { cache ->
        AcknowledgementCodec.decode(stored).forEach { cache.remember(it.commandId, it) }
    }
