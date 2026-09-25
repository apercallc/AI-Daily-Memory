package com.apercallc.aidailymemory.recording.speech

import kotlin.math.log10
import kotlin.math.max

/** Receives one decision per analysis frame, in order. */
fun interface FrameSink {
    fun onFrame(isSpeech: Boolean)
}

/**
 * Energy gate with an adaptive noise floor (ADR-0015, stage one).
 *
 * A frame counts as speech when it is at least [marginDb] above the tracked noise floor and
 * above an absolute floor. The noise floor falls quickly and rises slowly, so a quiet room
 * sets it within a second or two and a long conversation barely lifts it.
 *
 * It over-counts steady noise (a café, a car) as speech, which suits both of its jobs: the
 * shadow measurement it feeds gives an upper bound on transcription cost, and as a fallback
 * gate it errs toward uploading more rather than less.
 *
 * Allocation-free per call. Not thread-safe; use one instance per stream.
 */
class EnergyVad(
    sampleRateHz: Int,
    val frameMs: Int = DEFAULT_FRAME_MS,
    private val marginDb: Double = DEFAULT_MARGIN_DB,
    private val absoluteFloorDb: Double = DEFAULT_ABSOLUTE_FLOOR_DB,
    private val riseDbPerSecond: Double = DEFAULT_RISE_DB_PER_SECOND,
    initialNoiseDb: Double = DEFAULT_INITIAL_NOISE_DB,
) {
    private val frameSamples: Int
    private val risePerFrame: Double
    private var noiseDb = initialNoiseDb
    private var sumSquares = 0.0
    private var filled = 0

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(frameMs in MIN_FRAME_MS..MAX_FRAME_MS) { "frameMs must be 10..100" }
        require(marginDb > 0) { "marginDb must be positive" }
        require(riseDbPerSecond >= 0) { "riseDbPerSecond must not be negative" }
        frameSamples = sampleRateHz * frameMs / MILLIS_PER_SECOND
        require(frameSamples > 0) { "frame shorter than one sample" }
        risePerFrame = riseDbPerSecond * frameMs / MILLIS_PER_SECOND
    }

    /** Current noise-floor estimate in dBFS; exposed for tests and diagnostics. */
    val noiseFloorDb: Double get() = noiseDb

    /**
     * Feeds the first [count] mono 16-bit samples of [samples]. Calls [sink] once for every
     * frame completed; a partial frame carries over to the next call.
     */
    fun process(samples: ShortArray, count: Int = samples.size, sink: FrameSink) {
        require(count in 0..samples.size) { "count $count outside buffer of ${samples.size}" }
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
            if (++filled == frameSamples) {
                sink.onFrame(classify(sumSquares / frameSamples))
                sumSquares = 0.0
                filled = 0
            }
        }
    }

    private fun classify(meanSquare: Double): Boolean {
        val db = decibels(meanSquare)
        val speech = db >= absoluteFloorDb && db >= noiseDb + marginDb
        noiseDb = if (db < noiseDb) {
            // Fall fast (halfway per frame) so the floor finds a quiet room quickly.
            noiseDb + (db - noiseDb) * FALL_FACTOR
        } else {
            noiseDb + risePerFrame
        }
        return speech
    }

    companion object {
        const val DEFAULT_FRAME_MS = 30
        const val DEFAULT_MARGIN_DB = 9.0
        const val DEFAULT_ABSOLUTE_FLOOR_DB = -55.0
        const val DEFAULT_RISE_DB_PER_SECOND = 0.5
        const val DEFAULT_INITIAL_NOISE_DB = -60.0
        private const val MIN_FRAME_MS = 10
        private const val MAX_FRAME_MS = 100
        private const val MILLIS_PER_SECOND = 1000
        private const val FALL_FACTOR = 0.5
        private const val FULL_SCALE_SQUARED = 32768.0 * 32768.0
        private const val SILENCE_DB = -120.0

        /** dBFS of a mean-square value of 16-bit samples; exact zero maps to [SILENCE_DB]. */
        fun decibels(meanSquare: Double): Double = if (meanSquare <= 0.0) {
            SILENCE_DB
        } else {
            max(SILENCE_DB, TEN * log10(meanSquare / FULL_SCALE_SQUARED))
        }

        /** Recorded with every analysis so results from different detectors never mix. */
        const val VERSION = "energy-v1"
        private const val TEN = 10.0
    }
}
