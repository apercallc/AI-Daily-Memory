package com.apercallc.aidailymemory.recording.audio

/**
 * ADTS framing for AAC-LC (ISO/IEC 13818-7, 14496-3).
 *
 * Each frame carries its own header, so a segment is decodable up to its last complete frame
 * even if the process dies mid-write. That is why ADR-0007 allows ADTS but not MP4, whose
 * index is only written at the end.
 */
class AdtsHeaderWriter(sampleRateHz: Int, private val channelCount: Int) {
    private val frequencyIndex: Int = SAMPLE_RATES.indexOf(sampleRateHz)

    init {
        require(frequencyIndex >= 0) { "unsupported ADTS sample rate $sampleRateHz" }
        require(channelCount in 1..MAX_CHANNEL_CONFIG) { "channelCount must be 1..7" }
    }

    /**
     * Writes the 7-byte header for a frame with [payloadSize] bytes of raw AAC into [target] at
     * [offset]. Allocation-free: it runs once per encoded frame.
     */
    fun write(payloadSize: Int, target: ByteArray, offset: Int = 0) {
        val frameLength = payloadSize + HEADER_SIZE
        require(payloadSize > 0 && frameLength <= MAX_FRAME_LENGTH) {
            "payloadSize $payloadSize does not fit an ADTS frame"
        }
        require(offset >= 0 && offset + HEADER_SIZE <= target.size) { "target too small" }
        // Sync word, MPEG-4, layer 0, no CRC.
        target[offset] = 0xFF.toByte()
        target[offset + 1] = 0xF1.toByte()
        target[offset + 2] = (
            (AAC_LC_PROFILE shl 6) or (frequencyIndex shl 2) or (channelCount shr 2)
            ).toByte()
        target[offset + 3] = (((channelCount and 0x3) shl 6) or (frameLength shr 11)).toByte()
        target[offset + 4] = ((frameLength shr 3) and 0xFF).toByte()
        // Low 3 bits of the length, then buffer fullness 0x7FF (variable bit rate).
        target[offset + 5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
        target[offset + 6] = 0xFC.toByte()
    }

    companion object {
        const val HEADER_SIZE = 7
        const val MAX_FRAME_LENGTH = 0x1FFF
        private const val AAC_LC_PROFILE = 1 // audio object type 2, minus one
        private const val MAX_CHANNEL_CONFIG = 7

        /** Index = ADTS sampling_frequency_index. */
        internal val SAMPLE_RATES = intArrayOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
            24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
        )
    }
}

/** Result of scanning a (possibly truncated) ADTS stream. */
data class AdtsScan(
    val completeFrames: Int,
    /** Bytes covered by complete frames. Anything after this is a torn tail. */
    val validBytes: Int,
    /** Sample rate from the first header, or null if there was no complete frame. */
    val sampleRateHz: Int?,
    /** True when bytes after the last complete frame do not start a valid header. */
    val corrupt: Boolean,
) {
    /** Audio duration of the complete frames (AAC-LC: 1024 samples per frame). */
    val durationMs: Long
        get() = if (sampleRateHz == null) {
            0
        } else {
            completeFrames.toLong() * SAMPLES_PER_FRAME * MILLIS_PER_SECOND / sampleRateHz
        }

    private companion object {
        const val SAMPLES_PER_FRAME = 1024L
        const val MILLIS_PER_SECOND = 1000L
    }
}

object AdtsScanner {
    /**
     * Walks frame headers in the first [length] bytes of [data]. A torn final frame (the
     * crash case) is not corruption; garbage where a header should start is.
     */
    fun scan(data: ByteArray, length: Int = data.size): AdtsScan {
        require(length in 0..data.size) { "length outside buffer" }
        var position = 0
        var frames = 0
        var sampleRate: Int? = null
        var corrupt = false
        while (position < length) {
            val remaining = length - position
            if (remaining < AdtsHeaderWriter.HEADER_SIZE) {
                corrupt = !isSyncPrefix(data, position, remaining)
                break
            }
            if (!isSync(data, position)) {
                corrupt = true
                break
            }
            val frameLength = frameLength(data, position)
            val rateIndex = (data[position + 2].toInt() and 0x3C) shr 2
            if (frameLength < AdtsHeaderWriter.HEADER_SIZE ||
                rateIndex >= AdtsHeaderWriter.SAMPLE_RATES.size
            ) {
                corrupt = true
                break
            }
            if (frameLength > remaining) break // torn tail
            if (sampleRate == null) sampleRate = AdtsHeaderWriter.SAMPLE_RATES[rateIndex]
            frames++
            position += frameLength
        }
        return AdtsScan(frames, position, sampleRate, corrupt)
    }

    private fun isSync(data: ByteArray, at: Int): Boolean =
        data[at].toInt() and 0xFF == 0xFF && data[at + 1].toInt() and 0xF6 == 0xF0

    private fun isSyncPrefix(data: ByteArray, at: Int, available: Int): Boolean = when {
        available >= 2 -> isSync(data, at)
        else -> data[at].toInt() and 0xFF == 0xFF
    }

    private fun frameLength(data: ByteArray, at: Int): Int =
        ((data[at + 3].toInt() and 0x03) shl 11) or
            ((data[at + 4].toInt() and 0xFF) shl 3) or
            ((data[at + 5].toInt() and 0xE0) shr 5)
}
