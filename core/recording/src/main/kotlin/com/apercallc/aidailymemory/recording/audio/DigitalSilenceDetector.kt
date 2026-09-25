package com.apercallc.aidailymemory.recording.audio

/**
 * Detects a microphone that delivers exact digital zeros, which is how a blocked or revoked
 * microphone commonly looks (ADR-0010).
 *
 * A real quiet room still has an analog noise floor, so it is never a long run of exact
 * zeros. This does not detect "quiet"; it only detects "not actually capturing".
 *
 * Runs on the audio thread for every buffer, so it is allocation-free, and it scans each
 * buffer from the end: with live audio, the first sample checked is almost always non-zero.
 * Not thread-safe; use one instance per capture stream.
 */
class DigitalSilenceDetector(sampleRateHz: Int, thresholdMs: Long, channelCount: Int = 1) {
    private val thresholdSamples: Long
    private var zeroRunSamples = 0L

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(thresholdMs > 0) { "thresholdMs must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        // Counted in interleaved samples, so stereo needs twice as many zeros for the same time.
        thresholdSamples = sampleRateHz.toLong() * channelCount * thresholdMs / MILLIS_PER_SECOND
        require(thresholdSamples > 0) { "threshold is shorter than one sample" }
    }

    /** True once the current run of exact zeros reaches the threshold. */
    val isSilent: Boolean get() = zeroRunSamples >= thresholdSamples

    /**
     * Feeds the first [count] interleaved 16-bit PCM samples of [samples] (a blocked mic zeroes
     * every channel). Returns [isSilent].
     */
    fun process(samples: ShortArray, count: Int = samples.size): Boolean {
        require(count in 0..samples.size) { "count $count outside buffer of ${samples.size}" }
        var index = count - 1
        while (index >= 0 && samples[index].toInt() == 0) index--
        zeroRunSamples = if (index < 0) {
            zeroRunSamples + count
        } else {
            (count - 1 - index).toLong()
        }
        return isSilent
    }

    fun reset() {
        zeroRunSamples = 0
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
