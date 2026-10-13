package com.apercallc.aidailymemory.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import com.apercallc.aidailymemory.recording.server.HostedServer
import com.apercallc.aidailymemory.recording.server.ServerAddress
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the link stands, for the settings screen. */
enum class LinkProblem {
    NONE,

    /** The server refused the token (revoked or expired): reconnect with a new one. */
    REJECTED,

    /** The token can't be decrypted any more (Keystore reset): reconnect. */
    UNREADABLE,

    /**
     * The stored server address is not a plain https origin, so the link was dropped and its
     * token is never sent anywhere.
     */
    OTHER_SERVER,
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

    private val _serverUrl = MutableStateFlow(
        (ServerAddress.parse(prefs.getString(KEY_SERVER, null).orEmpty()) as? ServerAddress.Valid)?.origin
            ?: HostedServer.BASE_URL,
    )

    /**
     * The server the next sign-in goes to. Starts as the hosted server; Advanced lets a person
     * point it at their own copy of the backend. While a link exists the link's own address wins.
     */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    /** [origin] must come from [ServerAddress.parse]; a null resets to the hosted server. */
    fun setServerUrl(origin: String?) {
        val value = origin ?: HostedServer.BASE_URL
        prefs.edit { if (origin == null) remove(KEY_SERVER) else putString(KEY_SERVER, value) }
        _serverUrl.value = value
    }

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
        if (prefs.getBoolean(KEY_OTHER_SERVER, false)) _problem.value = LinkProblem.OTHER_SERVER
        _revokePending.value = readRevokes().length() > 0
        _revokeManual.value = prefs.getBoolean(KEY_REVOKE_MANUAL, false)
        val stored = prefs.getString(KEY_URL, null) ?: return
        // The server may be the hosted one or the person's own, but never an address that is not
        // a plain https origin: a token is not sent to anything else.
        val url = (EndpointUrl.parse(stored) as? EndpointUrl.Valid)?.value?.trimEnd('/')
        if (url == null) {
            logFailure(TAG, "link_invalid_server", IllegalStateException("not_https_origin"), warning = true)
            forget(LinkProblem.OTHER_SERVER)
            return
        }
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
        require(EndpointUrl.parse(link.baseUrl) is EndpointUrl.Valid) { "link must be an https address" }
        val sealed = aead().encrypt(link.token.toByteArray(Charsets.UTF_8), AD)
        prefs.edit {
            putString(KEY_URL, link.baseUrl)
            putString(KEY_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP))
            putLong(KEY_SINCE, if (includeBacklog) 0 else linkedAtEpochMs)
            putBoolean(KEY_REJECTED, false)
            remove(KEY_OTHER_SERVER)
        }
        loaded = true
        _link.value = link
        _problem.value = LinkProblem.NONE
        // A fresh, working link: the old-token notice has done its job.
        prefs.edit { remove(KEY_REVOKE_MANUAL) }
        _revokeManual.value = false
    }

    /** Forgets the token (Disconnect). */
    @Synchronized
    fun clear() = forget(LinkProblem.NONE)

    /**
     * Disconnect: forgets the link for uploads but keeps the token, still sealed, only until
     * the server has revoked it. Several can wait at once. If one can't be sealed, the user is
     * told to have it revoked on the server ([revokeManual]).
     */
    @Synchronized
    fun clearKeepingForRevoke(link: ServerLink) {
        try {
            val sealed = aead().encrypt(link.token.toByteArray(Charsets.UTF_8), REVOKE_AD)
            val entry = JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("url", link.baseUrl)
                .put("token", Base64.encodeToString(sealed, Base64.NO_WRAP))
            writeRevokes(readRevokes().put(entry))
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "revoke_keep_failed", e, warning = true)
            markRevokeManual()
        }
        forget(LinkProblem.NONE)
    }

    /** Tokens still waiting for their server-side revoke, by entry id. */
    @Synchronized
    fun pendingRevokes(): Map<String, ServerLink> {
        val entries = readRevokes()
        val pending = mutableMapOf<String, ServerLink>()
        val kept = JSONArray()
        var dropped = false
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i)
            val id = entry?.optString("id").orEmpty()
            val url = entry?.optString("url").orEmpty()
            if (id.isEmpty() || EndpointUrl.parse(url) !is EndpointUrl.Valid) {
                // A damaged entry, or one for an address that is not https: a token is never
                // sent there. Dropped, and the person is told to revoke it on the server.
                logFailure(TAG, "revoke_entry_invalid", IllegalStateException("invalid_entry"), warning = true)
                dropped = true
                continue
            }
            try {
                val token = aead().decrypt(Base64.decode(entry!!.getString("token"), Base64.NO_WRAP), REVOKE_AD)
                pending[id] = ServerLink(url, token.toString(Charsets.UTF_8))
                kept.put(entry)
            } catch (e: GeneralSecurityException) {
                // Unreadable here, but possibly still valid on the server: tell the user.
                logFailure(TAG, "revoke_unreadable", e, warning = true)
                dropped = true
            } catch (e: JSONException) {
                logFailure(TAG, "revoke_entry_invalid", e, warning = true)
                dropped = true
            } catch (e: IllegalArgumentException) {
                logFailure(TAG, "revoke_entry_invalid", e, warning = true)
                dropped = true
            }
        }
        if (dropped) {
            writeRevokes(kept)
            markRevokeManual()
        }
        return pending
    }

    @Synchronized
    fun revokeDone(id: String) {
        val entries = readRevokes()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") != id) kept.put(entry)
        }
        writeRevokes(kept)
    }

    private val _revokeManual = MutableStateFlow(false)

    /** A token this phone could not revoke itself: the user should have it revoked server-side. */
    val revokeManual: StateFlow<Boolean> = _revokeManual.asStateFlow()

    private fun markRevokeManual() {
        prefs.edit { putBoolean(KEY_REVOKE_MANUAL, true) }
        _revokeManual.value = true
    }

    private fun readRevokes(): JSONArray = try {
        JSONArray(prefs.getString(KEY_REVOKES, "[]"))
    } catch (e: JSONException) {
        logFailure(TAG, "revokes_invalid", e, warning = true)
        JSONArray()
    }

    private fun writeRevokes(entries: JSONArray) {
        prefs.edit { putString(KEY_REVOKES, entries.toString()) }
        _revokePending.value = entries.length() > 0
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
            if (problem == LinkProblem.OTHER_SERVER) putBoolean(KEY_OTHER_SERVER, true) else remove(KEY_OTHER_SERVER)
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
        private const val KEY_SERVER = "preferred_server"
        private const val KEY_REJECTED = "rejected"
        private const val KEY_OTHER_SERVER = "other_server"
        private const val KEY_REVOKES = "pending_revokes"
        private const val KEY_REVOKE_MANUAL = "revoke_manual"
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
        fun keystoreAead(context: Context): Aead = keystoreAead(context, KEYSET, KEYSET_PREFS, MASTER_KEY_URI)

        /** A separate Keystore-wrapped AES-256-GCM key per purpose. */
        fun keystoreAead(context: Context, keyset: String, prefs: String, masterKeyUri: String): Aead {
            try {
                AeadConfig.register()
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, keyset, prefs)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(masterKeyUri)
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
