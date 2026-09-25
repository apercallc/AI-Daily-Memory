package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.recovery.InterruptionRecoveryPolicy
import com.apercallc.aidailymemory.recording.recovery.RecoveryCondition
import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RecoverySchedulerTest {
    private val session = SessionId("s")
    private val policy = InterruptionRecoveryPolicy(
        initialDelayMs = 1_000,
        maxDelayMs = 4_000,
        maxAttempts = 2,
    )

    private class Journal : TransitionJournal {
        override suspend fun latest(): Pair<JournalRecord, CaptureTime>? = null
        override suspend fun append(record: JournalRecord, time: CaptureTime) = Unit
    }

    private fun TestScope.setUp(
        conditionHolds: (RecoveryCondition) -> Boolean = { true },
        needsUser: MutableList<RecordingState> = mutableListOf(),
    ): RecordingController {
        val controller = RecordingController(
            journal = Journal(),
            now = { CaptureTime.of(Instant.EPOCH, ZoneOffset.UTC, 0, "b") },
            currentBoot = { BootStamp("b", 0) },
            bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
            elapsedRealtimeMs = { 0 },
            scope = backgroundScope,
            // The fake "engine" fails every reopen, so each retry ends interrupted again.
            detached = { _, _ -> emptyList() },
            beforeRecovery = {},
        )
        controller.restore()
        RecoveryScheduler(controller, policy, conditionHolds, { needsUser += it }, pollMs = 500)
            .start(backgroundScope)
        runCurrent()
        return controller
    }

    private suspend fun RecordingController.interrupt(problem: CaptureProblem) {
        if (state.value.status == RecordingStatus.IDLE) {
            dispatch(RecordingCommand.Start(session))
            report(EngineReport.AudioFlowing(session))
        }
        report(EngineReport.Failed(session, problem))
    }

    @Test
    fun `transient loss retries with backoff, then hands the problem to the user`() = runTest {
        val needsUser = mutableListOf<RecordingState>()
        val controller = setUp(needsUser = needsUser)
        controller.interrupt(CaptureProblem.MICROPHONE_UNAVAILABLE)
        runCurrent()

        advanceTimeBy(999)
        assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
        advanceTimeBy(2)
        assertEquals(RecordingStatus.RECOVERING, controller.state.value.status)

        // Reopen fails again: the second retry waits twice as long.
        controller.report(EngineReport.Failed(session, CaptureProblem.MICROPHONE_UNAVAILABLE))
        runCurrent()
        advanceTimeBy(1_999)
        assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
        advanceTimeBy(2)
        assertEquals(RecordingStatus.RECOVERING, controller.state.value.status)

        // Attempts exhausted: no more automation, the user is told.
        controller.report(EngineReport.Failed(session, CaptureProblem.MICROPHONE_UNAVAILABLE))
        advanceTimeBy(60_000)
        assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
        assertEquals(1, needsUser.size)
    }

    @Test
    fun `a privacy pause cancels a pending automatic retry`() = runTest {
        val controller = setUp()
        controller.interrupt(CaptureProblem.MICROPHONE_UNAVAILABLE)
        runCurrent()

        controller.dispatch(RecordingCommand.Pause)
        advanceTimeBy(10_000)

        assertEquals(RecordingStatus.PAUSED, controller.state.value.status)
    }

    @Test
    fun `a phone call waits for the call to end instead of retrying`() = runTest {
        var inCall = true
        val controller = setUp(conditionHolds = { it == RecoveryCondition.CALL_ENDED && !inCall })
        controller.interrupt(CaptureProblem.PHONE_CALL)
        advanceTimeBy(30_000)
        assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)

        inCall = false
        advanceTimeBy(501)

        assertEquals(RecordingStatus.RECOVERING, controller.state.value.status)
        assertEquals(CaptureSignal.RecoveryStarted(session).sessionId, controller.state.value.sessionId)
    }

    @Test
    fun `an interruption restored from a previous process is never retried automatically`() =
        runTest {
            // The process died during a call; by now the call has ended. Retrying would reopen
            // the mic without the user: an automatic restart, which ADR-0010 forbids.
            val restored = JournalRecord(7, "INTERRUPTED", session.value, "PHONE_CALL", "x")
            val controller = RecordingController(
                journal = object : TransitionJournal {
                    override suspend fun latest() =
                        restored to CaptureTime.of(Instant.EPOCH, ZoneOffset.UTC, 0, "b")
                    override suspend fun append(record: JournalRecord, time: CaptureTime) = Unit
                },
                now = { CaptureTime.of(Instant.EPOCH, ZoneOffset.UTC, 5, "b") },
                currentBoot = { BootStamp("b", 5) },
                bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
                elapsedRealtimeMs = { 5 },
                scope = backgroundScope,
                detached = { _, _ -> emptyList() },
                beforeRecovery = {},
            )
            controller.restore()
            RecoveryScheduler(controller, policy, { true }, {}, pollMs = 500)
                .start(backgroundScope)

            advanceTimeBy(60_000)

            assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
            assertEquals(CaptureProblem.PHONE_CALL, controller.state.value.problem)
        }

    @Test
    fun `restart interruptions are never auto-recovered`() = runTest {
        val needsUser = mutableListOf<RecordingState>()
        val controller = setUp(needsUser = needsUser)
        controller.interrupt(CaptureProblem.DEVICE_REBOOTED)
        advanceTimeBy(60_000)

        assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
        assertEquals(1, needsUser.size)
    }
}
