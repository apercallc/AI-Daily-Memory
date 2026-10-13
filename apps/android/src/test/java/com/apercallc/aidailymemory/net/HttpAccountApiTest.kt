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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Against a real local HTTP server: headers, bodies, and error envelopes as sent on the wire. */
@RunWith(RobolectricTestRunner::class)
class HttpAccountApiTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
    private var respond: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }
    private val api = HttpAccountApi(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
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

    @Test
    fun `error envelopes surface their code`() {
        respond = { 409 to """{"error":{"code":"email_taken","message":"x"}}""" }

        val error = assertFailsWith<ApiException> { api.signUp(base, "a@b.example", "pw", "phone") }

        assertEquals(409, error.status)
        assertEquals("email_taken", error.code)
    }

    @Test
    fun `errors without an envelope still carry the status`() {
        respond = { 502 to "" }

        val error = assertFailsWith<ApiException> { api.me(ServerLink(base, token)) }

        assertEquals("http_502", error.code)
    }

    @Test
    fun `me checks the token and revoke deletes the current one`() {
        respond = { exchange ->
            if (exchange.requestMethod == "GET") {
                200 to JSONObject().put("storage_mode", "phone_only").toString()
            } else {
                204 to ""
            }
        }

        assertEquals("phone_only", api.me(ServerLink(base, token)))
        api.revokeCurrent(ServerLink(base, token))

        assertEquals(listOf("GET /v1/me", "DELETE /v1/tokens/current"), requests.map { it.first })
        assertEquals("Bearer $token", requests.last().second["authorization"])
    }

    @Test
    fun `sign in sends the credentials without a bearer token and returns the issued token`() {
        respond = { 200 to JSONObject().put("token", token).toString() }

        val issued = api.logIn(base, "a@b.example", "correct horse battery", "Pixel")

        assertEquals(token, issued)
        assertEquals("POST /v1/login", requests.single().first)
        assertEquals(null, requests.single().second["authorization"])
        assertEquals("a@b.example", JSONObject(String(requests.single().third)).getString("email"))
    }

    @Test
    fun `garbage responses are io errors, and a missing token is too`() {
        respond = { 200 to "<html>" }
        assertFailsWith<IOException> { api.me(ServerLink(base, token)) }
        respond = { 200 to "{}" }
        assertFailsWith<IOException> { api.signUp(base, "a@b.example", "pw", "phone") }
    }

    @Test
    fun `the client has no call that could carry audio`() {
        val methods = HttpAccountApi::class.java.declaredMethods.map { it.name }

        assertEquals(emptyList(), methods.filter { it.contains("upload", true) || it.contains("put", true) })
    }
}
