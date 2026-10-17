package com.apercallc.aidailymemory.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.apercallc.aidailymemory.platform.logFailure
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synchronous AAC-LC encoder over MediaCodec. Used only from the capture thread.
 *
 * Emits raw access units to [sink]; the segment writer adds ADTS headers, which keeps the
 * container append-safe (ADR-0007). Codec-config buffers (the AudioSpecificConfig) are dropped
 * because ADTS carries that information in every frame header.
 */
class AacEncoder(
    private val sampleRateHz: Int,
    channelCount: Int,
    bitRate: Int,
    maxInputBytes: Int,
) : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val info = MediaCodec.BufferInfo()
    private var presentationSamples = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MIME, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputBytes)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        } catch (e: IllegalArgumentException) {
            codec.release()
            throw e
        }
    }

    /** Feeds [count] PCM-16 samples, draining any finished frames into [sink]. */
    fun encode(pcm: ShortArray, count: Int, sink: (ByteBuffer, Int) -> Unit) {
        var offset = 0
        var stalledRounds = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                // A wedged codec must fail loudly, never spin the capture thread forever.
                check(++stalledRounds < MAX_STALLED_ROUNDS) { "encoder stalled" }
                // Encoder is full: free output space, then retry this chunk.
                drain(sink, endOfStream = false)
                continue
            }
            stalledRounds = 0
            val input = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.nativeOrder())
            input.clear()
            val samples = minOf(count - offset, input.remaining() / BYTES_PER_SAMPLE)
            input.asShortBuffer().put(pcm, offset, samples)
            codec.queueInputBuffer(index, 0, samples * BYTES_PER_SAMPLE, timestampUs(), 0)
            presentationSamples += samples
            offset += samples
        }
        drain(sink, endOfStream = false)
    }

    /** Signals end of stream and drains every remaining frame into [sink]. */
    fun finish(sink: (ByteBuffer, Int) -> Unit) {
        val index = codec.dequeueInputBuffer(FINISH_TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, timestampUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(sink, endOfStream = true)
        } else {
            drain(sink, endOfStream = false)
        }
    }

    private fun drain(sink: (ByteBuffer, Int) -> Unit, endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) FINISH_TIMEOUT_US else 0)
            when {
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val output = checkNotNull(codec.getOutputBuffer(index))
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        sink(output, info.size)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Bounded: a stuck codec at shutdown loses at most its last frames.
                    if (!endOfStream || ++idleRounds >= MAX_FINISH_ROUNDS) return
                }
                // INFO_OUTPUT_FORMAT_CHANGED carries nothing ADTS needs.
                else -> Unit
            }
        }
    }

    private fun timestampUs(): Long = presentationSamples * MICROS / sampleRateHz

    override fun close() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or errored; release below still frees the codec.
            logFailure(TAG, "encoder_stop_failed", e, warning = true)
        }
        codec.release()
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TAG = "AacEncoder"
        private const val BYTES_PER_SAMPLE = 2
        private const val MICROS = 1_000_000L
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val FINISH_TIMEOUT_US = 20_000L
        private const val MAX_FINISH_ROUNDS = 25
        private const val MAX_STALLED_ROUNDS = 100
    }
}
