package com.apercallc.aidailymemory.net

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
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

/** The backend said no, with its error envelope's code (app/errors.py). */
class ApiException(val status: Int, val code: String) : IOException("api_error status=$status code=$code")

/**
 * Object storage refused a presigned PUT (expired URL, clock skew, signature). Never an
 * account problem: the backend is the only party that can reject the device token.
 */
class StorageException(val status: Int) : IOException("storage_rejected status=$status")

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

/** [uploadId] is the id the server keeps the clip under (it can differ for a re-created clip). */
data class UploadState(val state: String, val target: UploadTarget?, val uploadId: String? = null)

/** The calling device's account (backend/app/api/account.py). */
interface AccountApi {
    /** Verifies the token; returns the account's storage mode (ADR-0014). */
    fun me(link: ServerLink): String

    /** Revokes the calling token on the server (Disconnect). */
    fun revokeCurrent(link: ServerLink)
}

/** A sealed transcript waiting on the server (backend/app/api/deliveries.py). */
data class SealedDelivery(val deliveryId: String, val uploadId: String, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SealedDelivery && other.deliveryId == deliveryId && other.uploadId == uploadId &&
            other.ciphertext.contentEquals(ciphertext)

    override fun hashCode(): Int = deliveryId.hashCode()
}

/** Collecting sealed transcripts (ADR-0014). */
interface DeliveryApi {
    /** Registers (or rotates to) this phone's HPKE public key. Idempotent for the same key. */
    fun registerKey(link: ServerLink, publicKey: ByteArray)

    fun deliveries(link: ServerLink, limit: Int): List<SealedDelivery>

    /** The phone stored them: the server deletes its copies. Returns how many it deleted. */
    fun acknowledge(link: ServerLink, deliveryIds: List<String>): Int
}

/** The one seam to the backend upload protocol (docs: backend/app/api/uploads.py). */
interface UploadApi {
    fun create(link: ServerLink, clip: ClipRequest): UploadState

    fun put(target: UploadTarget, bytes: ByteArray)

    fun complete(link: ServerLink, uploadId: String): UploadState

    /** The upload's state on the server, or null if the server has no such upload for us. */
    fun status(link: ServerLink, uploadId: String): String?
}

/**
 * Plain HttpURLConnection: three calls per clip don't justify an HTTP library. Blocking, so
 * callers run it on a background dispatcher. Bodies and tokens never reach the logs.
 */
class HttpUploadApi(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : UploadApi, AccountApi, DeliveryApi {
    override fun registerKey(link: ServerLink, publicKey: ByteArray) {
        val body = JSONObject().put("public_key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
        request(link, "PUT", "/v1/device-key", body)
    }

    override fun deliveries(link: ServerLink, limit: Int): List<SealedDelivery> {
        val page = request(link, "GET", "/v1/deliveries?limit=$limit", null).optJSONArray("deliveries")
            ?: throw IOException("missing_deliveries")
        return (0 until page.length()).map { i ->
            val item = page.getJSONObject(i)
            SealedDelivery(
                deliveryId = item.getString("delivery_id"),
                uploadId = item.getString("upload_id"),
                ciphertext = Base64.decode(item.getString("ciphertext"), Base64.DEFAULT),
            )
        }
    }

    override fun acknowledge(link: ServerLink, deliveryIds: List<String>): Int {
        val body = JSONObject().put("delivery_ids", JSONArray(deliveryIds))
        return request(link, "POST", "/v1/deliveries/ack", body).optInt("acknowledged")
    }

    override fun me(link: ServerLink): String =
        request(link, "GET", "/v1/me", null).optString("storage_mode").ifEmpty { throw IOException("missing_mode") }

    override fun revokeCurrent(link: ServerLink) {
        request(link, "DELETE", "/v1/tokens/current", null)
    }

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

    override fun status(link: ServerLink, uploadId: String): String? = try {
        request(link, "GET", "/v1/uploads/$uploadId", null).optString("state").ifEmpty { null }
    } catch (e: ApiException) {
        if (e.status == HTTP_NOT_FOUND) null else throw e
    }

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
            if (status !in 200..299) throw StorageException(status)
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
                if (text.isBlank()) JSONObject() else JSONObject(text)
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

    private companion object {
        const val HTTP_NOT_FOUND = 404
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
            val url = upload.optString("url")
            // Presigned URLs must be HTTPS too (loopback only in tests).
            if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")) {
                throw IOException("unsafe_upload_url")
            }
            val headers = upload.optJSONObject("headers")
            UploadTarget(
                url = url,
                headers = headers?.keys()?.asSequence()?.associateWith { headers.optString(it) }.orEmpty(),
            )
        }
        return UploadState(state, target, json.optString("upload_id").ifEmpty { null })
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
