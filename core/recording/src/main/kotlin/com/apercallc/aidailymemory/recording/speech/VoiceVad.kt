package com.apercallc.aidailymemory.recording.speech

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Human-voice detector (ADR-0015, stage one, voice-only). It replaces the plain energy gate as
 * the decision that picks which parts of a recording are worth transcribing.
 *
 * Loud is not the same as speech. A frame is only kept when all of these hold:
 *
 * 1. It clears the adaptive energy gate ([EnergyVad]): silence and a quiet room never count.
 * 2. Within about a second around it, several frames are *voiced*: they repeat at a pitch in
 *    the human range (70–400 Hz) after pre-emphasis. Steady noise (wind, rain, traffic, a fan)
 *    has no such repetition.
 * 3. That second is not a held tone: voiced in nearly every active frame *and* a pitch that
 *    does not move is how an instrument or a hum looks. Speech glides, and mixes in unvoiced
 *    consonants and pauses.
 * 4. The loudness rises and falls at syllable pace at least once. A level that stays flat is a
 *    machine, not a person.
 *
 * Decisions look a second ahead, so [process] only collects features and [finish] emits one
 * decision per frame in order. One instance per segment; not thread-safe.
 *
 * These are rules of thumb, not a trained model: they are meant to remove the bulk of
 * non-voice audio before transcription costs money, and they err toward keeping a doubtful
 * second (a missed sentence is worse than a few seconds of extra upload). Tune on labeled
 * audio before trusting the numbers.
 */
