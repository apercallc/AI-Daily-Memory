package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result codes are safe for the UI; provider response text and credentials are never retained. */
enum class ProviderHealthResult {
    IDLE,
    CHECKING,
    CONNECTED,
    AUTH_FAILED,
    QUOTA_EXHAUSTED,
    RATE_LIMITED,
    MODEL_UNAVAILABLE,
    INVALID_CONFIGURATION,
    UNSUPPORTED_CHECK,
    ENDPOINT_ERROR,
}

/** Verifies provider metadata access without uploading audio or transcript text. */
internal class ProviderHealthCheck(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun checkTranscription(apiUrl: String, apiKey: String): ProviderHealthResult {
        if (apiKey.isBlank() || EndpointUrl.parse(apiUrl) !is EndpointUrl.Valid) {
            return ProviderHealthResult.INVALID_CONFIGURATION
        }
        val host = runCatching { URL(apiUrl).host.lowercase() }.getOrNull()
        if (host != DEEPGRAM_HOST) return ProviderHealthResult.UNSUPPORTED_CHECK

        return request(
            url = "https://$DEEPGRAM_HOST/v1/projects",
            headers = mapOf("Authorization" to "Token $apiKey"),
        ).result
    }

    fun checkSummary(apiUrl: String, apiKey: String, model: String): ProviderHealthResult {
        if (apiKey.isBlank() || model.isBlank() || EndpointUrl.parse(apiUrl) !is EndpointUrl.Valid) {
            return ProviderHealthResult.INVALID_CONFIGURATION
        }
        val url = runCatching { URL(apiUrl) }.getOrNull() ?: return ProviderHealthResult.INVALID_CONFIGURATION
        if (url.host.equals(ANTHROPIC_HOST, ignoreCase = true)) return ProviderHealthResult.UNSUPPORTED_CHECK
        val path = url.path.trimEnd('/')
        val metadataPath = when {
            path.endsWith(CHAT_COMPLETIONS_PATH) -> path.removeSuffix(CHAT_COMPLETIONS_PATH) + MODELS_PATH
            path.endsWith(SHORT_CHAT_COMPLETIONS_PATH) -> path.removeSuffix(SHORT_CHAT_COMPLETIONS_PATH) + MODELS_PATH
            else -> return ProviderHealthResult.UNSUPPORTED_CHECK
        }
        val metadataUrl = URL(url.protocol, url.host, url.port, metadataPath)
        val response = request(metadataUrl.toString(), mapOf("Authorization" to "Bearer $apiKey"))
        if (response.result != ProviderHealthResult.CONNECTED) return response.result
        return if (model in response.models) ProviderHealthResult.CONNECTED else ProviderHealthResult.MODEL_UNAVAILABLE
    }

    private fun request(url: String, headers: Map<String, String>): MetadataResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(URL(url)).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val failure = connection.providerFailureFor(status)
                MetadataResponse(resultForFailure(failure?.code ?: status.toString()), emptySet())
            } else {
                val body = connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        val buffer = CharArray(MAX_RESPONSE_CHARS)
                        val count = reader.read(buffer)
                        if (count <= 0) "" else String(buffer, 0, count)
                    }
                }
                MetadataResponse(ProviderHealthResult.CONNECTED, modelIds(body))
            }
        } catch (_: IOException) {
            MetadataResponse(ProviderHealthResult.ENDPOINT_ERROR, emptySet())
        } catch (_: SecurityException) {
            MetadataResponse(ProviderHealthResult.ENDPOINT_ERROR, emptySet())
        } finally {
            connection?.disconnect()
        }
    }

    private fun modelIds(body: String): Set<String> = runCatching {
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?.mapNotNull { item -> item.jsonObject["id"]?.jsonPrimitive?.content }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    private fun resultForFailure(code: String): ProviderHealthResult = when (code) {
        "provider_auth_failed" -> ProviderHealthResult.AUTH_FAILED
        "provider_quota_exhausted" -> ProviderHealthResult.QUOTA_EXHAUSTED
        "provider_rate_limited" -> ProviderHealthResult.RATE_LIMITED
        "provider_error" -> ProviderHealthResult.ENDPOINT_ERROR
        else -> ProviderHealthResult.ENDPOINT_ERROR
    }

    private data class MetadataResponse(val result: ProviderHealthResult, val models: Set<String>)

    private companion object {
        const val DEEPGRAM_HOST = "api.deepgram.com"
        const val ANTHROPIC_HOST = "api.anthropic.com"
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val SHORT_CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val MODELS_PATH = "/v1/models"
        const val TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}
