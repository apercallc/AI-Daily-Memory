package com.apercallc.aidailymemory.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.apercallc.aidailymemory.recording.server.ConnectionCheck
import com.apercallc.aidailymemory.recording.server.HostedServer
import java.io.IOException
import java.security.GeneralSecurityException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountLinkerTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("linker-test", Context.MODE_PRIVATE)
    private val token = "adm_" + "L".repeat(43)
    private val url = HostedServer.BASE_URL

    private class FakeAccountApi : AccountApi {
        var onMe: () -> String = { "phone_only" }
        var onRevoke: () -> Unit = {}
        val calls = mutableListOf<String>()

        override fun me(link: ServerLink): String = onMe().also { calls += "me" }

        override fun revokeCurrent(link: ServerLink) {
            calls += "revoke"
            onRevoke()
        }
    }

    private val api = FakeAccountApi()
    private var linked = 0
    private var unlinked = 0
    private lateinit var links: LinkStore

    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private var revokeRetries = 0


    private class FakeSignIn : SignInApi {
        var result: () -> String = { "adm_" + "S".repeat(43) }
        val calls = mutableListOf<String>()
        val servers = mutableListOf<String>()

        override fun signUp(server: String, email: String, password: String, deviceName: String): String {
            calls += "signUp:$email:$deviceName"
            servers += server
            return result()
        }

        override fun logIn(server: String, email: String, password: String, deviceName: String): String {
            calls += "logIn:$email:$deviceName"
            servers += server
            return result()
        }
    }

    private val signIn = FakeSignIn()

    private fun linker(store: LinkStore = links) = AccountLinker(
        store,
        api,
        onLinked = { linked++ },
        onUnlinked = { unlinked++ },
        onRevokePending = { revokeRetries++ },
        signIn = signIn,
        now = { 42L },
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        val key = aead()
        links = LinkStore(prefs) { key }
    }

    @Test
    fun `a verified token is stored and uploads are scheduled`() {
        assertNull(linker().connect(token, includeBacklog = false))

        assertEquals(ServerLink(url, token), links.link.value)
        assertEquals(42L, links.uploadSinceEpochMs)
        assertEquals(1, linked)
    }

    @Test
    fun `bad input never reaches the server`() {
        assertEquals(ConnectError.INVALID_TOKEN, linker().connect("nope", false))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the token is always linked to the hosted server, whatever else is typed`() {
        assertNull(linker().connect(token, includeBacklog = false))
        assertEquals("https://aidailymem.apercallc.com", links.link.value?.baseUrl)
    }

    @Test
    fun `a token the server refuses is reported and not stored`() {
        api.onMe = { throw ApiException(401, "unauthorized") }

        assertEquals(ConnectError.REJECTED, linker().connect(token, false))
        assertNull(links.link.value)
        assertEquals(0, linked)
    }

    @Test
    fun `an unreachable or failing server is reported and nothing is stored`() {
        api.onMe = { throw IOException("dns") }
        assertEquals(ConnectError.UNREACHABLE, linker().connect(token, false))
        api.onMe = { throw ApiException(503, "service_unavailable") }
        assertEquals(ConnectError.SERVER_ERROR, linker().connect(token, false))

        assertNull(links.link.value)
    }

    @Test
    fun `a keystore failure stores nothing and says so`() {
        val broken = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("no keystore")

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("no keystore")
        }

        val error = linker(LinkStore(prefs) { broken }).connect(token, false)

        assertEquals(ConnectError.SAVE_FAILED, error)
        assertEquals(0, linked)
    }

    @Test
    fun `disconnect forgets locally first, then revokes on the server`() {
        linker().connect(token, false)

        linker().disconnect()

        assertNull(links.link.value)
        assertEquals(1, unlinked)
        assertEquals(listOf("me", "revoke"), api.calls)
    }

    @Test
    fun `an offline revoke still disconnects, is remembered, and is retried later`() {
        linker().connect(token, false)
        api.onRevoke = { throw IOException("offline") }

        linker().disconnect()

        assertFalse(links.isLinked)
        assertTrue(links.revokePending.value)
        assertEquals(1, revokeRetries)
        assertEquals(false, linker().retryPendingRevoke())

        api.onRevoke = {}
        assertTrue(linker().retryPendingRevoke())
        assertFalse(links.revokePending.value)
        assertEquals(listOf("me", "revoke", "revoke", "revoke"), api.calls)
    }

    @Test
    fun `a token the server already refuses counts as revoked`() {
        linker().connect(token, false)
        api.onRevoke = { throw ApiException(401, "unauthorized") }

        linker().disconnect()

        assertFalse(links.revokePending.value)
        assertEquals(0, revokeRetries)
    }

    @Test
    fun `a server error keeps the revoke pending`() {
        linker().connect(token, false)
        api.onRevoke = { throw ApiException(503, "service_unavailable") }

        linker().disconnect()

        assertTrue(links.revokePending.value)
    }

    @Test
    fun `several disconnected tokens wait and are all revoked, before connecting again`() {
        api.onRevoke = { throw IOException("offline") }
        linker().connect(token, false)
        linker().disconnect()
        linker().connect("adm_" + "M".repeat(43), false)
        linker().disconnect()
        assertEquals(2, links.pendingRevokes().size)

        api.onRevoke = {}
        linker().connect("adm_" + "N".repeat(43), false)

        assertTrue(links.pendingRevokes().isEmpty())
        assertFalse(links.revokePending.value)
    }

    @Test
    fun `disconnecting when not linked does nothing`() {
        linker().disconnect()

        assertEquals(0, unlinked)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `creating an account stores the issued token after the server verifies it`() {
        assertNull(linker().signUp(" me@example.com ", "a-long-enough-password", "Pixel 9", includeBacklog = false))

        assertEquals(listOf("signUp:me@example.com:Pixel 9"), signIn.calls)
        assertEquals(ServerLink(url, "adm_" + "S".repeat(43)), links.link.value)
        assertEquals(1, linked)
    }

    @Test
    fun `sign-in and the stored link follow the server the person chose`() {
        links.setServerUrl("https://own.example")

        assertNull(linker().signUp("me@example.com", "a-long-enough-password", "Pixel 9", includeBacklog = false))

        assertEquals("https://own.example", signIn.servers.single())
        assertEquals("https://own.example", links.link.value?.baseUrl)
    }

    @Test
    fun `signing in works the same way`() {
        assertNull(linker().logIn("me@example.com", "a-long-enough-password", "Pixel 9", includeBacklog = true))

        assertEquals(listOf("logIn:me@example.com:Pixel 9"), signIn.calls)
        assertEquals(0L, links.uploadSinceEpochMs)
    }

    @Test
    fun `each sign-in problem is reported on its own and nothing is stored`() {
        val cases = mapOf(
            ApiException(400, "invalid_email") to ConnectError.INVALID_EMAIL,
            ApiException(400, "weak_password") to ConnectError.WEAK_PASSWORD,
            ApiException(409, "email_taken") to ConnectError.EMAIL_TAKEN,
            ApiException(401, "invalid_credentials") to ConnectError.WRONG_CREDENTIALS,
            ApiException(429, "rate_limited") to ConnectError.TOO_MANY_ATTEMPTS,
            ApiException(503, "service_unavailable") to ConnectError.SERVER_ERROR,
            ApiException(500, "internal_error") to ConnectError.SERVER_ERROR,
            ApiException(404, "http_404") to ConnectError.WRONG_SERVER,
            ApiException(405, "method_not_allowed") to ConnectError.WRONG_SERVER,
            ApiException(418, "teapot") to ConnectError.UNREACHABLE,
        )
        cases.forEach { (failure, expected) ->
            signIn.result = { throw failure }
            assertEquals(expected, linker().logIn("me@example.com", "pw", "Pixel", false), "$failure")
        }
        signIn.result = { throw IOException("offline") }
        assertEquals(ConnectError.UNREACHABLE, linker().signUp("me@example.com", "pw", "Pixel", false))
        assertNull(links.link.value)
        assertEquals(0, linked)
    }

    @Test
    fun `a server failure while verifying a token is reported as the server's, not the network's`() {
        api.onMe = { throw ApiException(500, "internal_error") }
        assertEquals(ConnectError.SERVER_ERROR, linker().connect(token, false))
        api.onMe = { throw ApiException(404, "http_404") }
        assertEquals(ConnectError.WRONG_SERVER, linker().connect(token, false))
        assertNull(links.link.value)
    }

    @Test
    fun `an issued token the server then refuses is not stored`() {
        api.onMe = { throw ApiException(401, "unauthorized") }

        assertEquals(ConnectError.REJECTED, linker().signUp("me@example.com", "pw", "Pixel", false))
        assertNull(links.link.value)
    }

    @Test
    fun `testing the connection reports each outcome without changing anything`() {
        assertEquals(ConnectionCheck.NOT_CONNECTED, linker().testConnection())

        linker().connect(token, false)
        api.calls.clear()
        assertEquals(ConnectionCheck.OK, linker().testConnection())
        assertEquals(listOf("me"), api.calls)

        api.onMe = { throw ApiException(401, "unauthorized") }
        assertEquals(ConnectionCheck.SIGN_IN_REJECTED, linker().testConnection())
        api.onMe = { throw ApiException(503, "service_unavailable") }
        assertEquals(ConnectionCheck.SERVER_ERROR, linker().testConnection())
        api.onMe = { throw IOException("dns") }
        assertEquals(ConnectionCheck.UNREACHABLE, linker().testConnection())
        assertTrue(links.isLinked)
    }
}
