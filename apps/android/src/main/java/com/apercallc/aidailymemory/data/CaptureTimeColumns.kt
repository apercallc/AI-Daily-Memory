package com.apercallc.aidailymemory.data

import androidx.room.ColumnInfo
import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Column form of [CaptureTime] (ADR-0006), embedded in every captured record. Stored
 * denormalized on purpose: recall must never re-derive a local date from today's time zone.
 */
data class CaptureTimeColumns(
    @ColumnInfo(name = "occurred_at_epoch_ms") val occurredAtEpochMs: Long,
    @ColumnInfo(name = "timezone") val timezone: String,
    @ColumnInfo(name = "utc_offset_minutes") val utcOffsetMinutes: Int,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "elapsed_realtime_ms") val elapsedRealtimeMs: Long,
    @ColumnInfo(name = "boot_id") val bootId: String,
) {
    /** Re-validates on load: CaptureTime's invariants reject corrupt rows loudly. */
    fun toCaptureTime(): CaptureTime = CaptureTime(
        occurredAt = Instant.ofEpochMilli(occurredAtEpochMs),
        timezone = ZoneId.of(timezone),
        utcOffsetMinutes = utcOffsetMinutes,
        localDate = LocalDate.parse(localDate),
        elapsedRealtimeMs = elapsedRealtimeMs,
        bootId = bootId,
    )

    companion object {
        fun of(time: CaptureTime) = CaptureTimeColumns(
            occurredAtEpochMs = time.occurredAt.toEpochMilli(),
            timezone = time.timezone.id,
            utcOffsetMinutes = time.utcOffsetMinutes,
            localDate = time.localDate.toString(),
            elapsedRealtimeMs = time.elapsedRealtimeMs,
            bootId = time.bootId,
        )
    }
}
