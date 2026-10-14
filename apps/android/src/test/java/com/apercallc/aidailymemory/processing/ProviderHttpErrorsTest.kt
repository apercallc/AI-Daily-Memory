package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ProviderFailure
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderHttpErrorsTest {
    @Test
    fun `Deepgram insufficient credits is terminal`() {
        val connection = StubConnection(
            """{"err_code":"ASR_PAYMENT_REQUIRED","err_msg":"do not expose this"}""",
        )

        assertEquals(
            ProviderFailure("provider_quota_exhausted", retryable = false),
            connection.providerFailureFor(402),
        )
    }

    @Test
    fun `OpenAI exhausted quota on 429 is not retried as a transient limit`() {
        val connection = StubConnection(
            """{"error":{"message":"private detail","type":"insufficient_quota","code":"insufficient_quota"}}""",
        )

        assertEquals(
            ProviderFailure("provider_quota_exhausted", retryable = false),
            connection.providerFailureFor(429),
        )
    }

    @Test
    fun `ordinary 429 and unavailable error body remain retryable rate limits`() {
        assertEquals(
            ProviderFailure("provider_rate_limited", retryable = true),
            StubConnection("""{"err_code":"TOO_MANY_REQUESTS"}""").providerFailureFor(429),
        )
        assertEquals(
            ProviderFailure("provider_rate_limited", retryable = true),
            StubConnection(null).providerFailureFor(429),
        )
        assertEquals(
            ProviderFailure("provider_rate_limited", retryable = true),
            StubConnection(failingStream = true).providerFailureFor(429),
        )
    }

    private class StubConnection(
        body: String? = null,
        private val failingStream: Boolean = false,
    ) : HttpURLConnection(URL("https://provider.example/error")) {
        private val bodyBytes = body?.toByteArray(Charsets.UTF_8)

        override fun getErrorStream(): InputStream? = when {
            failingStream -> object : InputStream() {
                override fun read(): Int = throw IOException("unavailable")
            }
            bodyBytes != null -> bodyBytes.inputStream()
            else -> null
        }

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false
    }
}
