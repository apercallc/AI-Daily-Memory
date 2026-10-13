package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.analysis.PcmDecoder
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OnDeviceTranscriptionProviderTest {
    private class FakeRecognizer(private val words: List<RecognizedWord>) : WordRecognizer {
        var heard = 0
        var closed = false
        override fun accept(pcm: ShortArray, count: Int) {
            heard += count
        }
        override fun finish() = words
        override fun close() {
            closed = true
        }
    }

    private class FakeFactory(private val recognizer: WordRecognizer?, private val failure: ProcessingException? = null) :
        WordRecognizerFactory {
        var closed = false
        override fun open(): WordRecognizer = failure?.let { throw it } ?: recognizer!!
        override fun close() {
            closed = true
        }
    }

    private val decoder = PcmDecoder { _, _, _, sink -> sink.onPcm(ShortArray(1_600), 1_600) }

    private fun provider(factory: WordRecognizerFactory, decoder: PcmDecoder = this.decoder) =
        OnDeviceTranscriptionProvider(decoder, factory, model = "m", language = "en")

    @Test
    fun `decoded audio reaches the recognizer and its words come back as lines`() = runTest {
        val recognizer = FakeRecognizer(listOf(RecognizedWord("hi", 0, 300, 0.9), RecognizedWord("there", 400, 800, 0.8)))

        val result = provider(FakeFactory(recognizer)).transcribe(ByteArray(8), "aac-lc-adts", 16_000)

        assertEquals(1_600, recognizer.heard)
        assertEquals("en", result.language)
        assertEquals("hi there", result.spans.single().text)
        assertTrue(recognizer.closed)
    }

    @Test
    fun `silence gives an empty transcript, not an error`() = runTest {
        val result = provider(FakeFactory(FakeRecognizer(emptyList()))).transcribe(ByteArray(8), "aac-lc-adts", 16_000)

        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `a sample rate the model cannot read is refused for good`() = runTest {
        val e = assertFailsWith<ProcessingException> {
            provider(FakeFactory(FakeRecognizer(emptyList()))).transcribe(ByteArray(8), "aac-lc-adts", 44_100)
        }

        assertEquals("unsupported_sample_rate", e.code)
        assertFalse(e.retryable)
    }

    @Test
    fun `audio that cannot be decoded is a permanent failure and the recognizer is still released`() = runTest {
        val recognizer = FakeRecognizer(emptyList())
        val broken = PcmDecoder { _, _, _, _ -> throw IOException("bad frame") }

        val e = assertFailsWith<ProcessingException> {
            provider(FakeFactory(recognizer), broken).transcribe(ByteArray(8), "aac-lc-adts", 16_000)
        }

        assertEquals("audio_undecodable", e.code)
        assertFalse(e.retryable)
        assertTrue(recognizer.closed)
    }

    @Test
    fun `a model that is missing is passed on as retryable so the audio waits`() = runTest {
        val factory = FakeFactory(null, ProcessingException("speech_model_missing", retryable = true))

        val e = assertFailsWith<ProcessingException> { provider(factory).transcribe(ByteArray(8), "aac-lc-adts", 16_000) }

        assertTrue(e.retryable)
    }

    @Test
    fun `closing the provider releases the loaded model`() {
        val factory = FakeFactory(FakeRecognizer(emptyList()))

        provider(factory).close()

        assertTrue(factory.closed)
    }
}
