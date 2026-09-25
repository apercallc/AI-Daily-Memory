package com.apercallc.aidailymemory.analysis

import android.media.MediaCodec
import android.media.MediaFormat
import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import java.io.IOException
import java.nio.ByteOrder

/** Receives decoded mono 16-bit PCM; [samples] is reused between calls. */
fun interface PcmSink {
    fun onPcm(samples: ShortArray, count: Int)
}

/** Turns a segment's ADTS stream back into PCM. A seam so analysis is testable off-device. */
fun interface PcmDecoder {
    /** @throws IOException if the stream can't be decoded. */
    fun decode(adts: ByteArray, sampleRateHz: Int, channelCount: Int, sink: PcmSink)
}

/**
 * Hardware/software AAC decoder via MediaCodec, synchronous mode, fed one ADTS frame per input
 * buffer. Runs only in the charging-time analysis job (ADR-0015), never on the capture path.
 * Multi-channel output is mixed down to mono, which is all speech detection needs.
 */
class MediaCodecAacDecoder : PcmDecoder {
    override fun decode(adts: ByteArray, sampleRateHz: Int, channelCount: Int, sink: PcmSink) {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: IllegalArgumentException) {
            throw IOException("no AAC decoder", e)
        }
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRateHz,
                channelCount,
            ).apply { setInteger(MediaFormat.KEY_IS_ADTS, 1) }
            codec.configure(format, null, null, 0)
            codec.start()
            Session(codec, sink, sampleRateHz).run(adts)
        } catch (e: MediaCodec.CodecException) {
            throw IOException("decode failed", e)
        } catch (e: IllegalStateException) {
            throw IOException("decode failed", e)
        } finally {
            codec.release()
        }
    }

    private class Session(
        private val codec: MediaCodec,
        private val sink: PcmSink,
        private val sampleRateHz: Int,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var outputChannels = 1
        private var scratch = ShortArray(SCRATCH_SAMPLES)
        private var mono = ShortArray(SCRATCH_SAMPLES)
        private var framesQueued = 0L
        private var done = false

        fun run(adts: ByteArray) {
            AdtsScanner.scan(adts) { offset, size -> queue(adts, offset, size, endOfStream = false) }
            queue(adts, 0, 0, endOfStream = true)
            var idle = 0
            while (!done) {
                idle = if (drain()) 0 else idle + 1
                if (idle > MAX_IDLE_POLLS) throw IOException("decoder stalled")
            }
        }

        private fun queue(data: ByteArray, offset: Int, size: Int, endOfStream: Boolean) {
            var index: Int
            var idle = 0
            while (true) {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) break
                drain()
                if (++idle > MAX_IDLE_POLLS) throw IOException("decoder input stalled")
            }
            val buffer = codec.getInputBuffer(index) ?: throw IOException("no input buffer")
            buffer.clear()
            buffer.put(data, offset, size)
            val ptsUs = framesQueued * SAMPLES_PER_FRAME * MICROS / sampleRateHz
            val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            codec.queueInputBuffer(index, 0, size, ptsUs, flags)
            if (!endOfStream) framesQueued++
        }

        /** Handles at most one output event; returns true if it made progress. */
        private fun drain(): Boolean {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputChannels = codec.outputFormat
                        .getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        .coerceAtLeast(1)
                    return true
                }
                index < 0 -> return false
            }
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val count = shorts.remaining()
                if (scratch.size < count) scratch = ShortArray(count)
                shorts.get(scratch, 0, count)
                emit(count)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            return true
        }

        private fun emit(count: Int) {
            if (outputChannels == 1) {
                sink.onPcm(scratch, count)
                return
            }
            val frames = count / outputChannels
            if (mono.size < frames) mono = ShortArray(frames)
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until outputChannels) sum += scratch[f * outputChannels + c]
                mono[f] = (sum / outputChannels).toShort()
            }
            sink.onPcm(mono, frames)
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 500
        const val SAMPLES_PER_FRAME = 1024L
        const val MICROS = 1_000_000L
        const val SCRATCH_SAMPLES = 4096
    }
}
