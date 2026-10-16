package com.apercallc.aidailymemory.recording.processing

/**
 * Splits a session's numbered transcript lines into bounded chunks before extraction (TODO.md
 * "Keep reports grounded and bound processing context"): a very long session (hours of
 * meeting) must never go to a provider as one unbounded prompt, and chunking here — not a
 * second summarization pass over an already-generated summary — is how this avoids
 * summary-of-summary drift. Line [NumberedLine.index] values are preserved exactly as given,
 * never renumbered, so an extracted item's evidence_segment_indices still points at the right
 * line after chunking.
 *
 * A single line longer than [maxCharsPerChunk] is never split or dropped; it becomes its own
 * oversized chunk rather than losing evidence.
 */
object TranscriptChunker {
    const val DEFAULT_MAX_CHARS_PER_CHUNK = 12_000

    fun chunk(
        lines: List<NumberedLine>,
        maxCharsPerChunk: Int = DEFAULT_MAX_CHARS_PER_CHUNK,
    ): List<List<NumberedLine>> {
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk must be positive" }
        if (lines.isEmpty()) return emptyList()

        val chunks = mutableListOf<MutableList<NumberedLine>>()
        var currentChars = 0
        for (line in lines) {
            val lineChars = line.text.length
            val current = chunks.lastOrNull()
            if (current != null && currentChars + lineChars <= maxCharsPerChunk) {
                current.add(line)
                currentChars += lineChars
            } else {
                chunks += mutableListOf(line)
                currentChars = lineChars
            }
        }
        return chunks
    }
}
