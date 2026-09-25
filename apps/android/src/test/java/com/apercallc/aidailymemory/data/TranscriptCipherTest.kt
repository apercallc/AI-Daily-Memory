package com.apercallc.aidailymemory.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class TranscriptCipherTest {
    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val key = aead()
    private val cipher = TranscriptCipher { key }

    private fun line(sealed: ByteArray, uploadId: String = "u1", idx: Int = 0) =
        TranscriptLine(uploadId, idx, 1_000, "UTC", sealed)

    @Test
    fun `lines round-trip and are not readable at rest`() {
        val sealed = cipher.seal("u1", 0, "Let's ship Friday.", "A")

        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Friday"))
        assertEquals(TranscriptText("u1:0", 1_000, "UTC", "Let's ship Friday.", "A"), cipher.open(line(sealed)))
        assertNull(cipher.open(line(cipher.seal("u1", 1, "x", null), idx = 1))!!.speaker)
    }

    @Test
    fun `a sealed line moved to another transcript or position does not open`() {
        val sealed = cipher.seal("u1", 0, "private words", null)

        assertNull(cipher.open(line(sealed, uploadId = "u2")))
        assertNull(cipher.open(line(sealed, idx = 1)))
    }

    @Test
    fun `a lost key reads as unreadable, not garbage`() {
        val sealed = cipher.seal("u1", 0, "private words", null)
        val other = aead()

        assertNull(TranscriptCipher { other }.open(line(sealed)))
    }
}
