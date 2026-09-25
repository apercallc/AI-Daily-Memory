package com.apercallc.aidailymemory.recording

import com.apercallc.aidailymemory.recording.RecordingStatus.COMPLETED
import com.apercallc.aidailymemory.recording.RecordingStatus.IDLE
import com.apercallc.aidailymemory.recording.RecordingStatus.INTERRUPTED
import com.apercallc.aidailymemory.recording.RecordingStatus.PAUSED
import com.apercallc.aidailymemory.recording.RecordingStatus.RECORDING
import com.apercallc.aidailymemory.recording.RecordingStatus.RECOVERING
import com.apercallc.aidailymemory.recording.RecordingStatus.RESUMING
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

    /** Start for the session the test states are in (a retry). */
    private val startSame = RecordingCommand.Start(sessionA)

    /** Start for a different session. */
    private val start = RecordingCommand.Start(sessionB)

    // Signals for the session the test states are in.
    private val started = CaptureSignal.Started(sessionA)
    private val startFailed = CaptureSignal.StartFailed(sessionA, CaptureProblem.PERMISSION_DENIED)
    private val interrupted =
        CaptureSignal.Interrupted(sessionA, CaptureProblem.MICROPHONE_UNAVAILABLE)
    private val recoveryStarted = CaptureSignal.RecoveryStarted(sessionA)
    private val recovered = CaptureSignal.Recovered(sessionA)
    private val stopped = CaptureSignal.Stopped(sessionA)

    private val allEvents: List<RecordingEvent> = listOf(
        startSame,
        start,
        RecordingCommand.Pause,
        RecordingCommand.Resume,
        RecordingCommand.Stop,
        RecordingCommand.Reset,
        started,
        startFailed,
        interrupted,
        recoveryStarted,
        recovered,
        stopped,
    )

    /** Transitions allowed to change the session id. */
    private val sessionChanging = setOf(startSame, start, startFailed, RecordingCommand.Reset)

    /** The complete legal transition table. Anything not listed must not change state. */
    private val legal: Map<Pair<RecordingStatus, RecordingEvent>, RecordingStatus> = mapOf(
        (IDLE to startSame) to STARTING,
        (IDLE to start) to STARTING,
        (COMPLETED to startSame) to STARTING,
        (COMPLETED to start) to STARTING,
        (STARTING to started) to RECORDING,
        (STARTING to startFailed) to IDLE,
        (STARTING to RecordingCommand.Stop) to STOPPING,
        (RECORDING to RecordingCommand.Pause) to PAUSED,
        (RECORDING to interrupted) to INTERRUPTED,
        (RECORDING to RecordingCommand.Stop) to STOPPING,
        (PAUSED to RecordingCommand.Resume) to RESUMING,
        (RESUMING to started) to RECORDING,
        (RESUMING to interrupted) to INTERRUPTED,
        (RESUMING to RecordingCommand.Pause) to PAUSED,
        (RESUMING to RecordingCommand.Stop) to STOPPING,
        (PAUSED to RecordingCommand.Stop) to STOPPING,
        (INTERRUPTED to recoveryStarted) to RECOVERING,
        (INTERRUPTED to RecordingCommand.Pause) to PAUSED,
        (INTERRUPTED to RecordingCommand.Stop) to STOPPING,
        (RECOVERING to recovered) to RECORDING,
        (RECOVERING to interrupted) to INTERRUPTED,
        (RECOVERING to RecordingCommand.Pause) to PAUSED,
        (RECOVERING to RecordingCommand.Stop) to STOPPING,
        (STOPPING to stopped) to COMPLETED,
        (COMPLETED to RecordingCommand.Reset) to IDLE,
    )

    /** Duplicate commands whose intent already holds: acknowledged, not errors. */
    private val idempotent: Set<Pair<RecordingStatus, RecordingEvent>> = setOf(
        STARTING to startSame,
        RECORDING to startSame,
        PAUSED to RecordingCommand.Pause,
        RECORDING to RecordingCommand.Resume,
        RESUMING to RecordingCommand.Resume,
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
                        if (event !in sessionChanging) {
                            assertEquals(current.sessionId, applied.state.sessionId, "$key")
                        }
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
    fun `signals from another session are rejected in every state`() {
        val otherSession = SessionId("stale-session")
        val foreignSignals = listOf(
            CaptureSignal.Started(otherSession),
            CaptureSignal.StartFailed(otherSession, CaptureProblem.UNKNOWN),
            CaptureSignal.Interrupted(otherSession, CaptureProblem.UNKNOWN),
            CaptureSignal.RecoveryStarted(otherSession),
            CaptureSignal.Recovered(otherSession),
            CaptureSignal.Stopped(otherSession),
        )

        RecordingStatus.entries.forEach { status ->
            foreignSignals.forEach { signal ->
                val current = stateIn(status)
                val result = RecordingStateMachine.reduce(current, signal)
                assertIs<Transition.Rejected>(result, "$status + $signal")
                assertEquals(current, result.state)
            }
        }
    }

    @Test
    fun `start for a different session while one is active is rejected, not ignored`() {
        listOf(STARTING, RECORDING).forEach { status ->
            assertIs<Transition.Rejected>(RecordingStateMachine.reduce(stateIn(status), start))
            assertIs<Transition.Ignored>(RecordingStateMachine.reduce(stateIn(status), startSame))
        }
    }

    @Test
    fun `stopping an interrupted session keeps the cause through completion`() {
        val stopping =
            RecordingStateMachine.reduce(stateIn(INTERRUPTED), RecordingCommand.Stop).state
        val completed = RecordingStateMachine.reduce(stopping, stopped).state

        assertEquals(CaptureProblem.PHONE_CALL, stopping.problem)
        assertEquals(COMPLETED, completed.status)
        assertEquals(CaptureProblem.PHONE_CALL, completed.problem)
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
            .reduce(interruptedState, recoveryStarted).state
        assertEquals(CaptureProblem.MICROPHONE_UNAVAILABLE, recovering.problem)

        val resumed = RecordingStateMachine.reduce(recovering, recovered).state
        assertEquals(RECORDING, resumed.status)
        assertNull(resumed.problem)
        assertEquals(sessionA, resumed.sessionId)
    }

    @Test
    fun `resume never claims recording until audio is confirmed flowing`() {
        val resuming = RecordingStateMachine.reduce(stateIn(PAUSED), RecordingCommand.Resume).state
        assertEquals(RESUMING, resuming.status)

        // After a restart the service may be gone: the engine reports it, the session survives.
        val failed = RecordingStateMachine.reduce(
            resuming,
            CaptureSignal.Interrupted(sessionA, CaptureProblem.FOREGROUND_START_NOT_ALLOWED),
        ).state
        assertEquals(INTERRUPTED, failed.status)
        assertEquals(sessionA, failed.sessionId)
        assertEquals(CaptureProblem.FOREGROUND_START_NOT_ALLOWED, failed.problem)
    }

    @Test
    fun `failed recovery re-enters interrupted with the new cause`() {
        val next = RecordingStateMachine.reduce(
            stateIn(RECOVERING),
            CaptureSignal.Interrupted(sessionA, CaptureProblem.STORAGE_LOW),
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
            startSame,
            started,
            RecordingCommand.Pause,
            RecordingCommand.Pause, // duplicate from a watch retry
            RecordingCommand.Resume,
            started, // audio flowing again after resume
            interrupted,
            recoveryStarted,
            recovered,
            RecordingCommand.Stop,
            stopped,
        )

        val final = events.fold(RecordingState.Initial) { state, event ->
            RecordingStateMachine.reduce(state, event).state
        }

        assertEquals(COMPLETED, final.status)
        assertEquals(sessionA, final.sessionId)
        assertEquals(10, final.revision)
    }

    @Test
    fun `state rejects a session id that contradicts its status`() {
        assertFailsWith<IllegalArgumentException> {
            RecordingState(IDLE, sessionA, revision = 0, problem = null)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordingState(RECORDING, sessionId = null, revision = 0, problem = null)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordingState(IDLE, sessionId = null, revision = -1, problem = null)
        }
    }

    @Test
    fun `session id must not be blank`() {
        assertFailsWith<IllegalArgumentException> { SessionId(" ") }
    }
}
