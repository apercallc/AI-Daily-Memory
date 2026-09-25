package com.apercallc.aidailymemory.recording.schedule

import com.apercallc.aidailymemory.recording.schedule.OffState.Available
import com.apercallc.aidailymemory.recording.schedule.OffState.Sleeping
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RhythmPlannerTest {
    private val zone = ZoneId.of("America/New_York")
    private val weekdays = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )
    private val night = DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))
    private val lunch = QuietWindow("lunch", "Lunch", DailyWindow(t(12), t(13), weekdays))
    private val rhythm = DailyRhythm(sleep = listOf(night), quiet = listOf(lunch))

    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    // 2026-09-21 is a Monday.
    private fun at(date: String, hour: Int, minute: Int = 0, z: ZoneId = zone): Instant =
        LocalDateTime.of(LocalDate.parse(date), t(hour, minute)).atZone(z).toInstant()

    @Test
    fun `state follows sleep and quiet windows, and sleep outranks quiet`() {
        assertEquals(Sleeping, RhythmPlanner.stateAt(rhythm, at("2026-09-22", 2), zone))
        assertEquals(Available, RhythmPlanner.stateAt(rhythm, at("2026-09-22", 7), zone))
        assertEquals(
            OffState.Quiet("lunch", "Lunch"),
            RhythmPlanner.stateAt(rhythm, at("2026-09-22", 12, 30), zone),
        )
        // Saturday: no lunch window.
        assertEquals(Available, RhythmPlanner.stateAt(rhythm, at("2026-09-26", 12, 30), zone))

        val overlapping = rhythm.copy(
            quiet = listOf(QuietWindow("late", "Late", DailyWindow(t(22), t(23, 30)))),
        )
        assertEquals(Sleeping, RhythmPlanner.stateAt(overlapping, at("2026-09-22", 23, 15), zone))
    }

    @Test
    fun `windows are half-open, starting at start and ending before end`() {
        assertIs<OffState.Quiet>(RhythmPlanner.stateAt(rhythm, at("2026-09-22", 12), zone))
        assertEquals(Available, RhythmPlanner.stateAt(rhythm, at("2026-09-22", 13), zone))
    }

    @Test
    fun `a weekday produces pause, resume, and end-of-day in order`() {
        val actions = RhythmPlanner.transitionsBetween(
            rhythm,
            at("2026-09-22", 8),
            at("2026-09-23", 8),
            zone,
        ).map { it.at to it.actionFor(rhythm) }

        assertEquals(
            listOf(
                at("2026-09-22", 12) to RhythmAction.PAUSE,
                at("2026-09-22", 13) to RhythmAction.RESUME,
                at("2026-09-22", 23) to RhythmAction.END_DAY,
                at("2026-09-23", 7) to RhythmAction.WAKE_REMINDER,
            ),
            actions,
        )
    }

    @Test
    fun `overlapping quiet windows pause once and resume once`() {
        val meetings = rhythm.copy(
            quiet = listOf(
                lunch,
                QuietWindow("one-on-one", "1:1", DailyWindow(t(12, 30), t(14), weekdays)),
            ),
        )

        val actions = RhythmPlanner.transitionsBetween(
            meetings,
            at("2026-09-22", 8),
            at("2026-09-22", 20),
            zone,
        ).mapNotNull { t -> t.actionFor(meetings)?.let { t.at to it } }

        assertEquals(
            listOf(at("2026-09-22", 12) to RhythmAction.PAUSE, at("2026-09-22", 14) to RhythmAction.RESUME),
            actions,
        )
    }

    @Test
    fun `action rules for every relevant change`() {
        val quiet = OffState.Quiet("q", "Q")
        val other = OffState.Quiet("r", "R")
        val ends = DailyRhythm(endDayAtBedtime = true, morningReminder = true)
        val endsQuietly = ends.copy(morningReminder = false)
        val pauses = DailyRhythm(endDayAtBedtime = false)
        fun action(from: OffState, to: OffState, r: DailyRhythm) =
            RhythmTransition(Instant.EPOCH, from, to).actionFor(r)

        assertEquals(RhythmAction.PAUSE, action(Available, quiet, ends))
        assertEquals(RhythmAction.RESUME, action(quiet, Available, ends))
        assertNull(action(quiet, other, ends))
        assertEquals(RhythmAction.END_DAY, action(Available, Sleeping, ends))
        assertEquals(RhythmAction.END_DAY, action(quiet, Sleeping, ends))
        assertEquals(RhythmAction.WAKE_REMINDER, action(Sleeping, Available, ends))
        assertEquals(RhythmAction.WAKE_REMINDER, action(Sleeping, quiet, ends))
        assertNull(action(Sleeping, Available, endsQuietly))
        assertEquals(RhythmAction.PAUSE, action(Available, Sleeping, pauses))
        assertNull(action(quiet, Sleeping, pauses))
        assertEquals(RhythmAction.RESUME, action(Sleeping, Available, pauses))
        assertNull(action(Sleeping, quiet, pauses))
    }

    @Test
    fun `next action skips changes that need nothing and is null without windows`() {
        val next = RhythmPlanner.nextAction(rhythm, at("2026-09-22", 12, 10), zone)

        assertEquals(at("2026-09-22", 13) to RhythmAction.RESUME, next?.let { it.first.at to it.second })
        assertNull(RhythmPlanner.nextAction(DailyRhythm.None, at("2026-09-22", 12), zone))
        assertNull(RhythmPlanner.nextTransition(DailyRhythm.None, at("2026-09-22", 12), zone))
        assertEquals(
            at("2026-09-22", 12),
            RhythmPlanner.nextTransition(rhythm, at("2026-09-22", 9), zone)?.at,
        )
    }

    @Test
    fun `catch-up applies only the last action missed while asleep`() {
        // CPU asleep across the whole lunch window: pausing now would be wrong.
        assertEquals(
            RhythmAction.RESUME,
            RhythmPlanner.catchUpAction(rhythm, at("2026-09-22", 11), at("2026-09-22", 15), zone),
        )
        assertEquals(
            RhythmAction.PAUSE,
            RhythmPlanner.catchUpAction(rhythm, at("2026-09-22", 11), at("2026-09-22", 12, 5), zone),
        )
        assertNull(RhythmPlanner.catchUpAction(rhythm, at("2026-09-22", 14), at("2026-09-22", 15), zone))
        // Clock went backwards or no time passed: nothing to do.
        assertTrue(
            RhythmPlanner.transitionsBetween(rhythm, at("2026-09-22", 15), at("2026-09-22", 14), zone)
                .isEmpty(),
        )
    }

    @Test
    fun `disabled quiet windows are ignored`() {
        val off = rhythm.copy(quiet = listOf(lunch.copy(enabled = false)))

        assertEquals(Available, RhythmPlanner.stateAt(off, at("2026-09-22", 12, 30), zone))
        assertFalse(DailyRhythm(quiet = off.quiet).hasSchedule)
        assertTrue(rhythm.hasSchedule)
    }

    @Test
    fun `sleep still covers the night when clocks spring forward`() {
        // 2026-03-08: 02:00 → 03:00 in New York.
        assertEquals(Sleeping, RhythmPlanner.stateAt(rhythm, at("2026-03-08", 3, 30), zone))
        val wake = RhythmPlanner.nextTransition(rhythm, at("2026-03-08", 3, 30), zone)
        assertEquals(at("2026-03-08", 7), wake?.at)
    }

    @Test
    fun `wall-clock windows follow the zone the phone is in`() {
        val instant = at("2026-09-22", 12, 30)
        val london = ZoneId.of("Europe/London")

        assertIs<OffState.Quiet>(RhythmPlanner.stateAt(rhythm, instant, zone))
        // Same instant is 17:30 in London: not lunch there.
        assertEquals(Available, RhythmPlanner.stateAt(rhythm, instant, london))
    }

    @Test
    fun `windows collapsed by a DST gap are skipped, and shifted ones are kept`() {
        val collapsed = DailyWindow(t(2, 30), t(3))
        assertNull(collapsed.occurrenceStartingOn(LocalDate.parse("2026-03-08"), zone))
        assertTrue(collapsed.occurrenceStartingOn(LocalDate.parse("2026-03-09"), zone) != null)

        val shifted = DailyWindow(t(2), t(4)).occurrenceStartingOn(LocalDate.parse("2026-03-08"), zone)
        assertEquals(at("2026-03-08", 3), shifted?.start)
    }

    @Test
    fun `invalid windows and rhythms are rejected`() {
        assertFailsWith<IllegalArgumentException> { DailyWindow(t(9), t(9)) }
        assertFailsWith<IllegalArgumentException> { DailyWindow(t(9), t(10), emptySet()) }
        assertFailsWith<IllegalArgumentException> { TimeSpan(Instant.EPOCH, Instant.EPOCH) }
        assertFailsWith<IllegalArgumentException> { lunch.copy(id = " ") }
        assertFailsWith<IllegalArgumentException> { lunch.copy(label = "") }
        assertFailsWith<IllegalArgumentException> { lunch.copy(label = "x".repeat(41)) }
        assertFailsWith<IllegalArgumentException> { DailyRhythm(sleep = listOf(night, night)) }
        assertFailsWith<IllegalArgumentException> { DailyRhythm(quiet = listOf(lunch, lunch)) }
    }
}
