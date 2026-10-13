package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyProvidersTest {
    private fun extraction(model: String = "gpt-x") =
        ApiKeyExtractionProvider("https://llm.example/v1/chat", "key", model)

    private val transcription = ApiKeyTranscriptionProvider("https://asr.example/v1", "key", "m")

    @Test
    fun `request body escapes a hostile model name and prompt`() {
        val model = "x\",\"stream\":true,\"y\":\"\n"
        val body = extraction(model).requestBody("line1\n\"quoted\" \\ end")

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(model, root["model"]!!.jsonPrimitive.content)
        assertFalse(root.containsKey("stream"))
        assertEquals(
            "line1\n\"quoted\" \\ end",
            root["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `prompt numbers lines and includes the speaker`() {
        val prompt = extraction().buildPrompt(listOf(NumberedLine(0, "A", "hello"), NumberedLine(1, null, "bye")))
        assertTrue(prompt.contains("0: [A] hello"))
        assertTrue(prompt.contains("1: bye"))
    }

    private fun chat(content: String): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
            },
        )
    }.toString()

    @Test
    fun `extraction parses fenced json and skips incomplete items`() {
        val content = """
            Sure:
            ```json
            [{"kind":"action","text":"Send deck","confidence":"likely","evidence_segment_indices":[0,2],"owner":"Sam"},
             {"kind":"idea"}]
            ```
        """.trimIndent()

        val items = extraction().parseResponse(chat(content))

        assertEquals(1, items.size)
        assertEquals(1, extraction().also { it.parseResponse(chat(content)) }.droppedItems)
        assertEquals("action", items[0].kind)
        assertEquals(listOf(0, 2), items[0].evidenceSegmentIndices)
        assertEquals("Sam", items[0].owner)
        assertNull(items[0].quote)
    }

    @Test
    fun `an extraction where every item is malformed fails instead of looking empty`() {
        val e = assertFailsWith<ProcessingException> {
            extraction().parseResponse(chat("""[{"kind":"idea"},7,"x"]"""))
        }
        assertEquals("invalid_extraction_result", e.code)
        assertFalse(e.retryable)
    }

    @Test
    fun `extraction rejects a response without a json array`() {
        val e = assertFailsWith<ProcessingException> { extraction().parseResponse(chat("no list here")) }
        assertEquals("invalid_extraction_result", e.code)
        assertFalse(e.retryable)
    }

    @Test
    fun `extraction rejects a response without choices`() {
        assertFailsWith<ProcessingException> { extraction().parseResponse("""{"x":1}""") }
    }

    @Test
    fun `transcription groups words by speaker`() {
        val response = """{"language":"en","results":{"alternatives":[{"words":[
            {"word":"hi","start":0.0,"end":0.5,"speaker":"1","confidence":0.9},
            {"word":"there","start":0.5,"end":1.0,"speaker":"1","confidence":0.8},
            {"word":"yo","start":1.0,"end":1.5,"speaker":"2","confidence":0.7}]}]}}"""

        val result = transcription.parseResponse(response)

        assertEquals("en", result.language)
        assertEquals(2, result.spans.size)
        assertEquals("hi there", result.spans[0].text)
        assertEquals("1", result.spans[0].speaker)
        assertEquals(1_000L, result.spans[0].endMs)
        assertEquals("yo", result.spans[1].text)
    }

    @Test
    fun `transcription falls back to the transcript field`() {
        val result = transcription.parseResponse("""{"results":{"alternatives":[{"transcript":"whole text"}]}}""")
        assertEquals(listOf("whole text"), result.spans.map { it.text })
    }

    @Test
    fun `transcription rejects a response with no alternatives`() {
        val e = assertFailsWith<ProcessingException> { transcription.parseResponse("""{"results":{}}""") }
        assertEquals("invalid_provider_result", e.code)
    }
}
