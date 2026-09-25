package com.apercallc.aidailymemory.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
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
    private val url = "https://api.example"

    private class FakeAccountApi : AccountApi {
        var onMe: () -> String = { "phone_only" }
        var onRevoke: () -> Unit = {}
        val calls = mutableListOf<String>()

        override fun me(link: ServerLink): String = onMe().also { calls += "me" }

        override fun setStorageMode(link: ServerLink, mode: String) {
            calls += "setStorageMode"
        }

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

    private var register: (ServerLink) -> Unit = {}

    private fun linker(store: LinkStore = links) = AccountLinker(
        store,
        api,
        onLinked = { linked++ },
        onUnlinked = { unlinked++ },
        onRevokePending = { revokeRetries++ },
        registerKey = { register(it) },
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
        assertNull(linker().connect(url, token, includeBacklog = false))

        assertEquals(ServerLink(url, token), links.link.value)
        assertEquals(42L, links.uploadSinceEpochMs)
        assertEquals(1, linked)
    }

    @Test
    fun `bad input never reaches the server`() {
        assertEquals(ConnectError.INVALID_URL, linker().connect("http://x", token, false))
        assertEquals(ConnectError.INVALID_TOKEN, linker().connect(url, "nope", false))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a token the server refuses is reported and not stored`() {
        api.onMe = { throw ApiException(401, "unauthorized") }

        assertEquals(ConnectError.REJECTED, linker().connect(url, token, false))
        assertNull(links.link.value)
        assertEquals(0, linked)
    }

    @Test
    fun `an unreachable or failing server is reported and nothing is stored`() {
        api.onMe = { throw IOException("dns") }
        assertEquals(ConnectError.UNREACHABLE, linker().connect(url, token, false))
        api.onMe = { throw ApiException(503, "service_unavailable") }
        assertEquals(ConnectError.UNREACHABLE, linker().connect(url, token, false))

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

        val error = linker(LinkStore(prefs) { broken }).connect(url, token, false)

        assertEquals(ConnectError.SAVE_FAILED, error)
        assertEquals(0, linked)
    }

    @Test
    fun `disconnect forgets locally first, then revokes on the server`() {
        linker().connect(url, token, false)

        linker().disconnect()

        assertNull(links.link.value)
        assertEquals(1, unlinked)
        assertEquals(listOf("me", "revoke"), api.calls)
    }

    @Test
    fun `an offline revoke still disconnects, is remembered, and is retried later`() {
        linker().connect(url, token, false)
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
        linker().connect(url, token, false)
        api.onRevoke = { throw ApiException(401, "unauthorized") }

        linker().disconnect()

        assertFalse(links.revokePending.value)
        assertEquals(0, revokeRetries)
    }

    @Test
    fun `a server error keeps the revoke pending`() {
        linker().connect(url, token, false)
        api.onRevoke = { throw ApiException(503, "service_unavailable") }

        linker().disconnect()

        assertTrue(links.revokePending.value)
    }

    @Test
    fun `several disconnected tokens wait and are all revoked, before connecting again`() {
        api.onRevoke = { throw IOException("offline") }
        linker().connect(url, token, false)
        linker().disconnect()
        linker().connect(url, "adm_" + "M".repeat(43), false)
        linker().disconnect()
        assertEquals(2, links.pendingRevokes().size)

        api.onRevoke = {}
        linker().connect(url, "adm_" + "N".repeat(43), false)

        assertTrue(links.pendingRevokes().isEmpty())
        assertFalse(links.revokePending.value)
    }

    @Test
    fun `the device key is registered while connecting, and a mismatch refuses the link`() {
        val registered = mutableListOf<ServerLink>()
        register = { registered += it }
        assertNull(linker().connect(url, token, false))
        assertEquals(listOf(ServerLink(url, token)), registered)

        links.clear()
        register = { throw ApiException(409, "device_key_mismatch") }
        assertEquals(ConnectError.REJECTED, linker().connect(url, token, false))
        assertNull(links.link.value)

        register = { throw GeneralSecurityException("no keystore") }
        assertEquals(ConnectError.SAVE_FAILED, linker().connect(url, token, false))
    }

    @Test
    fun `disconnecting when not linked does nothing`() {
        linker().disconnect()

        assertEquals(0, unlinked)
        assertTrue(api.calls.isEmpty())
    }
}
