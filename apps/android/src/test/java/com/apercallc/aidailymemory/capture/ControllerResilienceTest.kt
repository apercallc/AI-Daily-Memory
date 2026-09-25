package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Failure paths of [RecordingController] found by the codebase scan (regression tests). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ControllerResilienceTest {
    private val session = SessionId("s")
    private val time = CaptureTime.of(Instant.EPOCH, ZoneOffset.UTC, 0, "b")

    private class Journal(
        private val latest: () -> Pair<JournalRecord, CaptureTime>?,
    ) : TransitionJournal {
        override suspend fun latest() = latest.invoke()
        override suspend fun append(record: JournalRecord, time: CaptureTime) = Unit
    }

    private fun TestScope.controller(
        journal: TransitionJournal,
        beforeRecovery: suspend () -> Unit = {},
    ) = RecordingController(
        journal = journal,
        now = { time },
        currentBoot = { BootStamp("b", 0) },
        bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
        elapsedRealtimeMs = { 0 },
        scope = backgroundScope,
        detached = { _, _ -> emptyList() },
        beforeRecovery = beforeRecovery,
    ).also {
        it.restore()
        runCurrent()
    }

    @Test
    fun `an unreadable journal still finishes restoring, visibly, instead of hanging`() = runTest {
        val controller = controller(Journal { throw IOException("disk") })

        val report = controller.awaitRestored()

        assertEquals(CaptureProblem.UNKNOWN, controller.state.value.problem)
        assertEquals(RecordingStatus.IDLE, report.restored.state.status)
        // Commands no longer wait forever.
        assertIs<Transition.Rejected>(controller.dispatch(RecordingCommand.Pause))
    }

    @Test
    fun `a failed cleanup keeps the restored state and its revision`() = runTest {
        val record = JournalRecord(41, "PAUSED", session.value, null, "command:Pause")
        val controller = controller(
            journal = Journal { record to time },
            beforeRecovery = { throw IOException("segment directory") },
        )

        controller.awaitRestored()

        assertEquals(RecordingStatus.PAUSED, controller.state.value.status)
        assertEquals(41, controller.state.value.revision)
    }

    @Test
    fun `only a person's rejected command is surfaced, never a late engine signal`() = runTest {
        val controller = controller(Journal { null })
        val surfaced = mutableListOf<Transition.Rejected>()
        backgroundScope.launch { controller.rejections.collect { surfaced += it } }
        runCurrent()

        controller.dispatch(CaptureSignal.Started(session))
        runCurrent()
        assertEquals(0, surfaced.size)

        controller.dispatch(RecordingCommand.Resume)
        runCurrent()
        assertEquals(1, surfaced.size)
    }
}
