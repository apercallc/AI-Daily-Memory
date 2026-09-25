package com.apercallc.aidailymemory.ui

import com.apercallc.aidailymemory.data.SessionRow
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.ui.sessions.SessionGroups
import com.apercallc.aidailymemory.ui.settings.Night
import com.apercallc.aidailymemory.ui.settings.QuietWindowForm
import com.apercallc.aidailymemory.ui.settings.SleepPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain-JVM tests for the UI's decision logic (no Compose, no Android). */
class ScheduleUiLogicTest {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun `sleep plans round-trip through schedule windows, including after-midnight bedtimes`() {
        val plans = listOf(
            SleepPlan(Night(t(23), t(7)), null),
            SleepPlan(Night(t(22, 30), t(6, 15)), Night(t(1), t(9, 30))),
            SleepPlan(Night(t(0, 30), t(8)), Night(t(2), t(10))),
        )
        plans.forEach { plan -> assertEquals(plan, SleepPlan.fromWindows(plan.toWindows()), "$plan") }
    }

    @Test
    fun `windows this app didn't write are not guessed at`() {
        assertNull(SleepPlan.fromWindows(emptyList()))
        assertNull(SleepPlan.fromWindows(listOf(DailyWindow(t(23), t(7), setOf(DayOfWeek.MONDAY)))))
    }

    @Test
    fun `quiet time form reports every problem at once`() {
        assertEquals(emptySet(), QuietWindowForm.errors("Lunch", t(12), t(13), setOf(DayOfWeek.MONDAY)))
        assertEquals(
            setOf(QuietWindowForm.Error.LABEL, QuietWindowForm.Error.TIMES, QuietWindowForm.Error.DAYS),
            QuietWindowForm.errors("   ", t(12), t(12), emptySet()),
        )
        assertTrue(QuietWindowForm.Error.LABEL in QuietWindowForm.errors("x".repeat(41), t(1), t(2), DailyWindow.EVERY_DAY))
    }

    @Test
    fun `sessions are grouped by memory day, newest first, and titled in time order`() {
        val zone = ZoneId.of("America/Chicago")
        fun row(id: String, day: String, hour: Int, title: String? = null, captured: Long = 60_000) = SessionRow(
            sessionId = id,
            memoryDay = day,
            zoneId = zone.id,
            startedAtEpochMs = LocalDateTime.of(LocalDate.parse(day), t(hour)).atZone(zone).toInstant().toEpochMilli(),
            customTitle = title,
            lastActivityEpochMs = LocalDateTime.of(LocalDate.parse(day), t(hour, 45)).atZone(zone).toInstant().toEpochMilli(),
            capturedMs = captured,
        )

        val groups = SessionGroups.group(
            rows = listOf(
                row("c", "2026-09-22", 15),
                row("a", "2026-09-21", 9),
                row("b", "2026-09-22", 13, title = "Design review"),
                row("d", "2026-09-22", 16),
            ),
            openSessionId = "d",
        )

        assertEquals(listOf(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-21")), groups.map { it.day })
        val today = groups.first()
        assertEquals(listOf("b", "c", "d"), today.sessions.map { it.sessionId })
        assertEquals(
            listOf(
                SessionTitle.Custom("Design review"),
                SessionTitle.Default(PartOfDay.AFTERNOON, 2),
                SessionTitle.Default(PartOfDay.AFTERNOON, 3),
            ),
            today.sessions.map { it.title },
        )
        assertNull(today.sessions.last().end) // still open
        assertEquals(180_000, today.capturedMs)
        assertEquals(t(15, 45), today.sessions[1].end?.toLocalTime())
    }

    @Test
    fun `an unknown stored zone falls back instead of crashing the list`() {
        val row = SessionRow("x", "2026-09-22", "Mars/Olympus_Mons", 0, null, null, 0)

        assertEquals(1, SessionGroups.group(listOf(row), null).single().sessions.size)
    }
}
