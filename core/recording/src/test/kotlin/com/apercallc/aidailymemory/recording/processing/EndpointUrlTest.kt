package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointUrlTest {
    @Test
    fun `https url with a host is valid and trimmed`() {
        assertEquals(
            EndpointUrl.Valid("https://api.example.com/v1/listen?x=1"),
            EndpointUrl.parse("  https://api.example.com/v1/listen?x=1 "),
        )
        assertEquals(EndpointUrl.Valid("https://10.0.0.5:8443/x"), EndpointUrl.parse("https://10.0.0.5:8443/x"))
        assertEquals(EndpointUrl.Valid("https://[::1]/x"), EndpointUrl.parse("https://[::1]/x"))
    }

    @Test
    fun `blank input is blank not invalid`() {
        assertEquals(EndpointUrl.Blank, EndpointUrl.parse("   "))
    }

    @Test
    fun `cleartext and other schemes are rejected`() {
        listOf("http://api.example.com", "ftp://x.example", "file:///etc/passwd", "api.example.com")
            .forEach { assertEquals(EndpointUrl.Invalid, EndpointUrl.parse(it), it) }
    }

    @Test
    fun `missing or malformed hosts are rejected`() {
        listOf("https://", "https:///path", "https://-bad.example", "https://exa mple.com", "https://a_b.example")
            .forEach { assertEquals(EndpointUrl.Invalid, EndpointUrl.parse(it), it) }
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertEquals(EndpointUrl.Invalid, EndpointUrl.parse("https://user:pw@api.example.com"))
    }

    @Test
    fun `scheme check is case insensitive`() {
        assertEquals(EndpointUrl.Valid("HTTPS://Api.Example.com"), EndpointUrl.parse("HTTPS://Api.Example.com"))
    }
}
