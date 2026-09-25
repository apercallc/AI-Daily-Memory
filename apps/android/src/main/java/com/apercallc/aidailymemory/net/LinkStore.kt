package com.apercallc.aidailymemory.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.apercallc.aidailymemory.platform.logFailure
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The account link: backend URL plus device token (AGENTS rule 4: a user-owned secret).
 *
 * The token is encrypted with a Keystore-wrapped AEAD key and bound to its purpose as
 * associated data; the file is excluded from backup and device transfer like all app data.
 * If the key is lost (Keystore reset), the link reads as absent and the user reconnects:
 * a secret that can't be decrypted is never guessed at or silently replaced.
 */
class LinkStore(private val prefs: SharedPreferences, private val aead: () -> Aead) {
    private val _link = MutableStateFlow(read())
    val link: StateFlow<ServerLink?> = _link.asStateFlow()

    private val _rejected = MutableStateFlow(prefs.getBoolean(KEY_REJECTED, false))

    /** The server refused the token (revoked or expired): the user must reconnect. */
    val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

    val isLinked: Boolean get() = _link.value != null

    fun save(link: ServerLink) {
        val sealed = aead().encrypt(link.token.toByteArray(Charsets.UTF_8), AD)
        prefs.edit {
            putString(KEY_URL, link.baseUrl)
            putString(KEY_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP))
            putBoolean(KEY_REJECTED, false)
        }
        _link.value = link
        _rejected.value = false
    }

    /** Forgets the token; [rejected] records whether the server forced it. */
    fun clear(rejected: Boolean = false) {
        prefs.edit {
            remove(KEY_URL)
            remove(KEY_TOKEN)
            putBoolean(KEY_REJECTED, rejected)
        }
        _link.value = null
        _rejected.value = rejected
    }

    private fun read(): ServerLink? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val token = aead().decrypt(Base64.decode(sealed, Base64.NO_WRAP), AD).toString(Charsets.UTF_8)
            ServerLink(url, token)
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "link_unreadable", e, warning = true)
            null
        } catch (e: IllegalArgumentException) {
            logFailure(TAG, "link_invalid", e, warning = true)
            null
        }
    }

    companion object {
        const val FILE_NAME = "server_link"
        private const val TAG = "LinkStore"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token_sealed"
        private const val KEY_REJECTED = "rejected"
        private val AD = "aidm:server-token:v1".toByteArray(Charsets.UTF_8)
        private const val KEYSET = "link_keyset"
        private const val KEYSET_PREFS = "link_keys"
        private const val MASTER_KEY_URI = "android-keystore://aidm_link_master_key"

        /** Keystore-backed AEAD; refuses Tink's cleartext-keyset fallback. */
        fun keystoreAead(context: Context): Aead {
            try {
                AeadConfig.register()
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, KEYSET, KEYSET_PREFS)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                if (!manager.isUsingKeystore) throw GeneralSecurityException("Android Keystore unavailable")
                return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            } catch (e: IOException) {
                throw GeneralSecurityException("keyset unreadable", e)
            }
        }
    }
}
