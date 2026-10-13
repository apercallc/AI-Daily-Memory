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
            "https://my.example:0",
            "https://my.example:65536",
            "https://my.example:99999",
            "https://my.example?a=1",
            "https://my.example#a",
            "ftp://my.example",
        ).forEach { assertEquals(ServerAddress.Invalid, ServerAddress.parse(it), it) }
    }

    @Test
    fun `an address with a trailing dot slash or an IP literal is still reduced to an origin`() {
        assertEquals("https://192.168.1.5:8443", origin("https://192.168.1.5:8443/"))
        assertEquals("https://[::1]", origin("https://[::1]/x"))
    }

    @Test
    fun `the hosted address round-trips as hosted`() {
        assertEquals(true, HostedServer.isHostedUrl(origin(HostedServer.WEBSITE_URL)))
    }
}
