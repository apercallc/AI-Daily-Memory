package com.apercallc.aidailymemory.ui.settings

import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import java.time.DayOfWeek
import java.time.LocalTime

/** One bedtime and wake-up pair. */
data class Night(val bedtime: LocalTime, val wake: LocalTime)

/**
 * How people describe a sleep schedule: the same every night, or different on weekend nights
 * (Friday and Saturday). Converts to and from the schedule's [DailyWindow]s, which are keyed by
 * start day rather than by "night of".
 */
data class SleepPlan(val weeknights: Night, val weekendNights: Night?) {
    fun toWindows(): List<DailyWindow> = if (weekendNights == null) {
        listOf(MemoryDays.sleepWindow(weeknights.bedtime, weeknights.wake, DailyWindow.EVERY_DAY))
    } else {
        listOf(
            MemoryDays.sleepWindow(weeknights.bedtime, weeknights.wake, DailyWindow.WEEKNIGHTS),
            MemoryDays.sleepWindow(
                weekendNights.bedtime,
                weekendNights.wake,
                DailyWindow.WEEKEND_NIGHTS,
            ),
        )
    }

    companion object {
        val Default = SleepPlan(Night(LocalTime.of(23, 0), LocalTime.of(7, 0)), weekendNights = null)

        /**
         * Reads windows this app wrote. Anything else (hand-edited, a future format) returns
         * null and the settings screen offers to start over rather than guess.
         */
        fun fromWindows(windows: List<DailyWindow>): SleepPlan? {
            val byNights = windows.associateBy { nightsOf(it) }
            return when {
                windows.isEmpty() -> null
                windows.size == 1 && byNights.keys.single() == DailyWindow.EVERY_DAY ->
                    SleepPlan(windows.single().toNight(), weekendNights = null)
                windows.size == 2 &&
                    byNights.keys == setOf(DailyWindow.WEEKNIGHTS, DailyWindow.WEEKEND_NIGHTS) ->
                    SleepPlan(
                        byNights.getValue(DailyWindow.WEEKNIGHTS).toNight(),
                        byNights.getValue(DailyWindow.WEEKEND_NIGHTS).toNight(),
                    )
                else -> null
            }
        }

        /** Undo MemoryDays.sleepWindow's after-midnight shift. */
        private fun nightsOf(window: DailyWindow): Set<DayOfWeek> =
            if (window.start.isBefore(MemoryDays.AFTER_MIDNIGHT_CUTOFF)) {
                window.days.map { it.minus(1) }.toSet()
            } else {
                window.days
            }

        private fun DailyWindow.toNight() = Night(start, end)
    }
}
