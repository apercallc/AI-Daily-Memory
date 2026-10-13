package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ExtractedItem
import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionResult
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls a user-configured LLM API (OpenAI-compatible chat completions) to extract structured
 * memory items from transcript lines (ADR-0020). The user's API key is stored on the phone only
 * and sent directly to the provider — never to us.
 *
 * Transcript text is untrusted model input: it is delimited as data inside the prompt, the
 * provider gets no tool access, and every field of the response is validated by
 * [com.apercallc.aidailymemory.recording.processing.ProcessingValidation] before persistence.
 */
class ApiKeyExtractionProvider(
    private val apiUrl: String,
    private val apiKey: String,
    override val model: String,
) : ExtractionProvider {

    override val name: String = "user-key"

    override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
        val prompt = buildPrompt(lines)
        val response = callApi(prompt)
        val items = parseResponse(response)
        return ExtractionResult(items)
    }

    internal fun buildPrompt(lines: List<NumberedLine>): String {
        val numbered = lines.joinToString("\n") { (i, speaker, text) ->
            val sp = speaker?.let { "[$it] " } ?: ""
            "$i: $sp$text"
        }
        return """
            You are a memory extraction assistant. Extract decisions, action items, commitments,
            ideas, and open questions from the following transcript lines. Return a JSON array
            where each item has: kind (one of: decision, action, commitment, idea, question),
            text (concise summary, max 280 chars), confidence (one of: confirmed, likely, possible),
            evidence_segment_indices (array of 0-based line numbers that support this item),
            quote (optional, max 280 chars, exact words from the transcript),
            owner (optional, for action items only, who is responsible),
            due (optional, ISO date for action/commitment),
            direction (optional, for commitments only: "i_owe" or "owed_to_me"),
            counterpart (optional, for commitments only, the other party).

            Transcript:
            $numbered

            Return only the JSON array, no prose.
        """.trimIndent()
    }

    private fun callApi(prompt: String): String {
        val url = URL(apiUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(requestBody(prompt).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 500..599) throw ProcessingException("provider_server_error", retryable = true)
            if (code in 400..499) throw ProcessingException("provider_client_error", retryable = false)
            if (code != 200) throw ProcessingException("provider_unexpected", retryable = false)
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: java.net.SocketTimeoutException) {
            throw ProcessingException("provider_timeout", retryable = true, cause = e)
        } catch (e: ProcessingException) {
            throw e
        } catch (e: Exception) {
            throw ProcessingException("provider_error", retryable = true, cause = e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseResponse(response: String): List<ExtractedItem> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw ProcessingException("invalid_extraction_result", retryable = false)

        // Extract the JSON array from the response (may be wrapped in markdown code fences)
        val jsonStr = extractJsonArray(content)
        val items = try {
            json.parseToJsonElement(jsonStr).jsonArray
        } catch (e: Exception) {
            throw ProcessingException("invalid_extraction_result", retryable = false, cause = e)
        }

        return items.mapNotNull { element ->
            val obj = element.jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val evidence = obj["evidence_segment_indices"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
            ExtractedItem(
                kind = kind,
                text = text,
                confidence = confidence,
                evidenceSegmentIndices = evidence,
                quote = obj["quote"]?.jsonPrimitive?.contentOrNull,
                owner = obj["owner"]?.jsonPrimitive?.contentOrNull,
                due = obj["due"]?.jsonPrimitive?.contentOrNull,
                direction = obj["direction"]?.jsonPrimitive?.contentOrNull,
                counterpart = obj["counterpart"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end < 0 || end <= start) {
            throw ProcessingException("invalid_extraction_result", retryable = false)
        }
        return text.substring(start, end + 1)
    }

    /** Built with a JSON writer so a model name or transcript can never break out of the string. */
    internal fun requestBody(prompt: String): String = buildJsonObject {
        put("model", model)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    },
                )
            },
        )
        put("temperature", 0.1)
    }.toString()

    companion object {
        private const val TIMEOUT_MS = 60_000
    }
}