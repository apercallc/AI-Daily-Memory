package com.apercallc.aidailymemory.recording.storage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How long structured memory (decisions, actions, commitments, ideas, questions — ADR-0020)
 * stays on the phone once extracted, independent of [AudioRetention] (which only governs the raw
 * recording). Unlike audio, memory was never itself a recording of a voice — it's text the
 * person already reviewed on the timeline, and keeping it is the point of the product — so the
 * default is to keep it until the user deletes it. A sweep under a bounded setting deletes
 * memory items, their corrections, and daily reports outright (AGENTS.md: evidence is never
 * rewritten, but it is deletable; this never touches the transcript evidence itself).
 */
enum class MemoryRetention(val keepDays: Int?) {
    KEEP_90_DAYS(keepDays = 90),
    KEEP_1_YEAR(keepDays = 365),

    /** Kept until the user deletes a session or turns this setting down. */
    KEEP_UNTIL_DELETED(keepDays = null),
    ;

    companion object {
        val Default = KEEP_UNTIL_DELETED
    }
}

/** Where a [MemoryRetention] sweep draws the line, computed once so storage code stays pure. */
object MemoryRetentionCutoff {
    /**
     * The oldest memory day that must survive a sweep run at [now] in [zone], or null when
     * [retention] keeps everything. A memory day strictly before this date is eligible for
     * deletion; the cutoff day itself is always kept, even if it is already over.
     */
    fun cutoffDay(now: Instant, zone: ZoneId, retention: MemoryRetention): LocalDate? {
        val days = retention.keepDays ?: return null
        return now.atZone(zone).toLocalDate().minusDays(days.toLong())
    }
}
