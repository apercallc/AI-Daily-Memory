package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import com.apercallc.aidailymemory.recording.processing.TranscriptResult

/** Default provider when none is configured — refuses to process (ADR-0020). */
class StubTranscriptionProvider : TranscriptionProvider {
    override val name: String = "disabled"
    override val model: String = "none"

    override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
        throw ProcessingException("processing_disabled", retryable = false)
    }
}