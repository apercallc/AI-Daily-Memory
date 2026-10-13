package com.apercallc.aidailymemory.ondevice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordGrouperTest {
    private fun word(text: String, start: Long, end: Long, conf: Double? = 1.0) =
        RecognizedWord(text, start, end, conf)

    @Test
    fun `no words give no lines`() {
        assertTrue(WordGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `words close together form one line with the span of the whole line`() {
        val spans = WordGrouper.group(listOf(word("hello", 0, 400), word("there", 500, 900), word("friend", 1_000, 1_600)))

        val line = spans.single()
        assertEquals("hello there friend", line.text)
        assertEquals(0L, line.startMs)
        assertEquals(1_600L, line.endMs)
        assertNull(line.speaker)
    }

    @Test
    fun `a pause longer than the limit starts a new line`() {
        val spans = WordGrouper.group(
            listOf(word("first", 0, 400), word("second", 400 + WordGrouper.PAUSE_MS + 1, 3_000)),
        )

        assertEquals(listOf("first", "second"), spans.map { it.text })
    }

    @Test
    fun `a pause exactly at the limit stays in the same line`() {
        val spans = WordGrouper.group(listOf(word("a", 0, 100), word("b", 100 + WordGrouper.PAUSE_MS, 2_000)))

        assertEquals(1, spans.size)
    }

    @Test
    fun `a long run of words is cut into lines by word count`() {
        val words = (0 until WordGrouper.MAX_WORDS + 5).map { word("w$it", it * 100L, it * 100L + 50) }

        val spans = WordGrouper.group(words)

        assertEquals(2, spans.size)
        assertEquals(WordGrouper.MAX_WORDS, spans.first().text.split(" ").size)
    }

    @Test
    fun `a long run of characters is cut into lines by length`() {
        val big = "x".repeat(100)
        val words = (0 until 6).map { word(big, it * 100L, it * 100L + 50) }

        val spans = WordGrouper.group(words)

        assertTrue(spans.all { it.text.length <= WordGrouper.MAX_CHARS })
        assertTrue(spans.size > 1)
    }

    @Test
    fun `words given out of order are placed by time`() {
        val spans = WordGrouper.group(listOf(word("second", 600, 900), word("first", 0, 500)))

        assertEquals("first second", spans.single().text)
    }

    @Test
    fun `confidence is the average of the words and stays inside zero to one`() {
        val spans = WordGrouper.group(listOf(word("a", 0, 100, 0.5), word("b", 100, 200, 1.0), word("c", 200, 300, null)))

        assertEquals(0.75, spans.single().confidence!!, 1e-9)
    }

    @Test
    fun `no confidence at all stays unknown`() {
        assertNull(WordGrouper.group(listOf(word("a", 0, 100, null))).single().confidence)
    }
}
