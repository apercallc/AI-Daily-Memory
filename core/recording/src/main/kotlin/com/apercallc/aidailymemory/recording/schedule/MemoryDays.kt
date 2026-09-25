package com.apercallc.aidailymemory.recording.schedule

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a memory day's report is due (the day is over) and when to tell the user about it.
 */
data class ReportTiming(
    val day: LocalDate,
    /** The day has ended: generate the report after this. Bedtime when a schedule exists. */
    val dueAt: Instant,
    /** Tell the user it's ready: next wake time, or 08:00 without a schedule. */
    val deliverAt: Instant,
)

/**
 * Which "day" a moment belongs to (ADR-0012).
 *
 * A memory day changes over at the midpoint of the sleep window, not at midnight. With sleep
 * 23:00–07:00 the day turns at 03:00, so a 00:30 conversation belongs to the evening before
 * and a 05:30 early start belongs to the new day. Night-shift schedules work the same way.
 * On a night without a scheduled sleep the day turns at [DEFAULT_ROLLOVER].
 */
object MemoryDays {
    val DEFAULT_ROLLOVER: LocalTime = LocalTime.of(4, 0)
    val DEFAULT_DELIVERY: LocalTime = LocalTime.of(8, 0)

    /** A sleep midpoint defines the day for 24 hours; after that the default rollover applies. */
    private val MIDPOINT_REACH: Duration = Duration.ofHours(24)
    private val ONE_MS: Duration = Duration.ofMillis(1)

    fun dayOf(at: Instant, zone: ZoneId, rhythm: DailyRhythm): LocalDate {
        val sleep = sleepSpans(rhythm, at.atZone(zone).toLocalDate(), zone)
            .filter { !it.midpoint.isAfter(at) }
            .maxByOrNull { it.midpoint }
        if (sleep != null && Duration.between(sleep.midpoint, at) < MIDPOINT_REACH) {
            return sleep.end.atZone(zone).toLocalDate()
        }
        return fallbackDayOf(at, zone)
    }

    fun timingFor(day: LocalDate, zone: ZoneId, rhythm: DailyRhythm): ReportTiming {
        val spans = sleepSpans(rhythm, day.plusDays(1), zone)
        // Every instant where the day can change: sleep midpoints, the moments they stop
        // applying, and the default rollovers.
        val candidates = sortedSetOf<Instant>()
        spans.forEach {
            candidates += it.midpoint
            candidates += it.midpoint.plus(MIDPOINT_REACH)
        }
        (1L..2L).forEach { candidates += at(day.plusDays(it), DEFAULT_ROLLOVER, zone) }

        val change = candidates.firstOrNull { c ->
            dayOf(c, zone, rhythm).isAfter(day) && !dayOf(c.minus(ONE_MS), zone, rhythm).isAfter(day)
        } ?: at(day.plusDays(1), DEFAULT_ROLLOVER, zone)

        val endingSleep = spans.firstOrNull { it.midpoint == change }
        if (endingSleep != null) {
            return ReportTiming(day, dueAt = endingSleep.start, deliverAt = endingSleep.end)
        }
        val changeDate = change.atZone(zone).toLocalDate()
        val delivery = at(changeDate, DEFAULT_DELIVERY, zone)
        return ReportTiming(day, dueAt = change, deliverAt = maxOf(delivery, change))
    }

    /**
     * Builds a sleep window from a bedtime and the *nights* it applies to, as people think of
     * them. A bedtime between midnight and [AFTER_MIDNIGHT_CUTOFF] belongs to the evening
     * before, so "Friday night, 01:00" starts on Saturday's date.
     */
    fun sleepWindow(bedtime: LocalTime, wake: LocalTime, nights: Set<DayOfWeek>): DailyWindow {
        val startDays = if (bedtime.isBefore(AFTER_MIDNIGHT_CUTOFF)) {
            nights.map { it.plus(1) }.toSet()
        } else {
            nights
        }
        return DailyWindow(bedtime, wake, startDays)
    }

    /** Bedtimes before this are "late night" and belong to the previous evening's night. */
    val AFTER_MIDNIGHT_CUTOFF: LocalTime = LocalTime.of(6, 0)

    private fun fallbackDayOf(at: Instant, zone: ZoneId): LocalDate {
        val local = at.atZone(zone)
        return if (local.toLocalTime().isBefore(DEFAULT_ROLLOVER)) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    /** Sleep occurrences starting from two days before to two days after [around]. */
    private fun sleepSpans(rhythm: DailyRhythm, around: LocalDate, zone: ZoneId): List<TimeSpan> =
        (-2L..2L).flatMap { offset ->
            val date = around.plusDays(offset)
            rhythm.sleep.mapNotNull { it.occurrenceStartingOn(date, zone) }
        }
}
