package com.apercallc.aidailymemory.recording

import com.apercallc.aidailymemory.recording.RecordingStatus.COMPLETED
import com.apercallc.aidailymemory.recording.RecordingStatus.IDLE
import com.apercallc.aidailymemory.recording.RecordingStatus.INTERRUPTED
import com.apercallc.aidailymemory.recording.RecordingStatus.PAUSED
import com.apercallc.aidailymemory.recording.RecordingStatus.RECORDING
import com.apercallc.aidailymemory.recording.RecordingStatus.RECOVERING
import com.apercallc.aidailymemory.recording.RecordingStatus.STARTING
import com.apercallc.aidailymemory.recording.RecordingStatus.STOPPING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RecordingStateMachineTest {
    private val sessionA = SessionId("session-a")
    private val sessionB = SessionId("session-b")

    private val start = RecordingCommand.Start(sessionB)
    private val startFailed = CaptureSignal.StartFailed(CaptureProblem.PERMISSION_DENIED)
    private val interrupted = CaptureSignal.Interrupted(CaptureProblem.MICROPHONE_UNAVAILABLE)

    private val allEvents: List<RecordingEvent> = listOf(
        start,
        RecordingCommand.Pause,
        RecordingCommand.Resume,
        RecordingCommand.Stop,
        RecordingCommand.Reset,
        CaptureSignal.Started,
        startFailed,
        interrupted,
        CaptureSignal.RecoveryStarted,
        CaptureSignal.Recovered,
        CaptureSignal.Stopped,
    )

    /** The complete legal transition table. Anything not listed must not change state. */
    private val legal: Map<Pair<RecordingStatus, RecordingEvent>, RecordingStatus> = mapOf(
        (IDLE to start) to STARTING,
        (COMPLETED to start) to STARTING,
        (STARTING to CaptureSignal.Started) to RECORDING,
        (STARTING to startFailed) to IDLE,
        (STARTING to RecordingCommand.Stop) to STOPPING,
        (RECORDING to RecordingCommand.Pause) to PAUSED,
        (RECORDING to interrupted) to INTERRUPTED,
        (RECORDING to RecordingCommand.Stop) to STOPPING,
        (PAUSED to RecordingCommand.Resume) to RECORDING,
        (PAUSED to RecordingCommand.Stop) to STOPPING,
        (INTERRUPTED to CaptureSignal.RecoveryStarted) to RECOVERING,
        (INTERRUPTED to RecordingCommand.Pause) to PAUSED,
        (INTERRUPTED to RecordingCommand.Stop) to STOPPING,
        (RECOVERING to CaptureSignal.Recovered) to RECORDING,
        (RECOVERING to interrupted) to INTERRUPTED,
        (RECOVERING to RecordingCommand.Pause) to PAUSED,
        (RECOVERING to RecordingCommand.Stop) to STOPPING,
        (STOPPING to CaptureSignal.Stopped) to COMPLETED,
        (COMPLETED to RecordingCommand.Reset) to IDLE,
    )

    /** Duplicate commands whose intent already holds: acknowledged, not errors. */
    private val idempotent: Set<Pair<RecordingStatus, RecordingEvent>> = setOf(
        STARTING to start,
        RECORDING to start,
        PAUSED to RecordingCommand.Pause,
        RECORDING to RecordingCommand.Resume,
        STOPPING to RecordingCommand.Stop,
        COMPLETED to RecordingCommand.Stop,
        IDLE to RecordingCommand.Reset,
    )

    private fun stateIn(status: RecordingStatus) = RecordingState(
        status = status,
        sessionId = if (status == IDLE) null else sessionA,
        revision = 7,
        problem = if (status == INTERRUPTED || status == RECOVERING) {
            CaptureProblem.PHONE_CALL
        } else {
            null
        },
    )

    @Test
    fun `every status and event pair resolves to exactly the specified outcome`() {
        RecordingStatus.entries.forEach { status ->
            allEvents.forEach { event ->
                val current = stateIn(status)
                val result = RecordingStateMachine.reduce(current, event)
                val key = status to event
                val expected = legal[key]

                when {
                    expected != null -> {
                        val applied = assertIs<Transition.Applied>(result, "$key")
                        assertEquals(expected, applied.state.status, "$key")
                        assertEquals(current, applied.previous, "$key")
                        assertEquals(current.revision + 1, applied.state.revision, "$key")
                    }
                    key in idempotent -> {
                        assertIs<Transition.Ignored>(result, "$key")
                        assertEquals(current, result.state, "$key")
                    }
                    else -> {
                        assertIs<Transition.Rejected>(result, "$key")
                        assertEquals(current, result.state, "$key")
                    }
                }
                assertEquals(event, result.event, "$key")
            }
        }
    }

    @Test
    fun `start assigns the caller-supplied session id and clears prior problems`() {
        val failed = RecordingState.Initial.copy(problem = CaptureProblem.STORAGE_LOW)

        val next = RecordingStateMachine.reduce(failed, start).state

        assertEquals(sessionB, next.sessionId)
        assertNull(next.problem)
    }

    @Test
    fun `start after completion replaces the finished session`() {
        val next = RecordingStateMachine.reduce(stateIn(COMPLETED), start).state

        assertEquals(STARTING, next.status)
        assertEquals(sessionB, next.sessionId)
    }

    @Test
    fun `start failure returns to idle, drops the session, and keeps the reason visible`() {
        val next = RecordingStateMachine.reduce(stateIn(STARTING), startFailed).state

        assertEquals(IDLE, next.status)
        assertNull(next.sessionId)
        assertEquals(CaptureProblem.PERMISSION_DENIED, next.problem)
    }

    @Test
    fun `interruption records its cause and recovery carries it until audio is flowing`() {
        val interruptedState = RecordingStateMachine.reduce(stateIn(RECORDING), interrupted).state
        assertEquals(CaptureProblem.MICROPHONE_UNAVAILABLE, interruptedState.problem)

        val recovering = RecordingStateMachine
            .reduce(interruptedState, CaptureSignal.RecoveryStarted).state
        assertEquals(CaptureProblem.MICROPHONE_UNAVAILABLE, recovering.problem)

        val recovered = RecordingStateMachine.reduce(recovering, CaptureSignal.Recovered).state
        assertEquals(RECORDING, recovered.status)
        assertNull(recovered.problem)
        assertEquals(sessionA, recovered.sessionId)
    }

    @Test
    fun `failed recovery re-enters interrupted with the new cause`() {
        val next = RecordingStateMachine.reduce(
            stateIn(RECOVERING),
            CaptureSignal.Interrupted(CaptureProblem.STORAGE_LOW),
        ).state

        assertEquals(INTERRUPTED, next.status)
        assertEquals(CaptureProblem.STORAGE_LOW, next.problem)
    }

    @Test
    fun `privacy pause during recovery wins and clears the interruption`() {
        val next = RecordingStateMachine.reduce(stateIn(RECOVERING), RecordingCommand.Pause).state

        assertEquals(PAUSED, next.status)
        assertNull(next.problem)
        assertEquals(sessionA, next.sessionId)
    }

    @Test
    fun `reset returns to the initial state but keeps the revision monotonic`() {
        val next = RecordingStateMachine.reduce(stateIn(COMPLETED), RecordingCommand.Reset).state

        assertEquals(RecordingState.Initial.copy(revision = 8), next)
    }

    @Test
    fun `revision increases by one per applied transition across a full session`() {
        val events = listOf(
            RecordingCommand.Start(sessionA),
            CaptureSignal.Started,
            RecordingCommand.Pause,
            RecordingCommand.Pause, // duplicate from a watch retry
            RecordingCommand.Resume,
            interrupted,
            CaptureSignal.RecoveryStarted,
            CaptureSignal.Recovered,
            RecordingCommand.Stop,
            CaptureSignal.Stopped,
        )

        val final = events.fold(RecordingState.Initial) { state, event ->
            RecordingStateMachine.reduce(state, event).state
        }

        assertEquals(COMPLETED, final.status)
        assertEquals(sessionA, final.sessionId)
        assertEquals(9, final.revision)
    }

    @Test
    fun `session id must not be blank`() {
        assertFailsWith<IllegalArgumentException> { SessionId(" ") }
    }
}
