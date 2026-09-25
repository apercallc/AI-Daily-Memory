package com.apercallc.aidailymemory.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
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

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `a saved link survives a restart and the token is never stored in the clear`() {
        val aead = newAead()
        LinkStore(prefs) { aead }.save(ServerLink("https://api.example", token))

        val reopened = LinkStore(prefs) { aead }

        assertEquals(ServerLink("https://api.example", token), reopened.link.value)
        assertTrue(reopened.isLinked)
        assertFalse(prefs.all.values.any { it.toString().contains(token) })
    }

    @Test
    fun `a token sealed under a lost key reads as not linked, never garbage`() {
        LinkStore(prefs) { newAead() }.save(ServerLink("https://api.example", token))

        val withNewKey = LinkStore(prefs) { newAead() }

        assertNull(withNewKey.link.value)
    }

    @Test
    fun `a rejected link is cleared and remembered until the user connects again`() {
        val aead = newAead()
        val store = LinkStore(prefs) { aead }
        store.save(ServerLink("https://api.example", token))

        store.clear(rejected = true)
        assertTrue(LinkStore(prefs) { aead }.rejected.value)
        assertNull(store.link.value)

        store.save(ServerLink("https://api.example", token))
        assertFalse(store.rejected.value)
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
