package com.apercallc.aidailymemory.recording.session

/** One recorded session's span, as input to event grouping. */
data class SessionSpan(
    val sessionId: String,
    val startEpochMs: Long,
    val durationMs: Long,
) {
    val endEpochMs: Long get() = startEpochMs + durationMs
}

/** A displayed event: one or more sessions the person experienced as one unit. */
data class EventGroup(
    val sessionIds: List<String>,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/**
 * A user correction of one session's automatic grouping with the session immediately before it
 * in time (TODO.md "Event grouping": support event-boundary corrections). Has no effect on a
 * session with no predecessor (the day's chronologically first session) since there is no
 * boundary there to correct.
 */
enum class SessionBoundaryOverride {
    /** Always group with the previous session, even across the automatic gap/duration rules. */
    FORCE_JOIN,

    /** Never group with the previous session, even if the automatic rules would join them. */
    FORCE_SPLIT,
}

/**
 * Groups a day's sessions into events for the timeline (TODO.md "Event grouping"): short,
 * closely spaced sessions cluster into one event so a string of 30-second notes doesn't
 * fragment the timeline; a long session (a meeting) always stands alone, never merged with a
 * neighbor, so it is never hidden inside someone else's cluster or stretched to cover a gap it
 * wasn't recorded in.
 *
 * Pure and stateless; [overrides] is the only way a caller changes the result for one session,
 * so the default algorithm itself never needs to change to support a correction.
 */
object EventGrouper {
    /** A session at or above this duration is "a meeting": always its own event. */
    const val DEFAULT_LONG_SESSION_MS = 5 * 60_000L

    /** Two short sessions within this gap of each other join the same event. */
    const val DEFAULT_GROUPING_GAP_MS = 10 * 60_000L

    fun group(
        sessions: List<SessionSpan>,
        overrides: Map<String, SessionBoundaryOverride> = emptyMap(),
        longSessionThresholdMs: Long = DEFAULT_LONG_SESSION_MS,
        groupingGapMs: Long = DEFAULT_GROUPING_GAP_MS,
    ): List<EventGroup> {
        if (sessions.isEmpty()) return emptyList()
        val sorted = sessions.sortedBy { it.startEpochMs }
        val groups = mutableListOf<MutableList<SessionSpan>>()

        for (span in sorted) {
            val current = groups.lastOrNull()
            val joins = current != null &&
                joins(current.last(), span, overrides[span.sessionId], longSessionThresholdMs, groupingGapMs)
            if (joins) {
                current.add(span)
            } else {
                groups += mutableListOf(span)
            }
        }

        return groups.map { it.toEventGroup() }
    }

    private fun joins(
        previous: SessionSpan,
        candidate: SessionSpan,
        override: SessionBoundaryOverride?,
        longSessionThresholdMs: Long,
        groupingGapMs: Long,
    ): Boolean = when (override) {
        SessionBoundaryOverride.FORCE_JOIN -> true
        SessionBoundaryOverride.FORCE_SPLIT -> false
        null -> canJoin(previous, candidate, longSessionThresholdMs, groupingGapMs)
    }

    private fun canJoin(
        previous: SessionSpan,
        candidate: SessionSpan,
        longSessionThresholdMs: Long,
        groupingGapMs: Long,
    ): Boolean {
        if (previous.durationMs >= longSessionThresholdMs) return false
        if (candidate.durationMs >= longSessionThresholdMs) return false
        val gap = candidate.startEpochMs - previous.endEpochMs
        return gap <= groupingGapMs
    }

    private fun List<SessionSpan>.toEventGroup() = EventGroup(
        sessionIds = map { it.sessionId },
        startEpochMs = minOf { it.startEpochMs },
        endEpochMs = maxOf { it.endEpochMs },
    )
}
