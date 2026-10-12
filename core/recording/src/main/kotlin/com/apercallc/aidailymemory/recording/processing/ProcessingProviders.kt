package com.apercallc.aidailymemory.recording.processing

/**
 * One transcribed span: a contiguous chunk of text with timing, optional speaker label, and
 * optional confidence. Mirrors the backend's `TranscribedSpan` (ADR-0018) for the on-device
 * path (ADR-0020).
 *
 * Times are milliseconds from the start of the audio clip that was transcribed.
 */
data class TranscribedSpan(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Double? = null,
    val speaker: String? = null,
)

/**
 * The full result of transcribing one audio clip. Mirrors the backend's `TranscriptResult`.
 */
data class TranscriptResult(
    val language: String?,
    val spans: List<TranscribedSpan>,
)

/**
 * One extracted memory item from a session's transcript. Mirrors the backend's `ExtractedItem`
 * (ADR-0019) for the on-device path (ADR-0020).
 *
 * @param kind one of: "decision", "action", "commitment", "idea", "question".
 * @param confidence one of: "confirmed", "likely", "possible".
 * @param evidenceSegmentIndices 0-based indices into the numbered transcript lines passed to
 *   the provider; validated against the real segment count before anything is trusted.
 * @param quote a short evidence quote (≤300 chars), or null.
 * @param owner the person responsible for an action item, or null for other kinds.
 * @param due a due date for an action or commitment, or null.
 * @param direction "i_owe" or "owed_to_me" for commitments, null for other kinds.
 * @param counterpart the other party in a commitment, null for other kinds.
 */
data class ExtractedItem(
    val kind: String,
    val text: String,
    val confidence: String,
    val evidenceSegmentIndices: List<Int>,
    val quote: String? = null,
    val owner: String? = null,
    val due: String? = null,
    val direction: String? = null,
    val counterpart: String? = null,
)

/**
 * The full extraction result for one session.
 */
data class ExtractionResult(
    val items: List<ExtractedItem>,
)

/**
 * One transcript line handed to the extraction provider, numbered so its response can point
 * back at specific segments without echoing full timestamps. Mirrors the backend's `Numbered`.
 */
data class NumberedLine(
    val index: Int,
    val speaker: String?,
    val text: String,
)

/**
 * A failure with a user-visible code; [retryable] decides retry vs dead-letter.
 * Mirrors the backend's `JobError`.
 */
class ProcessingException(
    val code: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(code, cause)

/**
 * Transcribes audio clips into text. The on-device equivalent of the backend's
 * `TranscriptionProvider` Protocol (ADR-0018, superseded by ADR-0020).
 *
 * Implementations:
 * - On-device Whisper / ONNX Runtime (free, offline, the default).
 * - User-supplied API key (Deepgram, OpenAI, etc. — the phone calls the provider directly).
 */
interface TranscriptionProvider {
    val name: String
    val model: String

    /**
     * Transcribes [audio] bytes, which are AAC-LC ADTS frames at [sampleRateHz].
     * Raises [ProcessingException] for provider failures.
     */
    suspend fun transcribe(
        audio: ByteArray,
        codec: String,
        sampleRateHz: Int,
    ): TranscriptResult
}

/**
 * Extracts structured memory items from a session's transcript. The on-device equivalent of
 * the backend's `ExtractionProvider` Protocol (ADR-0019, superseded by ADR-0020).
 *
 * Transcript text is untrusted model input (README "Untrusted Content"): it is delimited as
 * data inside the prompt, the provider gets no tool access, and every field of the response is
 * validated before anything is persisted.
 */
interface ExtractionProvider {
    val name: String
    val model: String

    /**
     * Extracts memory items from [lines], which are the session's transcript lines in order.
     * Raises [ProcessingException] for provider failures.
     */
    suspend fun extract(lines: List<NumberedLine>): ExtractionResult
}