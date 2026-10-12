package com.apercallc.aidailymemory.recording.processing

/**
 * Validation for provider results — rejects anything that would corrupt evidence or overflow
 * storage. A violation raises [ProcessingException] with `retryable = false`, matching the
 * backend's dead-letter behavior (ADR-0018, ADR-0019). Ported to Kotlin for the on-device path
 * (ADR-0020).
 */
object ProcessingValidation {

    // Provider timestamps may run slightly past the clip (rounding); more than this is invalid.
    private const val END_TOLERANCE_MS = 1_000L

    // Bounds from ADR-0019 and the report contract.
    private const val MAX_ITEM_TEXT = 280
    private const val MAX_QUOTE = 280
    private const val MAX_NAME = 80
    private const val MAX_EVIDENCE_PER_ITEM = 5
    private const val MAX_ITEMS_PER_SESSION = 50

    private val MEMORY_ITEM_KINDS = setOf("decision", "action", "commitment", "idea", "question")
    private val CONFIDENCE_LEVELS = setOf("confirmed", "likely", "possible")
    private val COMMITMENT_DIRECTIONS = setOf("i_owe", "owed_to_me")

    /**
     * Rejects a transcript result that would corrupt evidence: out-of-range or out-of-order
     * spans, or confidence outside [0, 1].
     */
    fun validateTranscript(result: TranscriptResult, durationMs: Long) {
        var previousStart = 0L
        for (span in result.spans) {
            if (span.startMs < 0 ||
                span.endMs < span.startMs ||
                span.endMs > durationMs + END_TOLERANCE_MS ||
                span.startMs < previousStart ||
                (span.confidence != null && span.confidence !in 0.0..1.0)
            ) {
                throw ProcessingException("invalid_provider_result", retryable = false)
            }
            previousStart = span.startMs
        }
    }

    /**
     * Rejects extraction output that would corrupt evidence or overflow storage: everything
     * must point at a real segment, every kind-specific field must be present exactly where
     * required, and every bounded field must fit.
     */
    fun validateExtraction(result: ExtractionResult, segmentCount: Int) {
        if (result.items.size > MAX_ITEMS_PER_SESSION) {
            throw ProcessingException("invalid_extraction_result", retryable = false)
        }
        for (item in result.items) {
            if (item.kind !in MEMORY_ITEM_KINDS || item.confidence !in CONFIDENCE_LEVELS) {
                throw ProcessingException("invalid_extraction_result", retryable = false)
            }
            if (item.text.length !in 1..MAX_ITEM_TEXT) {
                throw ProcessingException("invalid_extraction_result", retryable = false)
            }
            if (item.quote != null && item.quote.length > MAX_QUOTE) {
                throw ProcessingException("invalid_extraction_result", retryable = false)
            }
            if (item.owner != null && (item.kind != "action" || item.owner.length > MAX_NAME)) {
                throw ProcessingException("invalid_extraction_result", retryable = false)
            }
            val evidence = item.evidenceSegmentIndices
            if (evidence.isEmpty() ||
                evidence.size > MAX_EVIDENCE_PER_ITEM ||
                evidence.any { it < 0 || it >= segmentCount }
            ) {
                throw ProcessingException("invalid_extraction_result", retryable = false)
            }
            if (item.kind == "commitment") {
                if (item.direction !in COMMITMENT_DIRECTIONS ||
                    item.counterpart.isNullOrEmpty() ||
                    item.counterpart.length > MAX_NAME
                ) {
                    throw ProcessingException("invalid_extraction_result", retryable = false)
                }
            } else {
                if (item.direction != null || item.counterpart != null) {
                    throw ProcessingException("invalid_extraction_result", retryable = false)
                }
            }
        }
    }
}