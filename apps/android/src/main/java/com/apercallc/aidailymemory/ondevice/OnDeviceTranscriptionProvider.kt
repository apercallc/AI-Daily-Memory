package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.analysis.PcmDecoder
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TranscriptResult
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import java.io.IOException

/** Feeds 16 kHz mono PCM to a speech recognizer and returns the words it heard. */
interface WordRecognizer : AutoCloseable {
    fun accept(pcm: ShortArray, count: Int)
    fun finish(): List<RecognizedWord>
}

/** Opens a recognizer, or says why it cannot (model missing, no memory). */
interface WordRecognizerFactory : AutoCloseable {
    @Throws(ProcessingException::class)
    fun open(): WordRecognizer
}

/**
 * Free transcription on the phone (ADR-0026). Audio is decoded and recognized in this process;
 * nothing is sent anywhere. Segments are recorded at 16 kHz mono, the only rate the model reads.
 */
class OnDeviceTranscriptionProvider(
    private val decoder: PcmDecoder,
    private val recognizers: WordRecognizerFactory,
    override val model: String,
    private val language: String,
) : TranscriptionProvider, AutoCloseable {

    override val name: String = NAME

    override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
        if (sampleRateHz != MODEL_RATE_HZ) throw ProcessingException("unsupported_sample_rate", retryable = false)
        val words = recognizers.open().use { recognizer ->
            try {
                decoder.decode(audio, sampleRateHz, channelCount = 1) { samples, count ->
                    recognizer.accept(samples, count)
                }
            } catch (e: IOException) {
                throw ProcessingException("audio_undecodable", retryable = false, cause = e)
            }
            recognizer.finish()
        }
        return TranscriptResult(language, WordGrouper.group(words))
    }

    /** Releases the loaded model; the next [transcribe] loads it again. */
    override fun close() = recognizers.close()

    companion object {
        const val NAME = "on-device"
        const val MODEL_RATE_HZ = 16_000
    }
}
