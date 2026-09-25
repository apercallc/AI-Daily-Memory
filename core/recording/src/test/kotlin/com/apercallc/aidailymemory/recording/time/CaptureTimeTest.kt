package com.apercallc.aidailymemory.recording.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CaptureTimeTest {
    private val newYork = ZoneId.of("America/New_York")

    private fun at(iso: String, zone: ZoneId, elapsed: Long = 1_000, boot: String = "boot-1") =
        CaptureTime.of(Instant.parse(iso), zone, elapsed, boot)

    @Test
    fun `local date follows the zone, not UTC, around midnight`() {
        val lateEvening = at("2026-09-25T03:30:00Z", ZoneId.of("America/Chicago"))

        assertEquals(LocalDate.parse("2026-09-24"), lateEvening.localDate)
        assertEquals(-300, lateEvening.utcOffsetMinutes)
    }

    @Test
    fun `DST fall-back gives two distinct offsets on the same local date`() {
        val firstOneThirty = at("2026-11-01T05:30:00Z", newYork)
        val secondOneThirty = at("2026-11-01T06:30:00Z", newYork)

        assertEquals(-240, firstOneThirty.utcOffsetMinutes)
        assertEquals(-300, secondOneThirty.utcOffsetMinutes)
        assertEquals(firstOneThirty.localDate, secondOneThirty.localDate)
    }

    @Test
    fun `DST spring-forward switches offset at the transition instant`() {
        assertEquals(-300, at("2026-03-08T06:59:59Z", newYork).utcOffsetMinutes)
        assertEquals(-240, at("2026-03-08T07:00:00Z", newYork).utcOffsetMinutes)
    }

    @Test
    fun `non-hour offsets are preserved`() {
        assertEquals(330, at("2026-09-25T12:00:00Z", ZoneId.of("Asia/Kolkata")).utcOffsetMinutes)
        assertEquals(345, at("2026-09-25T12:00:00Z", ZoneId.of("Asia/Kathmandu")).utcOffsetMinutes)
    }

    @Test
    fun `travel changes local date for the same instant`() {
        val instant = "2026-09-25T23:30:00Z"

        assertEquals(LocalDate.parse("2026-09-25"), at(instant, newYork).localDate)
        assertEquals(LocalDate.parse("2026-09-26"), at(instant, ZoneId.of("Asia/Tokyo")).localDate)
    }

    @Test
    fun `monotonic duration is available only within one boot`() {
        val first = at("2026-09-25T12:00:00Z", newYork, elapsed = 10_000)
        // Wall clock stepped backwards, but the monotonic clock still advanced.
        val second = at("2026-09-25T11:59:00Z", newYork, elapsed = 70_000)
        val afterReboot = at("2026-09-25T12:05:00Z", newYork, elapsed = 500, boot = "boot-2")

        assertEquals(60_000, second.monotonicMillisSince(first))
        assertNull(afterReboot.monotonicMillisSince(first))
    }

    @Test
    fun `inconsistent or invalid fields are rejected`() {
        val valid = at("2026-09-25T12:00:00Z", newYork)

        assertFailsWith<IllegalArgumentException> { valid.copy(utcOffsetMinutes = 0) }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(localDate = valid.localDate.plusDays(1))
        }
        assertFailsWith<IllegalArgumentException> { valid.copy(elapsedRealtimeMs = -1) }
        assertFailsWith<IllegalArgumentException> { valid.copy(bootId = " ") }
    }
}
