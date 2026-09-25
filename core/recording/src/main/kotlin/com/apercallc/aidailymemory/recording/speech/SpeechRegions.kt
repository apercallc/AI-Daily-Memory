package com.apercallc.aidailymemory.recording.speech

/**
 * How frame decisions become upload regions (ADR-0015). Starting values are assumptions to be
 * tuned on a labeled set, which is why they are configuration, not constants.
 */
data class SpeechConfig(
    val preRollMs: Long = 1_000,
    val postRollMs: Long = 2_000,
    /** Speech runs separated by less than this are one region. */
    val mergeGapMs: Long = 3_000,
    /** Regions with less detected speech than this are kept but marked low confidence. */
    val minSpeechMs: Long = 300,
) {
    init {
        require(preRollMs >= 0 && postRollMs >= 0) { "rolls must not be negative" }
        require(mergeGapMs >= 0) { "mergeGapMs must not be negative" }
        require(minSpeechMs >= 0) { "minSpeechMs must not be negative" }
    }
}

/** A span of a segment to transcribe, in ms from the segment start: [startMs, endMs). */
data class SpeechRegion(
    val startMs: Long,
    val endMs: Long,
    /** Detected speech inside the region, before padding. */
    val speechMs: Long,
    val lowConfidence: Boolean,
) {
    init {
        require(startMs in 0..endMs) { "region must be ordered and non-negative" }
        require(speechMs in 0..(endMs - startMs)) { "speech cannot exceed the region" }
    }

    val lengthMs: Long get() = endMs - startMs
}

/** What one analyzed segment contributes to the cost model. */
data class SpeechSummary(
    val totalMs: Long,
    val regions: List<SpeechRegion>,
) {
    /** Raw detected speech. */
    val speechMs: Long get() = regions.sumOf { it.speechMs }

    /** What would be uploaded and transcribed: speech plus padding. */
    val paddedMs: Long get() = regions.sumOf { it.lengthMs }

    /** Share of the recorded time that would be transcribed, 0.0..1.0. */
    val transcribedRatio: Double get() = if (totalMs == 0L) 0.0 else paddedMs.toDouble() / totalMs
}

object SpeechRegions {
    /**
     * Builds regions from per-frame decisions. [frames] holds [count] decisions of [frameMs]
     * each; [totalMs] is the segment's real duration (the last frame may be partial or missing),
     * and every region is clamped to it.
     */
    fun build(
        frames: BooleanArray,
        count: Int,
        frameMs: Int,
        totalMs: Long,
        config: SpeechConfig = SpeechConfig(),
    ): SpeechSummary {
        require(count in 0..frames.size) { "count outside buffer" }
        require(frameMs > 0) { "frameMs must be positive" }
        require(totalMs >= 0) { "totalMs must not be negative" }
        val runs = speechRuns(frames, count, frameMs.toLong(), totalMs)
        val merged = mergeRuns(runs, config.mergeGapMs)
        val padded = merged.map { run ->
            SpeechRegion(
                startMs = (run.startMs - config.preRollMs).coerceAtLeast(0),
                endMs = (run.endMs + config.postRollMs).coerceAtMost(totalMs),
                speechMs = run.speechMs,
                lowConfidence = run.speechMs < config.minSpeechMs,
            )
        }
        return SpeechSummary(totalMs, joinOverlapping(padded, config.minSpeechMs))
    }

    private data class Run(val startMs: Long, val endMs: Long, val speechMs: Long)

    private fun speechRuns(frames: BooleanArray, count: Int, frameMs: Long, totalMs: Long): List<Run> {
        val runs = mutableListOf<Run>()
        var start = -1
        for (i in 0..count) {
            val speech = i < count && frames[i]
            if (speech && start < 0) {
                start = i
            } else if (!speech && start >= 0) {
                val startMs = (start * frameMs).coerceAtMost(totalMs)
                val endMs = (i * frameMs).coerceAtMost(totalMs)
                if (endMs > startMs) runs += Run(startMs, endMs, endMs - startMs)
                start = -1
            }
        }
        return runs
    }

    private fun mergeRuns(runs: List<Run>, gapMs: Long): List<Run> {
        val out = mutableListOf<Run>()
        for (run in runs) {
            val last = out.lastOrNull()
            if (last != null && run.startMs - last.endMs < gapMs) {
                out[out.lastIndex] = Run(last.startMs, run.endMs, last.speechMs + run.speechMs)
            } else {
                out += run
            }
        }
        return out
    }

    /** Padding can make neighbours overlap; overlapping regions become one. */
    private fun joinOverlapping(regions: List<SpeechRegion>, minSpeechMs: Long): List<SpeechRegion> {
        val out = mutableListOf<SpeechRegion>()
        for (region in regions) {
            val last = out.lastOrNull()
            if (last != null && region.startMs <= last.endMs) {
                val speech = last.speechMs + region.speechMs
                out[out.lastIndex] = SpeechRegion(
                    startMs = last.startMs,
                    endMs = maxOf(last.endMs, region.endMs),
                    speechMs = speech,
                    lowConfidence = speech < minSpeechMs,
                )
            } else {
                out += region
            }
        }
        return out
    }
}
