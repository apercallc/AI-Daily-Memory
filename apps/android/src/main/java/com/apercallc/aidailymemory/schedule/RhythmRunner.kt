package com.apercallc.aidailymemory.schedule

import android.util.Log
import com.apercallc.aidailymemory.capture.RecordingController
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.OffState
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.schedule.RhythmPlanner
import com.apercallc.aidailymemory.settings.SettingsStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wakes the app at the next schedule change. Null cancels. */
fun interface RhythmAlarms {
    fun schedule(at: Instant?)
}

/**
 * Applies the user's daily rhythm to recording (ADR-0012).
 *
 * Timing has two sources, and both only ever call [catchUp]:
 * - while the recorder service runs, an in-process timer (the CPU is awake while audio is
 *   being captured, so pausing at quiet time or bedtime is on time);
 * - otherwise an inexact alarm, so a paused or idle phone isn't kept awake. A late resume only
 *   means recording starts a little later, never that it records during quiet time.
 *
 * [catchUp] is idempotent and edge-triggered: it applies only the last change since the previous
 * check, so a quiet window that started and ended while the CPU slept needs no action.
 */
class RhythmRunner(
    private val settings: SettingsStore,
    private val controller: RecordingController,
    private val alarms: RhythmAlarms,
    private val onWakeReminder: () -> Unit,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()

    suspend fun catchUp() = lock.withLock {
        controller.awaitRestored()
        val rhythm = settings.rhythm.value
        val current = now()
        // First run, or the clock moved backwards: start from now, never replay history.
        val last = settings.lastRhythmCheckEpochMs
            ?.let(Instant::ofEpochMilli)
            ?.takeIf { !it.isAfter(current) }
            ?: current
        val missed = RhythmPlanner.transitionsBetween(rhythm, last, current, zone())
            .mapNotNull { t -> t.actionFor(rhythm)?.let { t.at to it } }
            .lastOrNull()
        settings.lastRhythmCheckEpochMs = current.toEpochMilli()
        missed?.let { (at, action) -> apply(action, lateBy = Duration.between(at, current)) }
        reschedule(rhythm, current)
    }

    /**
     * The user just changed the schedule. If it says "quiet" right now, honor it now rather
     * than at the next boundary; otherwise start watching from this moment.
     */
    suspend fun onRhythmChanged() = lock.withLock {
        controller.awaitRestored()
        val rhythm = settings.rhythm.value
        val current = now()
        settings.lastRhythmCheckEpochMs = current.toEpochMilli()
        if (RhythmPlanner.stateAt(rhythm, current, zone()) is OffState.Quiet) {
            apply(RhythmAction.PAUSE, lateBy = Duration.ZERO)
        }
        reschedule(rhythm, current)
    }

    /** Milliseconds until the next schedule change that needs an action, or null if none. */
    fun msUntilNextAction(): Long? {
        val current = now()
        return RhythmPlanner.nextAction(settings.rhythm.value, current, zone())
            ?.let { (transition, _) -> Duration.between(current, transition.at).toMillis() }
    }

    private suspend fun apply(action: RhythmAction, lateBy: Duration) {
        Log.i(TAG, "rhythm_action action=$action")
        when (action) {
            RhythmAction.PAUSE -> controller.dispatch(RecordingCommand.ScheduledPause)
            RhythmAction.RESUME -> controller.dispatch(RecordingCommand.ScheduledResume)
            RhythmAction.END_DAY -> controller.dispatch(RecordingCommand.ScheduledStop)
            RhythmAction.WAKE_REMINDER -> {
                val status = controller.state.value.status
                val idle = status == RecordingStatus.IDLE || status == RecordingStatus.COMPLETED
                // A "good morning" hours late (phone off overnight) or while already
                // recording is noise, not help.
                if (idle && lateBy <= REMINDER_GRACE) onWakeReminder()
            }
        }
    }

    private fun reschedule(rhythm: DailyRhythm, from: Instant) {
        alarms.schedule(RhythmPlanner.nextAction(rhythm, from, zone())?.first?.at)
    }

    private companion object {
        const val TAG = "RhythmRunner"
        val REMINDER_GRACE: Duration = Duration.ofHours(2)
    }
}
