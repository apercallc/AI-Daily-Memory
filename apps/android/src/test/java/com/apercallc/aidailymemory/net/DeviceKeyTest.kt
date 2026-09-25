package com.apercallc.aidailymemory.net

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Interop with the backend: the ciphertext below was produced by backend/app/sealing.py
 * (Python `cryptography` HPKE) for a fixed, test-only key. If Tink and the server ever
 * disagree on the suite, the info binding, or the wire format, this fails.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceKeyTest {
    private val privateKey = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val publicKey = hex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c")
    private val uploadId = "01890000-0000-7000-8000-000000000001"
    private val sealedByBackend = hex(
        "e5fc4a5199114dc93a72d2bfdac4e5999ad3830925eabd7fd073b0a19a1cda168868d98fd41f7a955cfe1eb8d8da85d1" +
            "95e667a13958066bcc3e1254a1f0b0662068650c797f60ffff197ca882d79f8fbfd951aca4ff0428ed8a4bc1fc366d70" +
            "887b99ec54abb1382fb947c07140c16e9207ce2f5874afaa9912985983115efa529d07ea35adf8eca6806fb4801910d7" +
            "16c660c337ac26855b29f587971f0aaceb9908dd401a6ec7a5a9348530254900814cd18c1621cde6e186bf56159046b5" +
            "c51ade48ab29039a30888156218aaf2463cbfd13c4c104ca24bbeb2c44166fdfc66b0a7dd51117a3311541dec271fc64" +
            "3f0c69ccdc316a160fb9f8fb6039bc779a69ce7396c63240a3518816e99349d2585d8041a28532145517e5b6aef01e73" +
            "183246308346ea65ee6c000a0c5e704a774b89de66ee1bf3d19a34b4ac6a14815a67c8a869ca5533ee17558ebfa5f141" +
            "361a71a2c80540fdbcf235d9eb0a66187d1a4c5982292ec1b6c7764571880e19",
    )

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun fixedKey(): DeviceKey {
        HybridConfig.register()
        val parameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        val public = HpkePublicKey.create(parameters, Bytes.copyFrom(publicKey), null)
        val private = HpkePrivateKey.create(public, SecretBytes.copyFrom(privateKey, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(private).withRandomId().makePrimary())
            .build()
        return DeviceKey(handle)
    }

    @Test
    fun `a transcript sealed by the backend opens on the phone`() {
        val key = fixedKey()

        val payload = JSONObject(String(key.open(sealedByBackend, uploadId), Charsets.UTF_8))

        assertEquals(1, payload.getInt("v"))
        assertEquals(uploadId, payload.getString("upload_id"))
        assertEquals("Let's ship on Friday.", payload.getJSONArray("spans").getJSONObject(0).getString("text"))
        assertEquals(publicKey.toList(), key.publicKey.toList())
    }

    @Test
    fun `a sealed transcript moved onto another upload does not open`() {
        assertFailsWith<GeneralSecurityException> {
            fixedKey().open(sealedByBackend, "01890000-0000-7000-8000-000000000002")
        }
    }

    @Test
    fun `a transcript sealed to another phone does not open`() {
        HybridConfig.register()
        val other = DeviceKey(
            KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW")),
        )

        assertEquals(32, other.publicKey.size)
        assertFailsWith<GeneralSecurityException> { other.open(sealedByBackend, uploadId) }
    }
}
