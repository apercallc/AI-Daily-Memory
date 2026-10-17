package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ExtractedItem
import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionResult
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TokenUsage
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
    /** Optional bounded summary of currently-open actions/commitments (TODO.md "evaluate prior
     *  open action items as extraction context"), e.g. from [PriorItemsContext.describe]. Null
     *  by default — the prompt is unchanged from before this existed until a measurement shows
     *  it helps without adding false positives (docs/product/AI_EVALUATION.md). */
    private val priorItemsContext: String? = null,
) : ExtractionProvider {

    override val name: String = "user-key"
    override val billingProviderId: String? = ProviderBillingIdentity.fromUrl(apiUrl)
    override val isExternalProvider: Boolean = true

    override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
        val prompt = buildPrompt(lines)
        val response = callApi(prompt)
        val result = parseResult(response)
        if (droppedItems > 0) Log.w(TAG, "extraction_items_dropped count=$droppedItems")
        return result
    }

    internal fun parseResult(response: String): ExtractionResult =
        ExtractionResult(parseResponse(response), tokenUsage(response))

    private fun tokenUsage(response: String): TokenUsage? {
        val root = Json.parseToJsonElement(response) as? JsonObject ?: return null
        val usage = root["usage"] as? JsonObject ?: return null
        val input = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: return null
        val output = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: return null
        if (input < 0 || output < 0) return null
        return TokenUsage(input, output)
    }

    internal fun buildPrompt(lines: List<NumberedLine>): String {
        val numbered = lines.joinToString("\n") { (i, speaker, text) ->
            val sp = speaker?.let { "[$it] " } ?: ""
            "$i: $sp$text"
        }
        val contextBlock = priorItemsContext?.let { "\n\n$it\n" } ?: "\n"
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
            $contextBlock
            Transcript:
            $numbered

            Return only the JSON array, no prose.
        """.trimIndent()
    }

    private fun callApi(prompt: String): String {
        val url = URL(apiUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(requestBody(prompt).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.providerFailureFor(code)?.let { throw ProcessingException(it.code, it.retryable) }
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

    /** Items the last response held that were left out because they were malformed. */
    @Volatile internal var droppedItems: Int = 0
        private set

    internal fun parseResponse(response: String): List<ExtractedItem> {
        droppedItems = 0
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

        val parsed = items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val evidence = obj["evidence_segment_indices"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
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
        val dropped = items.size - parsed.size
        if (dropped > 0) {
            // A malformed item from the model is left out, but counted: silence here would make a
            // day look emptier than it is. The caller logs the count.
            droppedItems = dropped
            if (parsed.isEmpty()) throw ProcessingException("invalid_extraction_result", retryable = false)
        }
        return parsed
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
        if (model == "gpt-6.1-sol") {
            put("reasoning_effort", "low")
        } else if (model.startsWith("gpt-6")) {
            put("reasoning_effort", "none")
        } else {
            put("temperature", 0.1)
        }
    }.toString()

    companion object {
        private const val TAG = "ApiKeyExtraction"
        private const val TIMEOUT_MS = 60_000
    }
}
