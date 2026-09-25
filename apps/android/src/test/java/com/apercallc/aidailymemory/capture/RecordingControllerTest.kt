package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Robolectric only for android.util.Log; everything else is plain JVM. */
@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {
    private val session = SessionId("session-1")

    /** Shared, ordered record of journal writes and effects. */
    private val log = mutableListOf<String>()

    private inner class FakeJournal(var stored: Pair<JournalRecord, CaptureTime>? = null) :
        TransitionJournal {
        val records = mutableListOf<JournalRecord>()
        override suspend fun latest() = stored
        override suspend fun append(record: JournalRecord, time: CaptureTime) {
            log += "journal:${record.status}"
            records += record
            stored = record to time
        }
    }

    private fun time(elapsedMs: Long, bootId: String = "boot-1") =
        CaptureTime.of(Instant.EPOCH, ZoneOffset.UTC, elapsedMs, bootId)

    private fun recordingHandler(
        name: String,
        respond: (CaptureEffect, RecordingState) -> List<RecordingEvent> = { _, _ -> emptyList() },
    ) = EffectHandler { effect, state ->
        log += "$name:${effect::class.simpleName}"
        respond(effect, state)
    }

    private fun TestScope.controller(
        journal: FakeJournal,
        detached: EffectHandler = recordingHandler("detached"),
        nowElapsed: Long = 50_000,
        bootId: String = "boot-1",
        scope: CoroutineScope = backgroundScope,
    ) = RecordingController(
        journal = journal,
        now = { time(nowElapsed, bootId) },
        currentBoot = { BootStamp(bootId, nowElapsed) },
        bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
        elapsedRealtimeMs = { nowElapsed },
        scope = scope,
        detached = detached,
        beforeRecovery = { log += "beforeRecovery" },
    ).also {
        it.restore()
        runCurrent()
    }

    @Test
    fun `transitions are journaled before any effect runs`() = runTest {
        val controller = controller(FakeJournal())
        controller.attach(recordingHandler("service"))

        controller.dispatch(RecordingCommand.Start(session))

        assertEquals(
            listOf(
                "beforeRecovery",
                "journal:STARTING",
                "service:EnsureForeground",
                "service:OpenMicrophone",
            ),
            log,
        )
        assertEquals(RecordingStatus.STARTING, controller.state.value.status)
    }

    @Test
    fun `a failed foreground start never opens the microphone`() = runTest {
        val journal = FakeJournal()
        val controller = controller(journal)
        controller.attach(
            recordingHandler("service") { effect, state ->
                val starting = state.status == RecordingStatus.STARTING
                if (effect == CaptureEffect.EnsureForeground && starting) {
                    val problem = CaptureProblem.FOREGROUND_START_NOT_ALLOWED
                    listOf(CaptureSignal.StartFailed(session, problem))
                } else {
                    emptyList()
                }
            },
        )

        controller.dispatch(RecordingCommand.Start(session))

        assertFalse("service:OpenMicrophone" in log)
        assertEquals(RecordingStatus.IDLE, controller.state.value.status)
        assertEquals(CaptureProblem.FOREGROUND_START_NOT_ALLOWED, controller.state.value.problem)
        assertEquals(listOf("STARTING", "IDLE"), journal.records.map { it.status })
        assertEquals(listOf(1L, 2L), journal.records.map { it.revision })
    }

    @Test
    fun `finishing a session follows up with Stopped and releases the service`() = runTest {
        val controller = controller(FakeJournal())
        controller.attach(
            recordingHandler("service") { effect, _ ->
                if (effect is CaptureEffect.FinishSession) {
                    listOf(CaptureSignal.Stopped(effect.sessionId))
                } else {
                    emptyList()
                }
            },
        )

        controller.dispatch(RecordingCommand.Start(session))
        controller.report(EngineReport.AudioFlowing(session))
        controller.dispatch(RecordingCommand.Stop)

        assertEquals(RecordingStatus.COMPLETED, controller.state.value.status)
        assertEquals("service:StopForeground", log.last())
    }

    @Test
    fun `engine reports map to the right signal for the current state`() = runTest {
        val controller = controller(FakeJournal())
        controller.attach(recordingHandler("service"))
        controller.dispatch(RecordingCommand.Start(session))
        controller.report(EngineReport.AudioFlowing(session))
        controller.report(EngineReport.Failed(session, CaptureProblem.MICROPHONE_UNAVAILABLE))
        controller.dispatch(CaptureSignal.RecoveryStarted(session))

        val recovered = controller.report(EngineReport.AudioFlowing(session))

        assertIs<Transition.Applied>(recovered)
        assertEquals(CaptureSignal.Recovered(session), recovered.event)
        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)
    }

    @Test
    fun `rejected events are surfaced and never journaled`() = runTest {
        val journal = FakeJournal()
        val controller = controller(journal)
        val rejection = async { controller.rejections.first() }
        runCurrent()

        val result = controller.dispatch(RecordingCommand.Pause)

        assertIs<Transition.Rejected>(result)
        assertTrue(journal.records.isEmpty())
        assertEquals(RecordingCommand.Pause, rejection.await().event)
    }

    @Test
    fun `a process restart mid-recording becomes a visible interruption without the mic`() =
        runTest {
            val persisted = JournalRecord(4, "RECORDING", session.value, null, "signal:Started")
            val journal = FakeJournal(persisted to time(elapsedMs = 10_000))

            val controller = controller(journal, nowElapsed = 50_000)
            val report = controller.awaitRestored()

            assertEquals(
                listOf(CaptureSignal.Interrupted(session, CaptureProblem.PROCESS_RESTARTED)),
                report.recoveryEvents,
            )
            assertEquals(RecordingStatus.INTERRUPTED, controller.state.value.status)
            assertEquals(5, controller.state.value.revision)
            assertEquals(
                listOf(
                    "beforeRecovery",
                    "journal:INTERRUPTED",
                    "detached:EnsureForeground",
                    "detached:CloseMicrophone",
                ),
                log,
            )
        }

    @Test
    fun `a reboot is classified from the boot id`() = runTest {
        val persisted = JournalRecord(4, "RESUMING", session.value, null, "command:Resume")
        val journal = FakeJournal(persisted to time(elapsedMs = 10_000, bootId = "boot-1"))

        val controller = controller(journal, nowElapsed = 90_000, bootId = "boot-2")
        controller.awaitRestored()

        assertEquals(CaptureProblem.DEVICE_REBOOTED, controller.state.value.problem)
    }

    @Test
    fun `a stop that was in progress completes on restart`() = runTest {
        val persisted = JournalRecord(9, "STOPPING", session.value, null, "command:Stop")
        val journal = FakeJournal(persisted to time(elapsedMs = 10_000))
        val detached = recordingHandler("detached") { effect, _ ->
            if (effect is CaptureEffect.FinishSession) {
                listOf(CaptureSignal.Stopped(effect.sessionId))
            } else {
                emptyList()
            }
        }

        val controller = controller(journal, detached = detached)
        controller.awaitRestored()

        assertEquals(RecordingStatus.COMPLETED, controller.state.value.status)
        assertEquals("detached:StopForeground", log.last())
    }

    @Test
    fun `a corrupt journal restarts idle with a visible problem and monotonic revisions`() =
        runTest {
            val persisted = JournalRecord(12, "GREMLIN", session.value, null, "command:Start")
            val journal = FakeJournal(persisted to time(elapsedMs = 10_000))
            val controller = controller(journal)
            controller.attach(recordingHandler("service"))

            assertEquals(RecordingStatus.IDLE, controller.state.value.status)
            assertEquals(CaptureProblem.UNKNOWN, controller.state.value.problem)

            controller.dispatch(RecordingCommand.Start(session))
            assertEquals(13, journal.records.single().revision)
        }

    @Test
    fun `a cancelled caller cannot split the journal from the published state`() = runTest {
        val journal = FakeJournal()
        val controller = controller(journal)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        controller.attach(
            recordingHandler("service") { _, _ -> emptyList() }.let { inner ->
                EffectHandler { effect, state ->
                    gate.await() // an effect that takes a while (e.g. sealing on close)
                    inner.execute(effect, state)
                }
            },
        )

        val caller = launch { controller.dispatch(RecordingCommand.Start(session)) }
        runCurrent()
        caller.cancel() // e.g. the service was destroyed mid-command
        gate.complete(Unit)
        runCurrent()

        assertEquals(RecordingStatus.STARTING, controller.state.value.status)
        assertEquals(listOf("STARTING"), journal.records.map { it.status })
        assertTrue("service:OpenMicrophone" in log, "planned effects still ran")
    }

    @Test
    fun `commands wait for the journal to be restored`() = runTest {
        val persisted = JournalRecord(4, "PAUSED", session.value, null, "command:Pause")
        val journal = FakeJournal(persisted to time(elapsedMs = 10_000))
        val controller = RecordingController(
            journal = journal,
            now = { time(20_000) },
            currentBoot = { BootStamp("boot-1", 20_000) },
            bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
            elapsedRealtimeMs = { 20_000 },
            scope = backgroundScope,
            detached = recordingHandler("detached"),
            beforeRecovery = {},
        )
        val resume = async { controller.dispatch(RecordingCommand.Resume) }
        runCurrent()
        assertFalse(resume.isCompleted)

        controller.restore()

        // Applied against the restored PAUSED state, not the initial IDLE one.
        assertIs<Transition.Applied>(resume.await())
        assertEquals(RecordingStatus.RESUMING, controller.state.value.status)
    }
}
