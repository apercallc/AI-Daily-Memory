package com.apercallc.aidailymemory.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

/** Where to reach the backend and as whom. The token is a secret: never log it. */
data class ServerLink(val baseUrl: String, val token: String) {
    init {
        // Loopback http exists only for tests and local tooling; the manifest forbids
        // cleartext traffic on devices anyway.
        require(baseUrl.startsWith("https://") || LOOPBACK.matches(baseUrl)) { "backend must use HTTPS" }
    }

    private companion object {
        val LOOPBACK = Regex("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$")
    }

    override fun toString(): String = "ServerLink(baseUrl=$baseUrl, token=<redacted>)"
}

/** The server said no, with its error envelope's code (app/errors.py). */
class ApiException(val status: Int, val code: String) : IOException("api_error status=$status code=$code")

data class ClipRequest(
    val uploadId: String,
    val sessionId: String,
    val segmentId: String,
    val offsetMs: Long,
    val durationMs: Long,
    val byteSize: Long,
    val sha256: String,
    val codec: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    /** ISO-8601 with offset: the server rejects times without one. */
    val capturedAt: String,
)

data class UploadTarget(val url: String, val headers: Map<String, String>)

data class UploadState(val state: String, val target: UploadTarget?)

/** The one seam to the backend upload protocol (docs: backend/app/api/uploads.py). */
interface UploadApi {
    fun create(link: ServerLink, clip: ClipRequest): UploadState

    fun put(target: UploadTarget, bytes: ByteArray)

    fun complete(link: ServerLink, uploadId: String): UploadState
}

/**
 * Plain HttpURLConnection: three calls per clip don't justify an HTTP library. Blocking, so
 * callers run it on a background dispatcher. Bodies and tokens never reach the logs.
 */
class HttpUploadApi(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : UploadApi {
    override fun create(link: ServerLink, clip: ClipRequest): UploadState {
        val body = JSONObject()
            .put("upload_id", clip.uploadId)
            .put("session_id", clip.sessionId)
            .put("segment_id", clip.segmentId)
            .put("offset_ms", clip.offsetMs)
            .put("duration_ms", clip.durationMs)
            .put("byte_size", clip.byteSize)
            .put("sha256", clip.sha256)
            .put("codec", clip.codec)
            .put("sample_rate_hz", clip.sampleRateHz)
            .put("channel_count", clip.channelCount)
            .put("captured_at", clip.capturedAt)
        return parseState(request(link, "POST", "/v1/uploads", body))
    }

    override fun complete(link: ServerLink, uploadId: String): UploadState =
        parseState(request(link, "POST", "/v1/uploads/$uploadId/complete", null))

    override fun put(target: UploadTarget, bytes: ByteArray) {
        val connection = open(target.url, "PUT")
        try {
            target.headers.forEach { (name, value) ->
                // HttpURLConnection sets Content-Length itself from the fixed-length mode.
                if (!name.equals("Content-Length", ignoreCase = true)) connection.setRequestProperty(name, value)
            }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiException(status, "storage_rejected")
        } finally {
            connection.disconnect()
        }
    }

    private fun request(link: ServerLink, method: String, path: String, body: JSONObject?): JSONObject {
        val connection = open(link.baseUrl.trimEnd('/') + path, method)
        try {
            connection.setRequestProperty("Authorization", "Bearer ${link.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("unparseable_response status=$status", e)
            }
            if (status !in 200..299) {
                val code = json.optJSONObject("error")?.optString("code").orEmpty().ifEmpty { "http_$status" }
                throw ApiException(status, code)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun parseState(json: JSONObject): UploadState {
        val state = json.optString("state").ifEmpty { throw IOException("missing_state") }
        val target = json.optJSONObject("upload")?.let { upload ->
            val headers = upload.optJSONObject("headers")
            UploadTarget(
                url = upload.getString("url"),
                headers = headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty(),
            )
        }
        return UploadState(state, target)
    }
}

/** What the user typed on the connect screen, checked before anything is stored. */
sealed interface LinkInput {
    data class Valid(val link: ServerLink) : LinkInput
    data object InvalidUrl : LinkInput
    data object InvalidToken : LinkInput

    companion object {
        private val TOKEN = Regex("^adm_[A-Za-z0-9_-]{20,120}$")

        fun parse(url: String, token: String): LinkInput {
            val base = url.trim().trimEnd('/')
            val secret = token.trim()
            if (!base.startsWith("https://") || base.length <= "https://".length || base.contains(' ')) {
                return InvalidUrl
            }
            if (!TOKEN.matches(secret)) return InvalidToken
            return Valid(ServerLink(base, secret))
        }
    }
}
