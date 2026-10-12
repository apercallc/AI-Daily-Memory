package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionResult
import com.apercallc.aidailymemory.recording.processing.ProcessingException

/** Default provider when none is configured — refuses to extract (ADR-0020). */
class StubExtractionProvider : ExtractionProvider {
    override val name: String = "disabled"
    override val model: String = "none"

    override suspend fun extract(lines: List<com.apercallc.aidailymemory.recording.processing.NumberedLine>): ExtractionResult {
        throw ProcessingException("processing_disabled", retryable = false)
    }
}