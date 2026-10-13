package com.apercallc.aidailymemory.recording.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectLinkTest {
    private val token = "adm_" + "a".repeat(32)

    @Test
    fun `reads the token from a connect link`() {
        assertEquals(token, ConnectLink.tokenFrom("aidailymem://connect?token=$token"))
        assertEquals(token, ConnectLink.tokenFrom("  AIDAILYMEM://CONNECT/?x=1&token=$token#frag "))
    }

    @Test
    fun `a link cannot choose the server`() {
        assertEquals(
            token,
            ConnectLink.tokenFrom("aidailymem://connect?server=https://evil.example&token=$token"),
        )
    }

    @Test
    fun `rejects anything that is not a well-formed connect link`() {
        assertNull(ConnectLink.tokenFrom(null))
        assertNull(ConnectLink.tokenFrom(""))
        assertNull(ConnectLink.tokenFrom("https://aidailymem.apercallc.com/?token=$token"))
        assertNull(ConnectLink.tokenFrom("aidailymem://connectx?token=$token"))
        assertNull(ConnectLink.tokenFrom("aidailymem://connect"))
        assertNull(ConnectLink.tokenFrom("aidailymem://connect?other=1"))
        assertNull(ConnectLink.tokenFrom("aidailymem://connect?token=short"))
        assertNull(ConnectLink.tokenFrom("aidailymem://connect?token=adm_$%^&*"))
    }

    @Test
    fun `a paste can be a token or a link`() {
        assertEquals(token, ConnectLink.tokenFromPaste("  $token \n"))
        assertEquals(token, ConnectLink.tokenFromPaste("aidailymem://connect?token=$token"))
        assertNull(ConnectLink.tokenFromPaste("hello"))
        assertNull(ConnectLink.tokenFromPaste(""))
    }
}
