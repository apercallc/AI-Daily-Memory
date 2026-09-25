package com.apercallc.aidailymemory.recording.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryDaysTest {
    private val zone = ZoneId.of("America/Chicago")
    private val everyNight = DailyRhythm(sleep = listOf(DailyWindow(t(23), t(7))))

    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    private fun d(date: String) = LocalDate.parse(date)

    // 2026-09-21 is a Monday.
    private fun at(date: String, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(d(date), t(hour, minute)).atZone(zone).toInstant()

    private fun day(date: String, hour: Int, minute: Int = 0, rhythm: DailyRhythm = everyNight) =
        MemoryDays.dayOf(at(date, hour, minute), zone, rhythm)

    @Test
    fun `late-night talk belongs to the evening before, an early start to the new day`() {
        assertEquals(d("2026-09-21"), day("2026-09-21", 22))
        assertEquals(d("2026-09-21"), day("2026-09-22", 0, 30))
        assertEquals(d("2026-09-21"), day("2026-09-22", 2, 59))
        assertEquals(d("2026-09-22"), day("2026-09-22", 3))
        assertEquals(d("2026-09-22"), day("2026-09-22", 5, 30))
    }

    @Test
    fun `without a schedule the day turns at 4 AM`() {
        assertEquals(d("2026-09-21"), day("2026-09-22", 3, 59, DailyRhythm.None))
        assertEquals(d("2026-09-22"), day("2026-09-22", 4, rhythm = DailyRhythm.None))
    }

    @Test
    fun `night-shift sleep turns the day at its own midpoint`() {
        val nightShift = DailyRhythm(sleep = listOf(DailyWindow(t(8), t(16))))

        assertEquals(d("2026-09-21"), day("2026-09-22", 2, rhythm = nightShift))
        assertEquals(d("2026-09-21"), day("2026-09-22", 11, 59, nightShift))
        assertEquals(d("2026-09-22"), day("2026-09-22", 12, rhythm = nightShift))
        assertEquals(d("2026-09-22"), day("2026-09-23", 1, rhythm = nightShift))
    }

    @Test
    fun `nights without a schedule fall back to 4 AM instead of stretching the day`() {
        val weeknights = DailyRhythm(
            sleep = listOf(DailyWindow(t(23), t(7), DailyWindow.WEEKNIGHTS)),
        )

        // Friday 2026-09-25 has no Friday-night sleep: Saturday starts at 04:00.
        assertEquals(d("2026-09-25"), day("2026-09-26", 3, 59, weeknights))
        assertEquals(d("2026-09-26"), day("2026-09-26", 4, rhythm = weeknights))

        val friday = MemoryDays.timingFor(d("2026-09-25"), zone, weeknights)
        assertEquals(at("2026-09-26", 4), friday.dueAt)
        assertEquals(at("2026-09-26", 8), friday.deliverAt)
    }

    @Test
    fun `the report is due at bedtime and delivered at wake time`() {
        val timing = MemoryDays.timingFor(d("2026-09-22"), zone, everyNight)

        assertEquals(ReportTiming(d("2026-09-22"), at("2026-09-22", 23), at("2026-09-23", 7)), timing)
    }

    @Test
    fun `without a schedule the report is due at 4 AM and delivered at 8 AM`() {
        val timing = MemoryDays.timingFor(d("2026-09-22"), zone, DailyRhythm.None)

        assertEquals(ReportTiming(d("2026-09-22"), at("2026-09-23", 4), at("2026-09-23", 8)), timing)
    }

    @Test
    fun `after-midnight bedtimes belong to the evening before`() {
        val late = MemoryDays.sleepWindow(t(1), t(9), setOf(DayOfWeek.FRIDAY))
        val usual = MemoryDays.sleepWindow(t(22, 30), t(6, 30), setOf(DayOfWeek.FRIDAY))

        assertEquals(setOf(DayOfWeek.SATURDAY), late.days)
        assertEquals(setOf(DayOfWeek.FRIDAY), usual.days)
    }

    @Test
    fun `a sleep that crosses the fall-back hour still ends the right day`() {
        // 2026-11-01: 02:00 → 01:00 in Chicago; the night is an hour longer.
        val timing = MemoryDays.timingFor(d("2026-10-31"), zone, everyNight)

        assertEquals(at("2026-10-31", 23), timing.dueAt)
        assertEquals(at("2026-11-01", 7), timing.deliverAt)
        assertEquals(d("2026-11-01"), day("2026-11-01", 7))
    }
}
