package com.apercallc.aidailymemory.recording.effects

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureEffectPlannerTest {
    private val session = SessionId("s1")

    private fun run(vararg events: RecordingEvent): List<List<CaptureEffect>> {
        var state = RecordingState.Initial
        return events.map { event ->
            val transition = RecordingStateMachine.reduce(state, event)
            state = transition.state
            CaptureEffectPlanner.effectsFor(transition)
        }
    }

    @Test
    fun `a full session opens, pauses, resumes, recovers, and finishes in order`() {
        val effects = run(
            RecordingCommand.Start(session),
            CaptureSignal.Started(session),
            RecordingCommand.Pause,
            RecordingCommand.Resume,
            CaptureSignal.Started(session),
            CaptureSignal.Interrupted(session, CaptureProblem.PHONE_CALL),
            CaptureSignal.RecoveryStarted(session),
            CaptureSignal.Recovered(session),
            RecordingCommand.Stop,
            CaptureSignal.Stopped(session),
            RecordingCommand.Reset,
        )

        val open = listOf(
            CaptureEffect.EnsureForeground,
            CaptureEffect.OpenMicrophone(session, recovery = false),
        )
        val close = listOf(CaptureEffect.EnsureForeground, CaptureEffect.CloseMicrophone(session))
        assertEquals(
            listOf(
                open,
                emptyList(),
                close,
                open,
                emptyList(),
                close,
                listOf(
                    CaptureEffect.EnsureForeground,
                    CaptureEffect.OpenMicrophone(session, recovery = true),
                ),
                emptyList(),
                listOf(CaptureEffect.FinishSession(session)),
                listOf(CaptureEffect.StopForeground),
                listOf(CaptureEffect.StopForeground),
            ),
            effects,
        )
    }

    @Test
    fun `a failed start releases the service`() {
        val effects = run(
            RecordingCommand.Start(session),
            CaptureSignal.StartFailed(session, CaptureProblem.PERMISSION_DENIED),
        )

        assertEquals(listOf(CaptureEffect.StopForeground), effects.last())
    }

    @Test
    fun `ignored and rejected transitions have no side effects`() {
        val effects = run(
            RecordingCommand.Pause, // rejected while idle
            RecordingCommand.Reset, // ignored while idle
            CaptureSignal.Started(session), // rejected: no such session
        )

        assertTrue(effects.all { it.isEmpty() })
    }

    @Test
    fun `only entering RECORDING is effect-free, including for restored states`() {
        RecordingStatus.entries.forEach { status ->
            // Exercise planning for arbitrary applied transitions, including restored states.
            val state = RecordingState(
                status = status,
                sessionId = if (status == RecordingStatus.IDLE) null else session,
                revision = 1,
                problem = null,
            )
            val transition = Transition.Applied(
                previous = RecordingState.Initial,
                state = state,
                event = RecordingCommand.Stop,
            )
            val effects = CaptureEffectPlanner.effectsFor(transition)
            assertEquals(status == RecordingStatus.RECORDING, effects.isEmpty(), "$status")
        }
    }
}
