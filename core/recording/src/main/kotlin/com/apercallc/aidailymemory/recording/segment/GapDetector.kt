package com.apercallc.aidailymemory.recording.segment

/**
 * Timing facts for a sealed segment, as recorded by the capture engine.
 *
 * [continuesPrevious] is false when the segment starts after an intentional boundary
 * (session start, resume after pause, recovery after an interruption). Those boundaries are
 * already in the transition journal, so they are not gaps.
 */
data class SegmentTiming(
    val sequence: Int,
    val bootId: String,
    val startElapsedMs: Long,
    val durationMs: Long,
    val continuesPrevious: Boolean,
)

enum class GapKind {
    /** Sequence numbers are missing: segments were lost or never sealed. */
    MISSING_SEGMENTS,

    /** The same sequence number appears twice. This is a capture-engine bug. */
    DUPLICATE_SEQUENCE,

    /** Continuous capture has a silent hole between two segments. */
    TIME_DISCONTINUITY,

    /** Consecutive segments overlap in time, so the clock or the engine is wrong. */
    OVERLAP,

    /** Continuous capture spans a reboot, which should be impossible without a boundary. */
    REBOOT,
}

data class CaptureGap(
    val kind: GapKind,
    /** Last good sequence before the gap, or null if the gap is at the start. */
    val afterSequence: Int?,
    val beforeSequence: Int,
    /** Missing segment count for MISSING_SEGMENTS, milliseconds for time-based kinds. */
    val magnitude: Long,
)

/**
 * Finds unintended discontinuities in one session's sealed segments. Feeds capture health and
 * the endurance-test "audio gaps" measurement. O(n log n); allocation proportional to gaps.
 */
class GapDetector(private val toleranceMs: Long = DEFAULT_TOLERANCE_MS) {
    init {
        require(toleranceMs >= 0) { "toleranceMs must not be negative" }
    }

    fun detect(segments: List<SegmentTiming>): List<CaptureGap> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.sequence }
        val gaps = mutableListOf<CaptureGap>()

        val first = sorted.first()
        if (first.sequence > 0) {
            gaps += CaptureGap(GapKind.MISSING_SEGMENTS, null, first.sequence, first.sequence.toLong())
        }

        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1]
            val current = sorted[i]
            val sequenceStep = current.sequence - previous.sequence
            when {
                sequenceStep == 0 -> gaps += CaptureGap(
                    GapKind.DUPLICATE_SEQUENCE, previous.sequence, current.sequence, 0,
                )
                sequenceStep > 1 -> gaps += CaptureGap(
                    GapKind.MISSING_SEGMENTS,
                    previous.sequence,
                    current.sequence,
                    (sequenceStep - 1).toLong(),
                )
                current.continuesPrevious -> timeGap(previous, current)?.let { gaps += it }
            }
        }
        return gaps
    }

    private fun timeGap(previous: SegmentTiming, current: SegmentTiming): CaptureGap? {
        if (previous.bootId != current.bootId) {
            return CaptureGap(GapKind.REBOOT, previous.sequence, current.sequence, 0)
        }
        val delta = current.startElapsedMs - (previous.startElapsedMs + previous.durationMs)
        return when {
            delta > toleranceMs ->
                CaptureGap(GapKind.TIME_DISCONTINUITY, previous.sequence, current.sequence, delta)
            delta < -toleranceMs ->
                CaptureGap(GapKind.OVERLAP, previous.sequence, current.sequence, -delta)
            else -> null
        }
    }

    companion object {
        const val DEFAULT_TOLERANCE_MS = 250L
    }
}
