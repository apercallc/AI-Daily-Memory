package com.apercallc.aidailymemory.ui.sessions

/** Measured segment progress and an ETA derived from completed work in this run. */
data class ProcessingEstimate(
    val percentComplete: Int,
    val remainingMs: Long?,
)

object ProcessingProgressEstimator {
    fun estimate(
        completedSegments: Int,
        totalSegments: Int,
        processingTimeMs: Long,
    ): ProcessingEstimate? {
        if (totalSegments <= 0) return null
        val completed = completedSegments.coerceIn(0, totalSegments)
        val percent = (completed * 100.0 / totalSegments).toInt()
        val remaining = when {
            completed == 0 -> null
            completed == totalSegments -> 0L
            processingTimeMs <= 0L -> null
            else -> {
                (processingTimeMs.toDouble() * (totalSegments - completed) / completed)
                    .coerceAtMost(Long.MAX_VALUE.toDouble())
                    .toLong()
            }
        }
        return ProcessingEstimate(percent, remaining)
    }
}
