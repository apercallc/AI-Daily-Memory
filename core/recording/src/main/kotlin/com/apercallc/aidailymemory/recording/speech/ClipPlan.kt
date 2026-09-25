package com.apercallc.aidailymemory.recording.speech

/**
 * One upload clip (ADR-0015): whole AAC frames [firstFrame, endFrame) of a segment.
 *
 * Clips are cut on frame boundaries, so they need no re-encode and every clip is a valid ADTS
 * stream on its own. [offsetMs] and [durationMs] are exact for those frames, so the server can
 * map transcript times back to the segment (ADR-0006) without drift.
 */
data class PlannedClip(
    val firstFrame: Int,
    val endFrame: Int,
    val offsetMs: Long,
    val durationMs: Long,
) {
    init {
        require(firstFrame in 0 until endFrame) { "clip must contain at least one frame" }
    }

    val frameCount: Int get() = endFrame - firstFrame
}

object ClipPlanner {
    private const val SAMPLES_PER_FRAME = 1024L
    private const val MILLIS = 1000L

    /**
     * Frames covering each region: the frame holding the region start through the frame
     * holding its last millisecond, never past [frameCount]. Regions longer than
     * [maxClipMs] are split, so one long meeting never becomes one oversized upload.
     */
    fun plan(
        regions: List<SpeechRegion>,
        frameCount: Int,
        sampleRateHz: Int,
        maxClipMs: Long = DEFAULT_MAX_CLIP_MS,
    ): List<PlannedClip> {
        require(frameCount >= 0) { "frameCount must not be negative" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        val maxFrames = framesFor(maxClipMs, sampleRateHz)
        require(maxFrames > 0) { "maxClipMs shorter than one frame" }
        return regions.sortedBy { it.startMs }.flatMap { region ->
            val first = frameAt(region.startMs, sampleRateHz).coerceAtMost(frameCount)
            val end = (frameAt(region.endMs - 1, sampleRateHz) + 1).coerceAtMost(frameCount)
            if (end <= first) {
                emptyList()
            } else {
                (first until end step maxFrames).map { start ->
                    clip(start, minOf(start + maxFrames, end), sampleRateHz)
                }
            }
        }.fold(mutableListOf()) { out, clip ->
            // Regions never overlap, but rounding to frames can make neighbours share one.
            val last = out.lastOrNull()
            if (last != null && clip.firstFrame < last.endFrame) {
                if (clip.endFrame > last.endFrame) out += clip(last.endFrame, clip.endFrame, sampleRateHz)
            } else {
                out += clip
            }
            out
        }
    }

    private fun clip(first: Int, end: Int, sampleRateHz: Int) = PlannedClip(
        firstFrame = first,
        endFrame = end,
        offsetMs = msAt(first, sampleRateHz),
        durationMs = msAt(end, sampleRateHz) - msAt(first, sampleRateHz),
    )

    private fun frameAt(ms: Long, sampleRateHz: Int): Int =
        (ms.coerceAtLeast(0) * sampleRateHz / (SAMPLES_PER_FRAME * MILLIS)).toInt()

    private fun framesFor(ms: Long, sampleRateHz: Int): Int =
        (ms * sampleRateHz / (SAMPLES_PER_FRAME * MILLIS)).toInt()

    private fun msAt(frame: Int, sampleRateHz: Int): Long = frame * SAMPLES_PER_FRAME * MILLIS / sampleRateHz

    /** Ten minutes: ~2.4 MB at 32 kbit/s, well under the server's per-clip limit. */
    const val DEFAULT_MAX_CLIP_MS = 10 * 60_000L
}
