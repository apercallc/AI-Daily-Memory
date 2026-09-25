package com.apercallc.aidailymemory.recording.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When a captured record happened, recorded at capture time (ADR-0006).
 *
 * The offset and local date are derived from the instant and zone *at that instant*, so
 * DST changes and travel across time zones give each record its own correct local date.
 * Recall ("yesterday", "this morning") uses [localDate] and [utcOffsetMinutes] directly and
 * never needs to reconstruct them later.
 *
 * Deliberately has no natural ordering. Wall clocks can step backwards and monotonic clocks
 * reset on reboot, so no comparator over both is transitive. Order records within a session by
 * segment `sequence`, and use [monotonicMillisSince] for durations inside one boot.
 */
data class CaptureTime(
    val occurredAt: Instant,
    val timezone: ZoneId,
    val utcOffsetMinutes: Int,
    val localDate: LocalDate,
    val elapsedRealtimeMs: Long,
    val bootId: String,
) {
    init {
        require(elapsedRealtimeMs >= 0) { "elapsedRealtimeMs must not be negative" }
        require(bootId.isNotBlank()) { "bootId must not be blank" }
        val offset = timezone.rules.getOffset(occurredAt)
        require(utcOffsetMinutes * SECONDS_PER_MINUTE == offset.totalSeconds) {
            "utcOffsetMinutes does not match $timezone at $occurredAt"
        }
        require(localDate == occurredAt.atOffset(offset).toLocalDate()) {
            "localDate does not match $timezone at $occurredAt"
        }
    }

    /**
     * Milliseconds from [earlier] to this record on the monotonic clock, or `null` when the two
     * were captured in different boots. In that case, only the wall clock is available, and it
     * is not trustworthy for gap measurement.
     */
    fun monotonicMillisSince(earlier: CaptureTime): Long? =
        if (bootId == earlier.bootId) elapsedRealtimeMs - earlier.elapsedRealtimeMs else null

    companion object {
        private const val SECONDS_PER_MINUTE = 60

        fun of(
            occurredAt: Instant,
            timezone: ZoneId,
            elapsedRealtimeMs: Long,
            bootId: String,
        ): CaptureTime {
            val offset = timezone.rules.getOffset(occurredAt)
            return CaptureTime(
                occurredAt = occurredAt,
                timezone = timezone,
                utcOffsetMinutes = offset.totalSeconds / SECONDS_PER_MINUTE,
                localDate = occurredAt.atOffset(offset).toLocalDate(),
                elapsedRealtimeMs = elapsedRealtimeMs,
                bootId = bootId,
            )
        }
    }
}