class VoiceVad(
    private val sampleRateHz: Int,
    val frameMs: Int = EnergyVad.DEFAULT_FRAME_MS,
) {
    private val gate = EnergyVad(sampleRateHz, frameMs)
    private val frameSamples = sampleRateHz * frameMs / MILLIS_PER_SECOND
    private val decimation = max(1, sampleRateHz / TARGET_RATE_HZ)
    private val frame = ShortArray(frameSamples)
    private val reduced = DoubleArray(frameSamples / decimation)
    private val minLag = (sampleRateHz / decimation / MAX_PITCH_HZ).coerceAtLeast(2)
    private val maxLag = min(sampleRateHz / decimation / MIN_PITCH_HZ, reduced.size - MIN_OVERLAP)
    private val halfWindow = (WINDOW_MS / frameMs / 2).coerceAtLeast(1)

    private var filled = 0
    private var frames = 0
    private var levelDb = FloatArray(INITIAL_FRAMES)
    private var active = BooleanArray(INITIAL_FRAMES)
    private var voiced = BooleanArray(INITIAL_FRAMES)
    private var pitchLag = FloatArray(INITIAL_FRAMES)

    init {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(frameMs in MIN_FRAME_MS..MAX_FRAME_MS) { "frameMs must be 20..100" }
        require(maxLag > minLag) { "frame too short for the pitch range" }
    }

    /** Feeds the first [count] mono 16-bit samples of [samples]. */
    fun process(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) { "count $count outside buffer of ${samples.size}" }
        for (i in 0 until count) {
            frame[filled++] = samples[i]
            if (filled == frameSamples) {
                collect()
                filled = 0
            }
        }
    }

    /** Emits one decision per completed frame, in order, then forgets them. */
    fun finish(sink: FrameSink) {
        for (i in 0 until frames) sink.onFrame(active[i] && windowLooksLikeSpeech(i))
        frames = 0
        filled = 0
    }

    private fun collect() {
        if (frames == levelDb.size) grow()
        // A constant offset (common on cheap microphones) is not sound; leaving it in would raise
        // the noise floor and hide quiet speech.
        var total = 0L
        for (s in frame) total += s
        val offset = (total / frameSamples).toInt()
        var sumSquares = 0.0
        for (i in 0 until frameSamples) {
            val centered = (frame[i] - offset).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            frame[i] = centered.toShort()
            sumSquares += centered.toDouble() * centered
        }
        var isActive = false
        gate.process(frame, frameSamples) { isActive = it }
        levelDb[frames] = EnergyVad.decibels(sumSquares / frameSamples).toFloat()
        active[frames] = isActive
        if (isActive) {
            val lag = periodicLag()
            voiced[frames] = lag > 0.0
            pitchLag[frames] = lag.toFloat()
        } else {
            voiced[frames] = false
            pitchLag[frames] = 0f
        }
        frames++
    }

    /**
     * The pitch lag (in decimated samples, to a fraction of one) when the frame repeats like a
     * voice, else 0.
     */
    private fun periodicLag(): Double {
        // Decimate by averaging, remove the mean, then pre-emphasise so low rumble does not look
        // periodic.
        var mean = 0.0
        for (i in reduced.indices) {
            var sum = 0.0
            for (k in 0 until decimation) sum += frame[i * decimation + k]
            reduced[i] = sum / decimation
            mean += reduced[i]
        }
        mean /= reduced.size
        var previous = reduced[0] - mean
        for (i in 1 until reduced.size) {
            val current = reduced[i] - mean
            reduced[i] = current - PRE_EMPHASIS * previous
            previous = current
        }
        reduced[0] = 0.0
        var bestLag = 0
        var best = 0.0
        var before = 0.0
        var current = correlation(minLag - 1)
        for (lag in minLag..maxLag) {
            val after = correlation(lag + 1)
            val score = correlation(lag)
            if (score > before && score >= after && score > best) {
                best = score
                bestLag = lag
            }
            before = current
            current = score
        }
        if (best < VOICED_CORRELATION) return 0.0
        // Parabolic interpolation across the peak: a whole-sample lag jumps by several percent at
        // 400 Hz, which would make a steady note look like it wobbles.
        val left = correlation(bestLag - 1)
        val right = correlation(bestLag + 1)
        val curvature = left - 2 * best + right
        val shift = if (curvature < 0) 0.5 * (left - right) / curvature else 0.0
        return bestLag + shift.coerceIn(-0.5, 0.5)
    }

    /** Normalised correlation of the frame with itself shifted by [lag]; 0 when there is no energy. */
    private fun correlation(lag: Int): Double {
        val limit = reduced.size - lag
        if (limit < MIN_OVERLAP) return 0.0
        var cross = 0.0
        var head = 0.0
        var tail = 0.0
        for (i in 0 until limit) {
            val a = reduced[i]
            val b = reduced[i + lag]
            cross += a * b
            head += a * a
            tail += b * b
        }
        val scale = sqrt(head * tail)
        return if (scale <= 0.0) 0.0 else cross / scale
    }

    private fun windowLooksLikeSpeech(center: Int): Boolean {
        val from = max(0, center - halfWindow)
        val to = min(frames - 1, center + halfWindow)
        var activeCount = 0
        var voicedCount = 0
        for (i in from..to) {
            if (active[i]) {
                activeCount++
                if (voiced[i]) voicedCount++
            }
        }
        if (voicedCount < MIN_VOICED_FRAMES) return false
        // A held note is voiced almost everywhere *and* does not move. Continuous voiced speech
        // (a long vowel-heavy phrase) is voiced almost everywhere too, but its pitch glides.
        if (activeCount >= MIN_FRAMES_FOR_TONE_TEST &&
            voicedCount >= TONE_VOICED_FRACTION * activeCount &&
            pitchSpread(from, to, voicedCount) < TONE_PITCH_SPREAD
        ) {
            return false
        }
        return syllablePeaks(from, to) >= 1
    }

    /** Standard deviation of the pitch lag over the voiced frames, as a share of its mean. */
    private fun pitchSpread(from: Int, to: Int, voicedCount: Int): Double {
        var sum = 0.0
        for (i in from..to) if (voiced[i]) sum += pitchLag[i]
        val mean = sum / voicedCount
        var squares = 0.0
        for (i in from..to) {
            if (voiced[i]) {
                val d = pitchLag[i] - mean
                squares += d * d
            }
        }
        return sqrt(squares / voicedCount) / mean
    }

    /**
     * Counts loudness peaks that rise [SYLLABLE_DEPTH_DB] above a dip and fall back by as much:
     * the ups and downs of syllables. The level is smoothed over three frames first so
     * single-frame jitter is not counted. A window that starts or ends inside a syllable still
     * counts that syllable.
     */
    private fun syllablePeaks(from: Int, to: Int): Int {
        var peaks = 0
        var rising = true
        var valley = Float.MAX_VALUE
        var peak = smoothed(from)
        for (i in from + 1..to) {
            val level = smoothed(i)
            if (rising) {
                peak = max(peak, level)
                if (peak - level >= SYLLABLE_DEPTH_DB) {
                    peaks++
                    rising = false
                    valley = level
                }
            } else {
                valley = min(valley, level)
                if (level - valley >= SYLLABLE_DEPTH_DB) {
                    rising = true
                    peak = level
                }
            }
        }
        // Rose from a dip and the window ended before it fell.
        if (rising && peak - smoothedMin(from, to) >= SYLLABLE_DEPTH_DB && peaks == 0) peaks++
        return peaks
    }

    private fun smoothedMin(from: Int, to: Int): Float {
        var low = Float.MAX_VALUE
        for (i in from..to) low = min(low, smoothed(i))
        return low
    }

    private fun smoothed(index: Int): Float {
        val a = levelDb[max(0, index - 1)]
        val b = levelDb[index]
        val c = levelDb[min(frames - 1, index + 1)]
        return (a + b + c) / 3f
    }

    private fun grow() {
        val size = levelDb.size * 2
        levelDb = levelDb.copyOf(size)
        active = active.copyOf(size)
        voiced = voiced.copyOf(size)
        pitchLag = pitchLag.copyOf(size)
    }

    companion object {
        /** Recorded with every analysis so results from different detectors never mix. */
        const val VERSION = "voice-v1"

        private const val MILLIS_PER_SECOND = 1000
        private const val TARGET_RATE_HZ = 8_000
        private const val MIN_SAMPLE_RATE_HZ = 8_000
        private const val MIN_FRAME_MS = 20
        private const val MAX_FRAME_MS = 100
        private const val MIN_PITCH_HZ = 70
        private const val MAX_PITCH_HZ = 400
        private const val MIN_OVERLAP = 32
        private const val PRE_EMPHASIS = 0.95
        private const val VOICED_CORRELATION = 0.45
        private const val WINDOW_MS = 1_000
        private const val MIN_VOICED_FRAMES = 3
        private const val MIN_FRAMES_FOR_TONE_TEST = 20
        private const val TONE_VOICED_FRACTION = 0.92
        private const val TONE_PITCH_SPREAD = 0.04
        private const val SYLLABLE_DEPTH_DB = 4f
        private const val INITIAL_FRAMES = 2_048
    }
}
