package com.apercallc.aidailymemory.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.RecordingController
import com.apercallc.aidailymemory.capture.TransitionJournal
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.schedule.QuietWindow
import com.apercallc.aidailymemory.recording.time.CaptureTime
import com.apercallc.aidailymemory.settings.SettingsStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RhythmRunnerTest {
    private val zone = ZoneId.of("America/Chicago")
    private val session = SessionId("s")
    private lateinit var settings: SettingsStore
    private var now: Instant = at(9, 0)
    private val alarms = mutableListOf<Instant?>()
    private var reminders = 0

    // 2026-09-22 is a Tuesday.
    private fun at(hour: Int, minute: Int = 0, day: Int = 22): Instant =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant()

    private val rhythm = DailyRhythm(
        sleep = listOf(DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))),
        quiet = listOf(QuietWindow("lunch", "Lunch", DailyWindow(LocalTime.NOON, LocalTime.of(13, 0)))),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsStore(context.getSharedPreferences("rhythm-test", Context.MODE_PRIVATE))
        settings.lastRhythmCheckEpochMs = null
        settings.setRhythm(rhythm)
    }

    private fun TestScope.setUp(): Pair<RecordingController, RhythmRunner> {
        val controller = RecordingController(
            journal = object : TransitionJournal {
                override suspend fun latest(): Pair<JournalRecord, CaptureTime>? = null
                override suspend fun append(record: JournalRecord, time: CaptureTime) = Unit
            },
            now = { CaptureTime.of(now, zone, 0, "b") },
            currentBoot = { BootStamp("b", 0) },
            bootOf = { BootStamp(it.bootId, it.elapsedRealtimeMs) },
            elapsedRealtimeMs = { 0 },
            scope = backgroundScope,
            // Like production without a service: finishing a session reports Stopped.
            detached = { effect, _ ->
                if (effect is CaptureEffect.FinishSession) {
                    listOf(CaptureSignal.Stopped(effect.sessionId))
                } else {
                    emptyList()
                }
            },
            beforeRecovery = {},
        )
        controller.restore()
        runCurrent()
        val runner = RhythmRunner(
            settings = settings,
            controller = controller,
            alarms = { alarms += it },
            onWakeReminder = { reminders++ },
            now = { now },
            zone = { zone },
        )
        return controller to runner
    }

    private suspend fun RecordingController.recording() {
        dispatch(RecordingCommand.Start(session))
        report(EngineReport.AudioFlowing(session))
    }

    @Test
    fun `quiet time pauses on schedule and resumes afterwards`() = runTest {
        val (controller, runner) = setUp()
        controller.recording()
        runner.catchUp() // first check: establishes "now", applies nothing

        now = at(12, 0)
        runner.catchUp()
        assertEquals(RecordingStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.pausedBySchedule)
        assertEquals(at(13, 0), alarms.last())

        now = at(13, 5) // inexact alarm, a little late
        runner.catchUp()
        assertEquals(RecordingStatus.RESUMING, controller.state.value.status)
    }

    @Test
    fun `a pause the user made is never resumed by the schedule`() = runTest {
        val (controller, runner) = setUp()
        controller.recording()
        runner.catchUp()
        now = at(12, 0)
        runner.catchUp()

        controller.dispatch(RecordingCommand.Pause) // user pauses during quiet time
        now = at(13, 1)
        runner.catchUp()

        assertEquals(RecordingStatus.PAUSED, controller.state.value.status)
    }

    @Test
    fun `bedtime ends the day, and the morning brings a reminder, not a recording`() = runTest {
        val (controller, runner) = setUp()
        controller.recording()
        runner.catchUp()

        now = at(23, 0)
        runner.catchUp()
        assertEquals(RecordingStatus.COMPLETED, controller.state.value.status)

        now = at(7, 2, day = 23)
        runner.catchUp()
        assertEquals(1, reminders)
        assertEquals(RecordingStatus.COMPLETED, controller.state.value.status)
    }

    @Test
    fun `no reminder hours late or while already recording`() = runTest {
        val (controller, runner) = setUp()
        now = at(22, 0)
        runner.catchUp()
        now = at(11, 0, day = 23) // phone was off all morning
        runner.catchUp()
        assertEquals(0, reminders)

        // Next night: bedtime ends the day, then the user starts early, before wake-up time.
        now = at(6, 30, day = 24)
        runner.catchUp()
        controller.recording()
        now = at(7, 1, day = 24)
        runner.catchUp()
        assertEquals(0, reminders)
        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)
    }

    @Test
    fun `a quiet window missed entirely while asleep needs no action`() = runTest {
        val (controller, runner) = setUp()
        controller.recording()
        runner.catchUp()

        now = at(14, 0) // 12:00–13:00 happened while nobody checked
        runner.catchUp()

        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)
    }

    @Test
    fun `the clock moving backwards never replays the schedule`() = runTest {
        val (controller, runner) = setUp()
        controller.recording()
        now = at(14, 0)
        runner.catchUp()

        now = at(11, 0) // manual clock change
        runner.catchUp()
        now = at(11, 30)
        runner.catchUp()

        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)
    }

    @Test
    fun `changing the schedule honors a quiet window that is on right now`() = runTest {
        val (controller, runner) = setUp()
        settings.setRhythm(DailyRhythm.None)
        controller.recording()
        now = at(12, 30)
        runner.catchUp()
        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)

        settings.setRhythm(rhythm)
        runner.onRhythmChanged()

        assertEquals(RecordingStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.pausedBySchedule)
    }

    @Test
    fun `no schedule means no alarm and nothing to wait for`() = runTest {
        val (_, runner) = setUp()
        settings.setRhythm(DailyRhythm.None)

        runner.catchUp()

        assertNull(alarms.last())
        assertNull(runner.msUntilNextAction())
    }
}
