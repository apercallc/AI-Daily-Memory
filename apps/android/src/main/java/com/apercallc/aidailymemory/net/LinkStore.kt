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
import java.security.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Where the link stands, for the settings screen. */
enum class LinkProblem {
    NONE,

    /** The server refused the token (revoked or expired): reconnect with a new one. */
    REJECTED,

    /** The token can't be decrypted any more (Keystore reset): reconnect. */
    UNREADABLE,
}

/**
 * The account link: backend URL plus device token (AGENTS rule 4: a user-owned secret).
 *
 * The token is encrypted with a Keystore-wrapped AEAD key and bound to its purpose as
 * associated data; the file is excluded from backup and device transfer like all app data.
 * If the key is lost the link reads as absent and [problem] says so: a secret that can't be
 * decrypted is never guessed at or silently replaced.
 *
 * Nothing touches the Keystore until [load] (call it off the main thread).
 */
class LinkStore(private val prefs: SharedPreferences, private val aead: () -> Aead) {
    private val _link = MutableStateFlow<ServerLink?>(null)
    val link: StateFlow<ServerLink?> = _link.asStateFlow()

    private val _problem = MutableStateFlow(LinkProblem.NONE)
    val problem: StateFlow<LinkProblem> = _problem.asStateFlow()

    private val _revokePending = MutableStateFlow(false)

    /** A disconnected token the server hasn't revoked yet (AGENTS rule 4): retried online. */
    val revokePending: StateFlow<Boolean> = _revokePending.asStateFlow()

    @Volatile private var loaded = false

    val isLinked: Boolean get() = _link.value != null

    /**
     * Upload only audio captured at or after this time (epoch ms): audio recorded before the
     * account was linked stays on the phone unless the user chose to include it. 0 = all.
     */
    val uploadSinceEpochMs: Long get() = prefs.getLong(KEY_SINCE, Long.MAX_VALUE)

    /** [load] on the IO dispatcher. */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) { load() }

    /** Reads the stored link once. Blocking Keystore work: call from a background thread. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        if (prefs.getBoolean(KEY_REJECTED, false)) _problem.value = LinkProblem.REJECTED
        _revokePending.value = prefs.contains(KEY_REVOKE_TOKEN)
        val url = prefs.getString(KEY_URL, null) ?: return
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return
        try {
            val token = aead().decrypt(Base64.decode(sealed, Base64.NO_WRAP), AD).toString(Charsets.UTF_8)
            _link.value = ServerLink(url, token)
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "link_unreadable", e, warning = true)
            _problem.value = LinkProblem.UNREADABLE
        } catch (e: IllegalArgumentException) {
            logFailure(TAG, "link_invalid", e, warning = true)
            _problem.value = LinkProblem.UNREADABLE
        }
    }

    /**
     * Stores the link. [includeBacklog] = upload speech recorded before now as well.
     * @throws GeneralSecurityException if the token can't be sealed (nothing is stored).
     */
    @Synchronized
    fun save(link: ServerLink, linkedAtEpochMs: Long, includeBacklog: Boolean) {
        val sealed = aead().encrypt(link.token.toByteArray(Charsets.UTF_8), AD)
        prefs.edit {
            putString(KEY_URL, link.baseUrl)
            putString(KEY_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP))
            putLong(KEY_SINCE, if (includeBacklog) 0 else linkedAtEpochMs)
            putBoolean(KEY_REJECTED, false)
        }
        loaded = true
        _link.value = link
        _problem.value = LinkProblem.NONE
    }

    /** Forgets the token (Disconnect). */
    @Synchronized
    fun clear() = forget(LinkProblem.NONE)

    /**
     * Disconnect: forgets the link for uploads but keeps the token, still sealed, only until
     * the server has revoked it. If it can't be sealed, it is simply forgotten.
     */
    @Synchronized
    fun clearKeepingForRevoke(link: ServerLink) {
        try {
            val sealed = aead().encrypt(link.token.toByteArray(Charsets.UTF_8), REVOKE_AD)
            prefs.edit {
                putString(KEY_REVOKE_URL, link.baseUrl)
                putString(KEY_REVOKE_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP))
            }
            _revokePending.value = true
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "revoke_keep_failed", e, warning = true)
        }
        forget(LinkProblem.NONE)
    }

    /** The token still waiting for its server-side revoke, if any. */
    @Synchronized
    fun pendingRevoke(): ServerLink? {
        val url = prefs.getString(KEY_REVOKE_URL, null) ?: return null
        val sealed = prefs.getString(KEY_REVOKE_TOKEN, null) ?: return null
        return try {
            ServerLink(url, aead().decrypt(Base64.decode(sealed, Base64.NO_WRAP), REVOKE_AD).toString(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            // Can't be read any more, so it can't be used either: nothing left to revoke from here.
            logFailure(TAG, "revoke_unreadable", e, warning = true)
            revokeDone()
            null
        }
    }

    @Synchronized
    fun revokeDone() {
        prefs.edit {
            remove(KEY_REVOKE_URL)
            remove(KEY_REVOKE_TOKEN)
        }
        _revokePending.value = false
    }

    /**
     * The server refused [rejected]. Clears only if it is still the stored link, so a run that
     * started with an old token can't wipe a token the user entered since.
     */
    @Synchronized
    fun clearIfCurrent(rejected: ServerLink): Boolean {
        if (_link.value != rejected) return false
        forget(LinkProblem.REJECTED)
        return true
    }

    private fun forget(problem: LinkProblem) {
        prefs.edit {
            remove(KEY_URL)
            remove(KEY_TOKEN)
            remove(KEY_SINCE)
            putBoolean(KEY_REJECTED, problem == LinkProblem.REJECTED)
        }
        loaded = true
        _link.value = null
        _problem.value = problem
    }

    companion object {
        const val FILE_NAME = "server_link"
        private const val TAG = "LinkStore"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token_sealed"
        private const val KEY_SINCE = "upload_since_epoch_ms"
        private const val KEY_REJECTED = "rejected"
        private const val KEY_REVOKE_URL = "revoke_base_url"
        private const val KEY_REVOKE_TOKEN = "revoke_token_sealed"
        private val REVOKE_AD = "aidm:server-token:revoke:v1".toByteArray(Charsets.UTF_8)
        private val AD = "aidm:server-token:v1".toByteArray(Charsets.UTF_8)
        private const val KEYSET = "link_keyset"
        private const val KEYSET_PREFS = "link_keys"
        private const val MASTER_KEY_URI = "android-keystore://aidm_link_master_key"

        /**
         * Keystore-backed AEAD; refuses Tink's cleartext-keyset fallback. Keystore failures
         * that surface as the unchecked ProviderException are converted, so callers only
         * handle GeneralSecurityException.
         */
        fun keystoreAead(context: Context): Aead {
            try {
                AeadConfig.register()
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, KEYSET, KEYSET_PREFS)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                if (!manager.isUsingKeystore) throw GeneralSecurityException("Android Keystore unavailable")
                val aead = manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
                return KeystoreGuard(aead)
            } catch (e: IOException) {
                throw GeneralSecurityException("keyset unreadable", e)
            } catch (e: ProviderException) {
                throw GeneralSecurityException("keystore failure", e)
            }
        }
    }
}

/** Converts ProviderException from Keystore-backed operations into a checked failure. */
internal class KeystoreGuard(private val inner: Aead) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = guard {
        inner.encrypt(plaintext, associatedData)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = guard {
        inner.decrypt(ciphertext, associatedData)
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: ProviderException) {
        throw GeneralSecurityException("keystore failure", e)
    }
}
