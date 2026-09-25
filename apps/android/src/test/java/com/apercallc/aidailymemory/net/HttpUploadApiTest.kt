package com.apercallc.aidailymemory.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Against a real local HTTP server: headers, bodies, and error envelopes as sent on the wire. */
@RunWith(RobolectricTestRunner::class)
class HttpUploadApiTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
    private var respond: (HttpExchange) -> Pair<Int, String> = { 200 to """{"state":"received"}""" }
    private val api = HttpUploadApi(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
    private val token = "adm_" + "k".repeat(40)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(",") }
            requests += Triple("${exchange.requestMethod} ${exchange.requestURI.path}", headers, body)
            val (status, text) = respond(exchange)
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    private val clip = ClipRequest(
        uploadId = "u1",
        sessionId = "s1",
        segmentId = "g1",
        offsetMs = 64,
        durationMs = 640,
        byteSize = 470,
        sha256 = "a".repeat(64),
        codec = "aac-lc-adts",
        sampleRateHz = 16_000,
        channelCount = 1,
        capturedAt = "2026-09-25T14:03:00+02:00",
    )

    @Test
    fun `create sends the clip with the bearer token and parses the target`() {
        respond = {
            201 to JSONObject()
                .put("upload_id", "u1")
                .put("state", "pending")
                .put(
                    "upload",
                    JSONObject()
                        .put("method", "PUT")
                        .put("url", "$base/put/u1")
                        .put("headers", JSONObject().put("x-amz-checksum-sha256", "abc"))
                        .put("expires_at", "2026-09-25T15:00:00Z"),
                )
                .toString()
        }

        val state = api.create(ServerLink(base, token), clip)

        assertEquals("pending", state.state)
        assertEquals("$base/put/u1", state.target?.url)
        assertEquals("abc", state.target?.headers?.get("x-amz-checksum-sha256"))
        val (line, headers, body) = requests.single()
        assertEquals("POST /v1/uploads", line)
        assertEquals("Bearer $token", headers["authorization"])
        val sent = JSONObject(String(body))
        assertEquals("u1", sent.getString("upload_id"))
        assertEquals(64, sent.getLong("offset_ms"))
        assertEquals("2026-09-25T14:03:00+02:00", sent.getString("captured_at"))
    }

    @Test
    fun `put sends exactly the bytes with the signed headers, never the token`() {
        respond = { 200 to "" }
        val bytes = ByteArray(470) { it.toByte() }
        val target = UploadTarget(
            "$base/put/u1",
            mapOf("x-amz-checksum-sha256" to "abc", "Content-Length" to "470"),
        )

        api.put(target, bytes)

        val (line, headers, body) = requests.single()
        assertEquals("PUT /put/u1", line)
        assertEquals("abc", headers["x-amz-checksum-sha256"])
        assertEquals("470", headers["content-length"])
        assertNull(headers["authorization"])
        assertContentEquals(bytes, body)
    }

    @Test
    fun `complete parses a finished state`() {
        val state = api.complete(ServerLink(base, token), "u1")

        assertEquals("received", state.state)
        assertNull(state.target)
        assertEquals("POST /v1/uploads/u1/complete", requests.single().first)
    }

    @Test
    fun `error envelopes surface their code`() {
        respond = { 409 to """{"error":{"code":"idempotency_conflict","message":"x"}}""" }

        val error = assertFailsWith<ApiException> { api.create(ServerLink(base, token), clip) }

        assertEquals(409, error.status)
        assertEquals("idempotency_conflict", error.code)
    }

    @Test
    fun `errors without an envelope still carry the status`() {
        respond = { 502 to "" }

        val error = assertFailsWith<ApiException> { api.complete(ServerLink(base, token), "u1") }

        assertEquals("http_502", error.code)
    }

    @Test
    fun `storage refusing a put is a retryable api error`() {
        respond = { 403 to "SignatureDoesNotMatch" }

        val error = assertFailsWith<ApiException> {
            api.put(UploadTarget("$base/put", emptyMap()), ByteArray(3))
        }

        assertEquals("storage_rejected", error.code)
    }

    @Test
    fun `garbage responses are io errors, and a missing state is too`() {
        respond = { 200 to "<html>" }
        assertFailsWith<IOException> { api.complete(ServerLink(base, token), "u1") }
        respond = { 200 to "{}" }
        assertFailsWith<IOException> { api.complete(ServerLink(base, token), "u1") }
    }
}
