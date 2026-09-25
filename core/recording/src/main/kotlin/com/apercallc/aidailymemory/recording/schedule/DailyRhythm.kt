package com.apercallc.aidailymemory.recording.schedule

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Half-open span of time: [start, end). */
data class TimeSpan(val start: Instant, val end: Instant) {
    init {
        require(end.isAfter(start)) { "a span must not be empty" }
    }

    operator fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)

    val midpoint: Instant get() = start.plus(Duration.between(start, end).dividedBy(2))
}

/**
 * A window that repeats on some days, in local wall-clock time (ADR-0012).
 *
 * [days] are the days the window *starts* on. When [end] is not after [start] the window
 * crosses midnight and ends the next day, so a 23:00–07:00 sleep window has start days, not
 * end days. Wall-clock times follow the device's current time zone: after travel, 23:00 means
 * 23:00 where the phone is now.
 */
data class DailyWindow(
    val start: LocalTime,
    val end: LocalTime,
    val days: Set<DayOfWeek> = EVERY_DAY,
) {
    init {
        require(start != end) { "a window must not be empty (start == end)" }
        require(days.isNotEmpty()) { "a window needs at least one day" }
    }

    val crossesMidnight: Boolean get() = !end.isAfter(start)

    /**
     * The occurrence that starts on [date], or null if the window doesn't run that day.
     *
     * DST: a local time inside a spring-forward gap moves forward by the gap and a repeated
     * fall-back time uses its first occurrence (java.time rules). If the adjusted window
     * collapses (for example 02:30–03:00 on a spring-forward night), that occurrence is skipped.
     */
    fun occurrenceStartingOn(date: LocalDate, zone: ZoneId): TimeSpan? {
        if (date.dayOfWeek !in days) return null
        val startAt = ZonedDateTime.of(date, start, zone).toInstant()
        val endDate = if (crossesMidnight) date.plusDays(1) else date
        val endAt = ZonedDateTime.of(endDate, end, zone).toInstant()
        return if (endAt.isAfter(startAt)) TimeSpan(startAt, endAt) else null
    }

    companion object {
        val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        val WEEKNIGHTS: Set<DayOfWeek> = setOf(
            DayOfWeek.SUNDAY,
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
        )
        val WEEKEND_NIGHTS: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    }
}

/** A recurring time the user doesn't want recorded, such as lunch or a weekly 1:1. */
data class QuietWindow(
    val id: String,
    val label: String,
    val window: DailyWindow,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "quiet window id must not be blank" }
        require(label.isNotBlank() && label.length <= MAX_LABEL) {
            "quiet window label must be 1..$MAX_LABEL characters"
        }
    }

    companion object {
        const val MAX_LABEL = 40
    }
}

/**
 * The user's daily rhythm: when they sleep and when they don't want to be recorded.
 *
 * @param sleep sleep windows. Their start days must not overlap, so each night has at most
 *   one bedtime (for example one window for weeknights and one for weekends).
 * @param endDayAtBedtime at bedtime, stop the session and close the day (saves battery all
 *   night). When false, bedtime only pauses, like a quiet window.
 * @param morningReminder at wake time, after a day was ended at bedtime, post a "start your
 *   day" notification. Capture never starts by itself (ADR-0010).
 */
data class DailyRhythm(
    val sleep: List<DailyWindow> = emptyList(),
    val quiet: List<QuietWindow> = emptyList(),
    val endDayAtBedtime: Boolean = true,
    val morningReminder: Boolean = true,
) {
    init {
        val startDays = sleep.flatMap { it.days }
        require(startDays.size == startDays.toSet().size) {
            "sleep windows must not share a start day"
        }
        require(quiet.map { it.id }.toSet().size == quiet.size) { "quiet window ids must be unique" }
    }

    val hasSchedule: Boolean get() = sleep.isNotEmpty() || quiet.any { it.enabled }

    companion object {
        val None = DailyRhythm()
    }
}
