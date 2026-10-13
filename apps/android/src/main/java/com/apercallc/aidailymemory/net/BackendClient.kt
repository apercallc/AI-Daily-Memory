package com.apercallc.aidailymemory.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject
import com.apercallc.aidailymemory.recording.server.HostedServer

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

/** The calling device's account (backend/app/api/account.py). */
interface AccountApi {
    /** Verifies the token; returns the account's storage mode label (unused by the phone). */
    fun me(link: ServerLink): String

    /** Revokes the calling token on the server (Disconnect). */
    fun revokeCurrent(link: ServerLink)
}

/**
 * Getting a device token without copying one by hand (backend/app/api/signup.py). These calls
 * have no bearer token yet and always go to the hosted server: there is no address to choose.
 */
interface SignInApi {
    /** Creates an account and returns this device's token (shown once by the server). */
    fun signUp(server: String, email: String, password: String, deviceName: String): String

    /** Signs in to an existing account and returns a new token for this device. */
    fun logIn(server: String, email: String, password: String, deviceName: String): String
}

/**
 * Plain HttpURLConnection: a handful of small account calls don't justify an HTTP library.
 * Blocking, so callers run it on a background dispatcher. Bodies and tokens never reach the logs.
 * It carries sign-in, token checks and revoke only; audio is never sent anywhere (ADR-0026).
 */
class HttpAccountApi(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : AccountApi, SignInApi {
    override fun me(link: ServerLink): String =
        request(link, "GET", "/v1/me", null).optString("storage_mode").ifEmpty { throw IOException("missing_mode") }

    override fun signUp(server: String, email: String, password: String, deviceName: String): String =
        issueToken(server, "/v1/signup", email, password, deviceName)

    override fun logIn(server: String, email: String, password: String, deviceName: String): String =
        issueToken(server, "/v1/login", email, password, deviceName)

    private fun issueToken(server: String, path: String, email: String, password: String, deviceName: String): String {
        val body = JSONObject().put("email", email).put("password", password).put("device_name", deviceName)
        return send(server.trimEnd('/') + path, "POST", null, body).optString("token").ifEmpty {
            throw IOException("missing_token")
        }
    }

    override fun revokeCurrent(link: ServerLink) {
        request(link, "DELETE", "/v1/tokens/current", null)
    }

    private fun request(link: ServerLink, method: String, path: String, body: JSONObject?): JSONObject =
        send(link.baseUrl.trimEnd('/') + path, method, link.token, body)

    /** One JSON call. [token] is null only for sign-up and sign-in, which have none yet. */
    private fun send(url: String, method: String, token: String?, body: JSONObject?): JSONObject {
        val connection = open(url, method)
        try {
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
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
                // A proxy error page is still a "no" from the server, not a lost connection.
                if (status !in 200..299) throw ApiException(status, "http_$status")
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
}

/** What the user typed on the connect screen, checked before anything is stored. */
sealed interface LinkInput {
    data class Valid(val link: ServerLink) : LinkInput
    data object InvalidToken : LinkInput

    companion object {
        private val TOKEN = Regex("^adm_[A-Za-z0-9_-]{20,120}$")

        /** [server] is the chosen origin: the hosted one unless the person set their own. */
        fun parse(token: String, server: String = HostedServer.BASE_URL): LinkInput {
            val secret = token.trim()
            if (!TOKEN.matches(secret)) return InvalidToken
            return Valid(ServerLink(server, secret))
        }
    }
}
