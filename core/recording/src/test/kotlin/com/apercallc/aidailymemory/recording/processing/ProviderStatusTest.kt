package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderStatusTest {
    @Test
    fun `success is not a failure`() = assertNull(ProviderStatus.failureFor(200))

    @Test
    fun `a rejected key is named and never retried`() {
        listOf(401, 403).forEach {
            assertEquals(ProviderFailure("provider_auth_failed", false), ProviderStatus.failureFor(it), "$it")
        }
    }

    @Test
    fun `rate limits, overload and server errors are retried later`() {
        assertEquals(ProviderFailure("provider_rate_limited", true), ProviderStatus.failureFor(429))
        listOf(408, 425).forEach { assertEquals(ProviderFailure("provider_busy", true), ProviderStatus.failureFor(it)) }
        listOf(500, 502, 503, 504, 529, 599).forEach {
            assertEquals(ProviderFailure("provider_server_error", true), ProviderStatus.failureFor(it), "$it")
        }
    }

    @Test
    fun `a request that can never succeed is given up on`() {
        listOf(400, 404, 413, 422, 499).forEach {
            assertEquals(ProviderFailure("provider_client_error", false), ProviderStatus.failureFor(it), "$it")
        }
        listOf(201, 204, 301, 302, 600).forEach {
            assertEquals(ProviderFailure("provider_unexpected", false), ProviderStatus.failureFor(it), "$it")
        }
    }
}
