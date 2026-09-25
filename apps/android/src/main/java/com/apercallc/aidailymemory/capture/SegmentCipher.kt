package com.apercallc.aidailymemory.capture

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/** Local audio encryption is unusable. Capture must stop rather than write plaintext. */
class EncryptionUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Streaming AEAD for local segments (ADR-0007): AES-256-GCM-HKDF with 4 KB ciphertext
 * segments, keyset wrapped by an Android Keystore key without user authentication, so capture
 * keeps working while the phone is locked.
 *
 * The segment id is the associated data: a ciphertext cannot be swapped onto another
 * segment's record without failing authentication.
 *
 * Key lifecycle: the keyset is created once and never rotated automatically. HKDF derives a
 * fresh key per segment from a random salt, so there is no nonce-reuse limit to rotate for.
 * If the Keystore key is lost or invalidated, [open] fails, capture reports
 * ENCRYPTION_UNAVAILABLE, and existing unacknowledged segments are unreadable; they are
 * never replaced by a silently regenerated key.
 */
class SegmentCipher internal constructor(private val aead: StreamingAead) {
    fun encrypting(output: OutputStream, segmentId: String): OutputStream =
        aead.newEncryptingStream(output, segmentId.toByteArray(Charsets.UTF_8))

    fun decrypting(input: InputStream, segmentId: String): InputStream =
        aead.newDecryptingStream(input, segmentId.toByteArray(Charsets.UTF_8))

    companion object {
        private const val PREFS = "segment_keys"
        private const val KEYSET = "segment_keyset"
        private const val MASTER_KEY_URI = "android-keystore://aidm_segment_master_key"

        @Throws(EncryptionUnavailableException::class)
        fun open(context: Context): SegmentCipher {
            try {
                StreamingAeadConfig.register()
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, KEYSET, PREFS)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                // Tink silently falls back to a cleartext keyset when the Keystore is
                // unusable. That would put the audio key next to the audio: refuse instead.
                if (!manager.isUsingKeystore) {
                    throw EncryptionUnavailableException("Android Keystore unavailable")
                }
                val aead = manager.keysetHandle
                    .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
                return SegmentCipher(aead)
            } catch (e: GeneralSecurityException) {
                throw EncryptionUnavailableException("keyset unusable", e)
            } catch (e: IOException) {
                throw EncryptionUnavailableException("keyset unreadable", e)
            }
        }
    }
}
