package com.apercallc.aidailymemory.recording.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostedServerTest {
    @Test
    fun `the hosted address is https and has no path`() {
        assertEquals("https://aidailymem.apercallc.com", HostedServer.BASE_URL)
        assertTrue(HostedServer.SYNC_URL.startsWith(HostedServer.BASE_URL))
        assertTrue(HostedServer.WEBSITE_URL.startsWith("https://"))
    }

    @Test
    fun `only the hosted address counts as hosted`() {
        assertTrue(HostedServer.isHostedUrl("https://aidailymem.apercallc.com"))
        assertTrue(HostedServer.isHostedUrl(" https://aidailymem.apercallc.com/ "))
        assertTrue(HostedServer.isHostedUrl("HTTPS://AIDAILYMEM.APERCALLC.COM"))
        assertFalse(HostedServer.isHostedUrl("https://example.com"))
        assertFalse(HostedServer.isHostedUrl("http://aidailymem.apercallc.com"))
        assertFalse(HostedServer.isHostedUrl("https://aidailymem.apercallc.com.evil.example"))
        assertFalse(HostedServer.isHostedUrl(""))
    }

    @Test
    fun `mode follows whether an account is linked`() {
        assertEquals(ServerMode.CONNECTED, ServerMode.of(true))
        assertEquals(ServerMode.OFFLINE, ServerMode.of(false))
    }

    @Test
    fun `http statuses map to a connection result`() {
        assertEquals(ConnectionCheck.OK, ConnectionCheck.fromHttpStatus(200))
        assertEquals(ConnectionCheck.OK, ConnectionCheck.fromHttpStatus(204))
        assertEquals(ConnectionCheck.SIGN_IN_REJECTED, ConnectionCheck.fromHttpStatus(401))
        assertEquals(ConnectionCheck.SIGN_IN_REJECTED, ConnectionCheck.fromHttpStatus(403))
        assertEquals(ConnectionCheck.SERVER_ERROR, ConnectionCheck.fromHttpStatus(500))
        assertEquals(ConnectionCheck.SERVER_ERROR, ConnectionCheck.fromHttpStatus(503))
        assertEquals(ConnectionCheck.SERVER_ERROR, ConnectionCheck.fromHttpStatus(404))
        assertEquals(ConnectionCheck.UNREACHABLE, ConnectionCheck.fromHttpStatus(null))
    }

    @Test
    fun `only OK is a success`() {
        assertTrue(ConnectionCheck.OK.isSuccess)
        ConnectionCheck.entries.filter { it != ConnectionCheck.OK }.forEach { assertFalse(it.isSuccess) }
    }
}
