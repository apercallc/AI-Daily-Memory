package com.apercallc.aidailymemory.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    private val link = ServerLink("https://api.example", token)

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
        val newer = ServerLink("https://api.example", "adm_" + "N".repeat(43))
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
        val trimmed = LinkInput.parse(" https://api.example/ ", " $token ")
        assertIs<LinkInput.Valid>(trimmed)
        assertEquals("https://api.example", trimmed.link.baseUrl)
        assertEquals(LinkInput.InvalidUrl, LinkInput.parse("http://api.example", token))
        assertEquals(LinkInput.InvalidUrl, LinkInput.parse("https://", token))
        assertEquals(LinkInput.InvalidUrl, LinkInput.parse("https://a b", token))
        assertEquals(LinkInput.InvalidToken, LinkInput.parse("https://api.example", "password123"))
        assertEquals(LinkInput.InvalidToken, LinkInput.parse("https://api.example", "adm_short"))
    }

    @Test
    fun `links refuse cleartext except loopback, and never print the token`() {
        assertFailsWith<IllegalArgumentException> { ServerLink("http://api.example", token) }
        ServerLink("http://127.0.0.1:8080", token)
        ServerLink("http://localhost", token)

        assertFalse(ServerLink("https://api.example", token).toString().contains(token))
    }
}
