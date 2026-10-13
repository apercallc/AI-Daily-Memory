package com.apercallc.aidailymemory.ondevice

import com.apercallc.aidailymemory.recording.processing.ProcessingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoskResultTest {
    @Test
    fun `words come out in milliseconds with their confidence`() {
        val json = """{"result":[{"conf":0.9,"end":1.25,"start":0.5,"word":"hello"},{"conf":1.0,"end":2.0,"start":1.5,"word":"world"}],"text":"hello world"}"""

        val words = VoskResult.words(json)

        assertEquals(listOf("hello", "world"), words.map { it.text })
        assertEquals(500L, words[0].startMs)
        assertEquals(1_250L, words[0].endMs)
        assertEquals(0.9, words[0].confidence)
    }

    @Test
    fun `a reply with no words is empty`() {
        assertTrue(VoskResult.words("""{"text":""}""").isEmpty())
        assertTrue(VoskResult.words("""{"partial":"hel"}""").isEmpty())
    }

    @Test
    fun `entries missing a word or a time are skipped rather than guessed`() {
        val json = """{"result":[{"start":0.1,"end":0.2},{"word":"ok"},{"word":" ","start":0,"end":1},{"word":"fine","start":0.2,"end":0.4}]}"""

        assertEquals(listOf("fine"), VoskResult.words(json).map { it.text })
    }

    @Test
    fun `an end before its start is lifted to the start and confidence is clamped`() {
        val word = VoskResult.words("""{"result":[{"word":"x","start":2.0,"end":1.0,"conf":7}]}""").single()

        assertEquals(2_000L, word.endMs)
        assertEquals(1.0, word.confidence)
    }

    @Test
    fun `text that is not JSON is a permanent provider failure`() {
        val e = assertFailsWith<ProcessingException> { VoskResult.words("not json") }

        assertEquals("invalid_provider_result", e.code)
    }
}
