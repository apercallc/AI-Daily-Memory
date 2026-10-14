package com.apercallc.aidailymemory.processing

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderHealthCheckTest {
    @Test
    fun `Deepgram metadata check verifies auth without posting audio`() {
        val connection = FakeConnection(URL("https://api.deepgram.com/v1/projects"), 200, "{}")
        var requestedUrl: URL? = null
        val check = ProviderHealthCheck { url ->
            requestedUrl = url
            connection
        }

        assertEquals(
            ProviderHealthResult.CONNECTED,
            check.checkTranscription("https://api.deepgram.com/v1/listen?model=nova-3", "test-secret"),
        )
        assertEquals("https://api.deepgram.com/v1/projects", requestedUrl.toString())
        assertEquals("GET", connection.requestMethod)
        assertEquals("Token test-secret", connection.getRequestProperty("Authorization"))
        assertEquals(false, connection.doOutput)
    }

    @Test
    fun `summary check verifies that the selected model is listed`() {
        val connection = FakeConnection(
            URL("https://api.openai.com/v1/models"),
            200,
            """{"data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"}]}""",
        )
        val check = ProviderHealthCheck { connection }

        assertEquals(
            ProviderHealthResult.CONNECTED,
            check.checkSummary(
                "https://api.openai.com/v1/chat/completions",
                "test-secret",
                "gpt-4o-mini",
            ),
        )
        assertEquals("GET", connection.requestMethod)
        assertEquals("Bearer test-secret", connection.getRequestProperty("Authorization"))
        assertEquals(false, connection.doOutput)
    }

    @Test
    fun `summary check identifies a model missing from provider metadata`() {
        val connection = FakeConnection(URL("https://api.openai.com/v1/models"), 200, """{"data":[]}""")

        assertEquals(
            ProviderHealthResult.MODEL_UNAVAILABLE,
            ProviderHealthCheck { connection }.checkSummary(
                "https://api.openai.com/v1/chat/completions",
                "test-secret",
                "missing-model",
            ),
        )
    }

    @Test
    fun `checks distinguish auth quota and ordinary rate limits`() {
        val auth = FakeConnection(URL("https://api.deepgram.com/v1/projects"), 401, "")
        val quota = FakeConnection(
            URL("https://api.openai.com/v1/models"),
            429,
            """{"error":{"code":"insufficient_quota"}}""",
        )
        val rate = FakeConnection(URL("https://api.openai.com/v1/models"), 429, "{}")

        assertEquals(
            ProviderHealthResult.AUTH_FAILED,
            ProviderHealthCheck { auth }.checkTranscription("https://api.deepgram.com/v1/listen", "key"),
        )
        assertEquals(
            ProviderHealthResult.QUOTA_EXHAUSTED,
            ProviderHealthCheck { quota }.checkSummary(
                "https://api.openai.com/v1/chat/completions", "key", "model",
            ),
        )
        assertEquals(
            ProviderHealthResult.RATE_LIMITED,
            ProviderHealthCheck { rate }.checkSummary(
                "https://api.openai.com/v1/chat/completions", "key", "model",
            ),
        )
    }

    @Test
    fun `unsupported and invalid settings make no provider request`() {
        var requests = 0
        val check = ProviderHealthCheck {
            requests++
            FakeConnection(it, 200, "{}")
        }

        assertEquals(
            ProviderHealthResult.UNSUPPORTED_CHECK,
            check.checkSummary("https://example.com/custom", "key", "model"),
        )
        assertEquals(
            ProviderHealthResult.UNSUPPORTED_CHECK,
            check.checkSummary("https://api.anthropic.com/v1/chat/completions", "key", "claude-model"),
        )
        assertEquals(
            ProviderHealthResult.INVALID_CONFIGURATION,
            check.checkTranscription("http://example.com/v1/listen", "key"),
        )
        assertEquals(0, requests)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }
}
