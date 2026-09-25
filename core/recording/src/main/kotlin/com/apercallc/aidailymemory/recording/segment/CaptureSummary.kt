package com.apercallc.aidailymemory.recording.segment

/**
 * Honest totals for the capture-health surface, computed from segment records only.
 *
 * Only SEALED-or-later segments count as captured audio (ADR-0007). A segment that is still
 * being written, or that failed validation, contributes nothing: uncaptured time is never
 * shown as recorded.
 */
data class CaptureSummary(
    val capturedMs: Long,
    /** Captured audio the server has not yet acknowledged. Lost if the phone is lost. */
    val pendingUploadMs: Long,
    val pendingUploadBytes: Long,
    val quarantinedSegments: Int,
    /** Highest sequence that is sealed or later, or null when nothing is captured yet. */
    val lastPersistedSequence: Int?,
) {
    companion object {
        val Empty = CaptureSummary(0, 0, 0, 0, null)

        fun of(segments: Iterable<AudioSegment>): CaptureSummary {
            var captured = 0L
            var pendingMs = 0L
            var pendingBytes = 0L
            var quarantined = 0
            var lastSequence: Int? = null
            for (segment in segments) {
                when (segment.state) {
                    SegmentState.SEALED, SegmentState.UPLOADED -> {
                        captured += segment.durationMs
                        pendingMs += segment.durationMs
                        pendingBytes += segment.byteSize
                    }
                    SegmentState.ACKNOWLEDGED -> captured += segment.durationMs
                    // A deleted segment was still captured, unless it was quarantined first.
                    SegmentState.DELETED -> if (segment.defect == null) {
                        captured += segment.durationMs
                    } else {
                        quarantined++
                    }
                    SegmentState.QUARANTINED -> quarantined++
                    SegmentState.WRITING -> Unit
                }
                if (segment.countsAsCaptured()) {
                    lastSequence = maxOf(lastSequence ?: segment.sequence, segment.sequence)
                }
            }
            return CaptureSummary(captured, pendingMs, pendingBytes, quarantined, lastSequence)
        }

        private fun AudioSegment.countsAsCaptured(): Boolean =
            state != SegmentState.WRITING && defect == null
    }
}
