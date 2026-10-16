package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TranscriptChunkerTest {
    private fun line(index: Int, chars: Int) = NumberedLine(index, null, "a".repeat(chars))

    @Test
    fun `no lines gives no chunks`() {
        assertEquals(emptyList(), TranscriptChunker.chunk(emptyList()))
    }

    @Test
    fun `lines that fit the budget stay in one chunk`() {
        val lines = listOf(line(0, 10), line(1, 10))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(listOf(lines), chunks)
    }

    @Test
    fun `a budget exceeded by the next line starts a new chunk`() {
        val lines = listOf(line(0, 60), line(1, 60))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(listOf(listOf(lines[0]), listOf(lines[1])), chunks)
    }

    @Test
    fun `a line exactly at the remaining budget still joins the chunk`() {
        val lines = listOf(line(0, 40), line(1, 60))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(listOf(lines), chunks)
    }

    @Test
    fun `a single line longer than the budget becomes its own chunk, never dropped`() {
        val lines = listOf(line(0, 500))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(listOf(lines), chunks)
    }

    @Test
    fun `an oversized line does not swallow the lines around it`() {
        val lines = listOf(line(0, 10), line(1, 500), line(2, 10))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(
            listOf(listOf(lines[0]), listOf(lines[1]), listOf(lines[2])),
            chunks,
        )
    }

    @Test
    fun `line indices are preserved exactly, never renumbered`() {
        val lines = listOf(line(5, 60), line(9, 60))

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(listOf(5, 9), chunks.flatten().map { it.index })
    }

    @Test
    fun `every line is present across the chunks, in original order`() {
        val lines = (0 until 10).map { line(it, 30) }

        val chunks = TranscriptChunker.chunk(lines, maxCharsPerChunk = 100)

        assertEquals(lines, chunks.flatten())
    }

    @Test
    fun `a non-positive budget is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TranscriptChunker.chunk(listOf(line(0, 1)), maxCharsPerChunk = 0)
        }
    }
}
