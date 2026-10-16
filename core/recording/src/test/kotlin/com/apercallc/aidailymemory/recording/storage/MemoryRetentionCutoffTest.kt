package com.apercallc.aidailymemory.recording.storage

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryRetentionCutoffTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `keep until deleted has no cutoff`() {
        assertNull(MemoryRetentionCutoff.cutoffDay(now, utc, MemoryRetention.KEEP_UNTIL_DELETED))
    }

    @Test
    fun `a 90-day window cuts off 90 days before today`() {
        val cutoff = MemoryRetentionCutoff.cutoffDay(now, utc, MemoryRetention.KEEP_90_DAYS)

        assertEquals(now.atZone(utc).toLocalDate().minusDays(90), cutoff)
    }

    @Test
    fun `a 1-year window cuts off 365 days before today`() {
        val cutoff = MemoryRetentionCutoff.cutoffDay(now, utc, MemoryRetention.KEEP_1_YEAR)

        assertEquals(now.atZone(utc).toLocalDate().minusDays(365), cutoff)
    }

    @Test
    fun `the cutoff is computed in the given zone, not UTC`() {
        // Just before midnight UTC, which is already the next day in a positive-offset zone.
        val lateUtc = Instant.parse("2026-10-16T23:30:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")

        val cutoff = MemoryRetentionCutoff.cutoffDay(lateUtc, tokyo, MemoryRetention.KEEP_90_DAYS)

        assertEquals(lateUtc.atZone(tokyo).toLocalDate().minusDays(90), cutoff)
    }
}
