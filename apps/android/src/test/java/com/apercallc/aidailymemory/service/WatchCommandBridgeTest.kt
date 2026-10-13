package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerWindow
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCommandBridgeTest {
    @Test
    fun `a restarted phone answers a retried command from the persisted acknowledgement`() = runTest {
        var stored: String? = null
        var dispatches = 0
        fun newBridge() = WatchCommandBridge(
            revision = { 7L },
            dispatch = {
                dispatches++
                Transition.Applied(RecordingState.Initial, RecordingState.Initial.copy(revision = 7), it)
            },
            placeMarker = { MarkerDecision.NotCapturing },
            newSessionId = { SessionId("session") },
            acknowledgements = restoreAcknowledgements(stored),
            persist = { stored = it },
        )

        val first = newBridge().execute("cmd-1", WatchCommand.Pause)
        // Process restart: a fresh bridge rebuilt from what was persisted.
        val retried = newBridge().execute("cmd-1", WatchCommand.Pause)

        assertEquals(first, retried)
        assertEquals(1, dispatches)
    }

    @Test
    fun `waits for the phone journal to restore before dispatching`() = runTest {
        var restored = false
        val bridge = WatchCommandBridge(
            revision = { 0L },
            dispatch = {
                assertTrue(restored)
                Transition.Ignored(RecordingState.Initial, it)
            },
            placeMarker = { MarkerDecision.NotCapturing },
            newSessionId = { SessionId("session") },
            awaitReady = { restored = true },
        )

        bridge.execute("restore-gated", WatchCommand.Pause)

        assertTrue(restored)
    }

    @Test
    fun mapsWatchCommandsToPhoneCommandsAndMarkers() = runTest {
        val actions = mutableListOf<Any>()
        val bridge = WatchCommandBridge(
            revision = { 12L },
            dispatch = { command ->
                actions += command
                Transition.Ignored(RecordingState.Initial, command)
            },
            placeMarker = { kind ->
                actions += kind
                MarkerDecision.Place(kind, MarkerWindow(1, 1))
            },
            newSessionId = { SessionId("session") },
        )

        WatchCommand.entries.forEachIndexed { index, command ->
            bridge.execute("id-$index", command)
        }

        assertEquals(
            listOf(
                RecordingCommand.Start(SessionId("session")), RecordingCommand.Pause,
                RecordingCommand.Resume, RecordingCommand.Stop,
                MarkerKind.HIGHLIGHT, MarkerKind.QUICK_NOTE,
            ),
            actions,
        )
    }

    @Test
    fun acknowledgementsUseReducerResultAndAuthoritativeRevisionAndDeduplicate() = runTest {
        var dispatches = 0
        var revision = 4L
        val bridge = WatchCommandBridge(
            revision = { revision },
            dispatch = {
                dispatches++
                revision = 9L
                val event = RecordingCommand.Pause
                Transition.Applied(RecordingState.Initial, RecordingState.Initial.copy(revision = 5), event)
            },
            placeMarker = { MarkerDecision.Place(it, MarkerWindow(1, 1)) },
            newSessionId = { SessionId("session") },
        )

        val first = bridge.execute("same-id", WatchCommand.Pause)
        revision = 12L
        val duplicate = bridge.execute("same-id", WatchCommand.Pause)

        assertEquals(CommandAcknowledgement("same-id", CommandOutcome.Applied, 9), first)
        assertEquals(first, duplicate)
        assertEquals(1, dispatches)
    }

    @Test
    fun ignoredRejectedAndMarkerFailuresAreAcknowledgedWithoutInventingState() = runTest {
        var revision = 20L
        val bridge = WatchCommandBridge(
            revision = { revision },
            dispatch = { Transition.Rejected(RecordingState.Initial.copy(revision = 20), it) },
            placeMarker = { MarkerDecision.NotCapturing },
            newSessionId = { SessionId("session") },
        )

        assertEquals(
            CommandAcknowledgement("reject", CommandOutcome.Rejected, 20),
            bridge.execute("reject", WatchCommand.Start),
        )
        assertEquals(
            CommandAcknowledgement("marker", CommandOutcome.Rejected, 20),
            bridge.execute("marker", WatchCommand.Highlight),
        )

        val ignored = WatchCommandBridge(
            revision = { revision },
            dispatch = {
                revision = 22L
                Transition.Ignored(RecordingState.Initial.copy(revision = revision), it)
            },
            placeMarker = { MarkerDecision.Place(it, MarkerWindow(1, 1)) },
            newSessionId = { SessionId("session") },
        )
        assertEquals(
            CommandAcknowledgement("ignored", CommandOutcome.Ignored, 22),
            ignored.execute("ignored", WatchCommand.Pause),
        )
    }

    @Test
    fun `concurrent redelivery with one id dispatches exactly once`() = runTest {
        var dispatches = 0
        val bridge = WatchCommandBridge(
            revision = { 3L },
            dispatch = {
                dispatches++
                delay(10)
                Transition.Ignored(RecordingState.Initial, it)
            },
            placeMarker = { MarkerDecision.NotCapturing },
            newSessionId = { SessionId("session") },
        )

        val acknowledgements = listOf(
            async { bridge.execute("same-id", WatchCommand.Pause) },
            async { bridge.execute("same-id", WatchCommand.Pause) },
        ).awaitAll()

        assertEquals(1, dispatches)
        assertEquals(acknowledgements[0], acknowledgements[1])
    }
}
