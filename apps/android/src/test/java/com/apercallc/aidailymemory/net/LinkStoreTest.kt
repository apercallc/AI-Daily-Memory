package com.apercallc.aidailymemory.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.recording.server.HostedServer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.security.GeneralSecurityException
import java.security.ProviderException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LinkStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("link-test", Context.MODE_PRIVATE)
    private val token = "adm_" + "Z".repeat(43)
    private val link = ServerLink(HostedServer.BASE_URL, token)

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun reopened(aead: Aead) = LinkStore(prefs) { aead }.also { it.load() }

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `nothing is read from the keystore until load`() {
        var touched = false
        val store = LinkStore(prefs) {
            touched = true
            newAead()
        }

        assertNull(store.link.value)
        assertFalse(touched)
    }

    @Test
    fun `a saved link survives a restart and the token is never stored in the clear`() {
        val aead = newAead()
        LinkStore(prefs) { aead }.save(link, linkedAtEpochMs = 1_000, includeBacklog = false)

        val store = reopened(aead)

        assertEquals(link, store.link.value)
        assertTrue(store.isLinked)
        assertEquals(1_000, store.uploadSinceEpochMs)
        assertFalse(prefs.all.values.any { it.toString().contains(token) })
    }

    @Test
    fun `including the backlog uploads everything and not linked uploads nothing`() {
        val aead = newAead()
        val store = LinkStore(prefs) { aead }
        assertEquals(Long.MAX_VALUE, store.uploadSinceEpochMs)

        store.save(link, linkedAtEpochMs = 1_000, includeBacklog = true)

        assertEquals(0, store.uploadSinceEpochMs)
    }

    @Test
    fun `a token sealed under a lost key reads as not linked and says why`() {
        LinkStore(prefs) { newAead() }.save(link, 0, false)

        val withNewKey = reopened(newAead())

        assertNull(withNewKey.link.value)
        assertEquals(LinkProblem.UNREADABLE, withNewKey.problem.value)
    }

    @Test
    fun `a rejection clears only the link that was rejected`() {
        val aead = newAead()
        val store = LinkStore(prefs) { aead }
        store.save(link, 0, false)
        val newer = ServerLink(HostedServer.BASE_URL, "adm_" + "N".repeat(43))
        store.save(newer, 0, false)

        assertFalse(store.clearIfCurrent(link)) // a run that started with the old token
        assertEquals(newer, store.link.value)

        assertTrue(store.clearIfCurrent(newer))
        assertNull(store.link.value)
        assertEquals(LinkProblem.REJECTED, reopened(aead).problem.value)

        store.save(link, 0, false)
        assertEquals(LinkProblem.NONE, store.problem.value)
    }

    @Test
    fun `disconnecting is not a rejection`() {
        val aead = newAead()
        val store = LinkStore(prefs) { aead }
        store.save(link, 0, false)

        store.clear()

        assertEquals(LinkProblem.NONE, reopened(aead).problem.value)
        assertEquals(Long.MAX_VALUE, store.uploadSinceEpochMs)
    }

    @Test
    fun `disconnected tokens wait sealed for their revoke and survive a restart`() {
        val aead = newAead()
        val store = LinkStore(prefs) { aead }
        store.save(link, 0, false)

        store.clearKeepingForRevoke(link)

        assertFalse(prefs.all.values.any { it.toString().contains(token) })
        val reopened = reopened(aead)
        assertTrue(reopened.revokePending.value)
        val (id, pending) = reopened.pendingRevokes().entries.single()
        assertEquals(link, pending)
        reopened.revokeDone(id)
        assertFalse(reopened.revokePending.value)
    }

    @Test
    fun `a pending revoke that can no longer be read asks the user to revoke it server-side`() {
        LinkStore(prefs) { newAead() }.clearKeepingForRevoke(link)

        val other = reopened(newAead())

        assertTrue(other.pendingRevokes().isEmpty())
        assertTrue(other.revokeManual.value)
        other.save(link, 0, false)
        assertFalse(other.revokeManual.value)
    }

    @Test
    fun `keystore provider failures become checked security failures`() {
        val failing = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw ProviderException("tee busy")

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
                throw ProviderException("tee busy")
        }
        val guarded = KeystoreGuard(failing)

        assertFailsWith<GeneralSecurityException> { guarded.encrypt(ByteArray(1), ByteArray(0)) }
        assertFailsWith<GeneralSecurityException> { guarded.decrypt(ByteArray(1), ByteArray(0)) }
        val store = LinkStore(prefs) { guarded }
        assertFailsWith<GeneralSecurityException> { store.save(link, 0, false) }
        assertNull(store.link.value) // nothing half-saved
    }

    @Test
    fun `input is validated before anything is stored`() {
        val trimmed = LinkInput.parse(" $token ")
        assertIs<LinkInput.Valid>(trimmed)
        assertEquals(HostedServer.BASE_URL, trimmed.link.baseUrl)
        assertEquals("https://api.example", (LinkInput.parse(token, "https://api.example") as LinkInput.Valid).link.baseUrl)
        assertEquals(LinkInput.InvalidToken, LinkInput.parse("password123"))
        assertEquals(LinkInput.InvalidToken, LinkInput.parse("adm_short"))
    }

    @Test
    fun `a link to the person's own https server is kept`() {
        val aead = newAead()
        val own = ServerLink("https://api.example", token)
        reopened(aead).save(own, 5, false)

        assertEquals(own, reopened(aead).link.value)
        assertEquals(LinkProblem.NONE, reopened(aead).problem.value)
    }

    @Test
    fun `the server defaults to hosted, can be set, and resets`() {
        val store = reopened(newAead())
        assertEquals(HostedServer.BASE_URL, store.serverUrl.value)

        store.setServerUrl("https://api.example")
        assertEquals("https://api.example", store.serverUrl.value)
        assertEquals("https://api.example", reopened(newAead()).serverUrl.value)

        store.setServerUrl(null)
        assertEquals(HostedServer.BASE_URL, store.serverUrl.value)
        assertEquals(HostedServer.BASE_URL, reopened(newAead()).serverUrl.value)
    }

    @Test
    fun `an unusable stored server falls back to hosted`() {
        prefs.edit().putString("preferred_server", "http://plain.example").commit()
        assertEquals(HostedServer.BASE_URL, reopened(newAead()).serverUrl.value)
    }

    @Test
    fun `a stored link that is not an https origin is dropped and never used`() {
        val aead = newAead()
        // Written by an earlier version, which let people type a server address.
        prefs.edit()
            .putString("base_url", "http://api.example")
            .putString("token_sealed", android.util.Base64.encodeToString(
                aead.encrypt(token.toByteArray(), "aidm:server-token:v1".toByteArray()),
                android.util.Base64.NO_WRAP,
            ))
            .commit()

        val reopened = reopened(aead)

        assertNull(reopened.link.value)
        assertEquals(LinkProblem.OTHER_SERVER, reopened.problem.value)
        // The notice survives a restart, and connecting properly clears it.
        val again = reopened(aead)
        assertEquals(LinkProblem.OTHER_SERVER, again.problem.value)
        again.save(link, 6, false)
        assertEquals(LinkProblem.NONE, again.problem.value)
        val final = reopened(aead)
        assertEquals(link, final.link.value)
        assertEquals(LinkProblem.NONE, final.problem.value)
    }

    @Test
    fun `links refuse cleartext except loopback, and never print the token`() {
        assertFailsWith<IllegalArgumentException> { ServerLink("http://api.example", token) }
        ServerLink("http://127.0.0.1:8080", token)
        ServerLink("http://localhost", token)

        assertFalse(ServerLink(HostedServer.BASE_URL, token).toString().contains(token))
    }
}
