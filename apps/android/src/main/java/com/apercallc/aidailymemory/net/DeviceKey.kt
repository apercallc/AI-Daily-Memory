package com.apercallc.aidailymemory.net

import android.content.Context
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * This phone's HPKE key pair (ADR-0014): the server seals transcripts to [publicKey] and only
 * this phone can open them. X25519 / HKDF-SHA256 / AES-256-GCM, raw ciphertext `enc || ct`,
 * matching backend/app/sealing.py. The private keyset is wrapped by an Android Keystore key
 * and excluded from backup like all app data, so a new phone gets a new key by design.
 */
class DeviceKey(private val handle: KeysetHandle) {
    private val decrypt = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    /** Raw 32-byte X25519 public key, as the server expects it. */
    val publicKey: ByteArray
        get() = (handle.publicKeysetHandle.primary.key as HpkePublicKey).publicKeyBytes.toByteArray()

    /**
     * Opens a transcript sealed for [uploadId]. The upload id is bound in as HPKE `info`, so a
     * ciphertext moved onto another clip fails to open.
     * @throws GeneralSecurityException if it wasn't sealed to this key for this upload.
     */
    fun open(ciphertext: ByteArray, uploadId: String): ByteArray = try {
        decrypt.decrypt(ciphertext, (INFO_PREFIX + uploadId).toByteArray(Charsets.US_ASCII))
    } catch (e: ProviderException) {
        throw GeneralSecurityException("keystore failure", e)
    }

    companion object {
        const val INFO_PREFIX = "aidm:delivery:v1:"
        private const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        private const val KEYSET = "device_hpke_keyset"
        private const val KEYSET_PREFS = "device_keys"
        private const val MASTER_KEY_URI = "android-keystore://aidm_device_hpke_master_key"

        /** Loads or creates the phone's key. Blocking Keystore work: background thread only. */
        fun keystore(context: Context): DeviceKey {
            try {
                HybridConfig.register()
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, KEYSET, KEYSET_PREFS)
                    .withKeyTemplate(KeyTemplates.get(TEMPLATE))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                if (!manager.isUsingKeystore) throw GeneralSecurityException("Android Keystore unavailable")
                return DeviceKey(manager.keysetHandle)
            } catch (e: IOException) {
                throw GeneralSecurityException("keyset unreadable", e)
            } catch (e: ProviderException) {
                throw GeneralSecurityException("keystore failure", e)
            }
        }
    }
}
