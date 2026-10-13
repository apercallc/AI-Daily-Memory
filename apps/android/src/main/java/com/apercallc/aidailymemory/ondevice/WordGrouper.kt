package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.recording.processing.TranscribedSpan

/** One recognized word with its position inside the audio, in milliseconds. */
data class RecognizedWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Double?)

/**
 * Turns a flat word list into readable transcript lines: a new line starts after a pause or when
 * a line gets long. The recognizer gives words only; the lines are what the person reads and what
 * evidence links point at.
 */
object WordGrouper {
    const val PAUSE_MS = 1_500L
    const val MAX_CHARS = 300
    const val MAX_WORDS = 50

    fun group(words: List<RecognizedWord>): List<TranscribedSpan> {
        val spans = mutableListOf<TranscribedSpan>()
        var current = mutableListOf<RecognizedWord>()
        var chars = 0
        fun flush() {
            if (current.isEmpty()) return
            val confidences = current.mapNotNull { it.confidence }
            spans += TranscribedSpan(
                startMs = current.first().startMs,
                endMs = current.last().endMs,
                text = current.joinToString(" ") { it.text },
                confidence = confidences.takeIf { it.isNotEmpty() }?.average()?.coerceIn(0.0, 1.0),
                speaker = null,
            )
            current = mutableListOf()
            chars = 0
        }
        for (word in words.sortedBy { it.startMs }) {
            val last = current.lastOrNull()
            if (last != null &&
                (word.startMs - last.endMs > PAUSE_MS || current.size >= MAX_WORDS || chars + word.text.length > MAX_CHARS)
            ) {
                flush()
            }
            current += word
            chars += word.text.length + 1
        }
        flush()
        return spans
    }
}
