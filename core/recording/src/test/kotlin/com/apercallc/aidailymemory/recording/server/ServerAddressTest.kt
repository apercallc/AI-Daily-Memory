package com.apercallc.aidailymemory.recording.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerAddressTest {
    private fun origin(input: String) = (ServerAddress.parse(input) as ServerAddress.Valid).origin

    @Test
    fun `blank means the default server`() {
        assertEquals(ServerAddress.Blank, ServerAddress.parse("   "))
    }

    @Test
    fun `only the origin is kept`() {
        assertEquals("https://my.example", origin("https://my.example/"))
        assertEquals("https://my.example", origin("https://my.example/v1/sync"))
        assertEquals("https://my.example:8443", origin(" https://MY.example:8443/x "))
    }

    @Test
    fun `plain http, credentials, queries and junk are refused`() {
        listOf(
            "http://my.example",
            "https://user:pw@my.example",
            "https://my.example/?a=1",
            "https://my.example/#frag",
            "my.example",
            "https://",
            "https://bad host",
        ).forEach { assertEquals(ServerAddress.Invalid, ServerAddress.parse(it), it) }
    }

    @Test
    fun `the hosted address round-trips as hosted`() {
        assertEquals(true, HostedServer.isHostedUrl(origin(HostedServer.WEBSITE_URL)))
    }
}
