package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TranscribedSpan
import com.apercallc.aidailymemory.recording.processing.TranscriptResult
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls a user-configured transcription API (Deepgram-style prerecorded REST) with a
 * user-supplied API key (ADR-0020). The phone calls the provider directly — the audio
 * never touches our server. Uses HttpURLConnection (no extra dependencies).
 */
class ApiKeyTranscriptionProvider(
    private val apiUrl: String,
    private val apiKey: String,
    override val model: String,
) : TranscriptionProvider {

    override val name: String = "user-key"

    override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
        val response = callApi(audio)
        return parseResponse(response)
    }

    private fun callApi(audio: ByteArray): String {
        val url = URL(apiUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "audio/aac")
            setRequestProperty("Authorization", "Token $apiKey")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(audio) }
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

    internal fun parseResponse(response: String): TranscriptResult {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(response).jsonObject
        val language = root["language"]?.jsonPrimitive?.contentOrNull
        val results = root["results"]?.jsonObject
            ?: root["channels"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProcessingException("invalid_provider_result", retryable = false)
        val alternatives = results["alternatives"]?.jsonArray
            ?: throw ProcessingException("invalid_provider_result", retryable = false)
        val first = alternatives.firstOrNull()?.jsonObject
            ?: throw ProcessingException("invalid_provider_result", retryable = false)

        val spans = mutableListOf<TranscribedSpan>()
        val words = first["words"]?.jsonArray
        if (words != null) {
            // Group words into spans by speaker
            var currentSpeaker: String? = null
            var spanStart = 0L
            var spanText = StringBuilder()
            for (word in words) {
                val obj = word.jsonObject
                val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull
                    ?: obj["speaker_label"]?.jsonPrimitive?.contentOrNull
                val startMs = ((obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                val endMs = ((obj["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                val text = obj["word"]?.jsonPrimitive?.contentOrNull
                    ?: obj["punctuated_word"]?.jsonPrimitive?.contentOrNull
                    ?: continue
                val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull

                if (speaker != currentSpeaker && spanText.isNotEmpty()) {
                    spans.add(TranscribedSpan(spanStart, startMs, spanText.toString().trim(), confidence, currentSpeaker))
                    spanText = StringBuilder()
                }
                if (spanText.isEmpty()) spanStart = startMs
                spanText.append(text).append(" ")
                currentSpeaker = speaker
            }
            if (spanText.isNotEmpty()) {
                val lastEnd = words.lastOrNull()?.jsonObject?.let {
                    ((it["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                } ?: spanStart
                spans.add(TranscribedSpan(spanStart, lastEnd, spanText.toString().trim(), null, currentSpeaker))
            }
        }

        // Fallback: use the transcript field if words aren't available
        if (spans.isEmpty()) {
            val transcript = first["transcript"]?.jsonPrimitive?.contentOrNull
            if (!transcript.isNullOrEmpty()) {
                spans.add(TranscribedSpan(0, 0, transcript, null, null))
            }
        }

        return TranscriptResult(language, spans)
    }

    companion object {
        private const val TIMEOUT_MS = 120_000
    }
}