package com.apercallc.aidailymemory.settings

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.apercallc.aidailymemory.platform.logFailure
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings for on-device processing and optional API sync (ADR-0020). Backed by
 * SharedPreferences, which are excluded from backup and device transfer like all app data
 * (data_extraction_rules.xml).
 *
 * Processing is disabled by default: the user must configure a provider (on-device or a
 * user-supplied API key) before any transcription or extraction runs. Sync is also off by
 * default until the user enters an API endpoint and auth token.
 *
 * Writes publish immediately; reads never throw. The transcription key, extraction key and
 * sync bearer token are sealed with a Keystore-backed AEAD (same scheme as the account token in
 * `LinkStore`) before they reach SharedPreferences, and are never logged. A value that can no
 * longer be decrypted (Keystore reset) reads as unset; it is never guessed at. Plaintext values
 * written by earlier versions are sealed on first read and the plaintext entry removed.
 * Nothing touches the Keystore until a secret is read or written (call off the main thread
 * where possible).
 */
class ProcessingSettings(private val prefs: SharedPreferences, private val aead: () -> Aead) {

    /** One sealed secret: cached after the first read so the Keystore is hit once. */
    private inner class SealedSecret(
        private val legacyKey: String,
        private val sealedKey: String,
        private val purpose: String,
    ) {
        private val ad = "aidm:processing-secret:$purpose:v1".toByteArray(Charsets.UTF_8)
        private var cached: String? = null
        private var read = false

        @Synchronized
        fun get(): String? {
            if (read) return cached
            read = true
            cached = load()
            return cached
        }

        @Synchronized
        fun set(value: String?) {
            read = true
            cached = value
            if (value == null) {
                prefs.edit {
                    remove(sealedKey)
                    remove(legacyKey)
                }
                return
            }
            val sealed = seal(value)
            prefs.edit {
                remove(legacyKey)
                if (sealed != null) putString(sealedKey, sealed) else remove(sealedKey)
            }
        }

        private fun seal(value: String): String? = try {
            Base64.encodeToString(aead().encrypt(value.toByteArray(Charsets.UTF_8), ad), Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            // Never fall back to plaintext: the value lives in memory until the process ends.
            logFailure(TAG, "secret_seal_failed", e, warning = true)
            null
        }

        private fun load(): String? {
            prefs.getString(sealedKey, null)?.let { sealed ->
                return try {
                    aead().decrypt(Base64.decode(sealed, Base64.NO_WRAP), ad).toString(Charsets.UTF_8)
                } catch (e: GeneralSecurityException) {
                    logFailure(TAG, "secret_unreadable", e, warning = true)
                    null
                } catch (e: IllegalArgumentException) {
                    logFailure(TAG, "secret_invalid", e, warning = true)
                    null
                }
            }
            val legacy = prefs.getString(legacyKey, null) ?: return null
            // Migrate: seal first, drop the plaintext only once the sealed copy is stored.
            seal(legacy)?.let { sealed ->
                prefs.edit {
                    putString(sealedKey, sealed)
                    remove(legacyKey)
                }
            }
            return legacy
        }
    }

    private val transcriptionKey = SealedSecret(KEY_TRANSCRIPTION_API_KEY, KEY_TRANSCRIPTION_API_KEY_SEALED, "transcription")
    private val extractionKey = SealedSecret(KEY_EXTRACTION_API_KEY, KEY_EXTRACTION_API_KEY_SEALED, "extraction")
    private val syncSecret = SealedSecret(KEY_SYNC_TOKEN, KEY_SYNC_TOKEN_SEALED, "sync")

    private val _configRevision = MutableStateFlow(0L)
    /** Emits whenever a non-flow provider or sync setting changes. */
    val configRevision: StateFlow<Long> = _configRevision.asStateFlow()

    private fun configChanged() {
        _configRevision.value += 1
    }

    // --- Processing enable / disable -----------------------------------------------

    private val _processingEnabled = MutableStateFlow(prefs.getBoolean(KEY_PROCESSING_ENABLED, false))
    val processingEnabled: StateFlow<Boolean> = _processingEnabled.asStateFlow()

    fun setProcessingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PROCESSING_ENABLED, enabled) }
        _processingEnabled.value = enabled
    }

    // --- Transcription provider ----------------------------------------------------

    private val _transcriptionProvider = MutableStateFlow(
        prefs.getString(KEY_TRANSCRIPTION_PROVIDER, PROVIDER_DISABLED) ?: PROVIDER_DISABLED,
    )
    val transcriptionProvider: StateFlow<String> = _transcriptionProvider.asStateFlow()

    fun setTranscriptionProvider(provider: String) {
        prefs.edit { putString(KEY_TRANSCRIPTION_PROVIDER, provider) }
        _transcriptionProvider.value = provider
    }

    var transcriptionApiKey: String?
        get() = transcriptionKey.get()
        set(value) {
            transcriptionKey.set(value)
            configChanged()
        }

    var transcriptionApiUrl: String?
        get() = prefs.getString(KEY_TRANSCRIPTION_API_URL, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_TRANSCRIPTION_API_URL) else putString(KEY_TRANSCRIPTION_API_URL, value)
            }
            configChanged()
        }

    // --- Extraction provider -------------------------------------------------------

    private val _extractionProvider = MutableStateFlow(
        prefs.getString(KEY_EXTRACTION_PROVIDER, PROVIDER_DISABLED) ?: PROVIDER_DISABLED,
    )
    val extractionProvider: StateFlow<String> = _extractionProvider.asStateFlow()

    fun setExtractionProvider(provider: String) {
        prefs.edit { putString(KEY_EXTRACTION_PROVIDER, provider) }
        _extractionProvider.value = provider
        configChanged()
    }

    var extractionApiKey: String?
        get() = extractionKey.get()
        set(value) {
            extractionKey.set(value)
            configChanged()
        }

    var extractionApiUrl: String?
        get() = prefs.getString(KEY_EXTRACTION_API_URL, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_EXTRACTION_API_URL) else putString(KEY_EXTRACTION_API_URL, value)
            }
            configChanged()
        }

    var extractionModel: String?
        get() = prefs.getString(KEY_EXTRACTION_MODEL, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_EXTRACTION_MODEL) else putString(KEY_EXTRACTION_MODEL, value)
            }
            configChanged()
        }

    // --- Audio cleanup -------------------------------------------------------------

    private val _deleteAudioAfterTranscription = MutableStateFlow(
        prefs.getBoolean(KEY_DELETE_AUDIO_AFTER_TRANSCRIPTION, true),
    )
    val deleteAudioAfterTranscription: StateFlow<Boolean> = _deleteAudioAfterTranscription.asStateFlow()

    fun setDeleteAudioAfterTranscription(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DELETE_AUDIO_AFTER_TRANSCRIPTION, enabled) }
        _deleteAudioAfterTranscription.value = enabled
    }

    // --- Sync configuration --------------------------------------------------------

    private val _syncEnabled = MutableStateFlow(prefs.getBoolean(KEY_SYNC_ENABLED, false))
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    fun setSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SYNC_ENABLED, enabled) }
        _syncEnabled.value = enabled
    }

    private val _lastSyncFailed = MutableStateFlow(prefs.getBoolean(KEY_LAST_SYNC_FAILED, false))
    val lastSyncFailed: StateFlow<Boolean> = _lastSyncFailed.asStateFlow()

    fun setLastSyncFailed(failed: Boolean) {
        prefs.edit { putBoolean(KEY_LAST_SYNC_FAILED, failed) }
        _lastSyncFailed.value = failed
    }

    var syncUrl: String?
        get() = prefs.getString(KEY_SYNC_URL, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_SYNC_URL) else putString(KEY_SYNC_URL, value)
            }
            configChanged()
        }

    var syncToken: String?
        get() = syncSecret.get()
        set(value) {
            syncSecret.set(value)
            configChanged()
        }

    private val _syncIncludeTranscripts = MutableStateFlow(prefs.getBoolean(KEY_SYNC_INCLUDE_TRANSCRIPTS, false))
    val syncIncludeTranscripts: StateFlow<Boolean> = _syncIncludeTranscripts.asStateFlow()

    fun setSyncIncludeTranscripts(include: Boolean) {
        if (_syncIncludeTranscripts.value == include) return
        prefs.edit {
            putBoolean(KEY_SYNC_INCLUDE_TRANSCRIPTS, include)
            remove(KEY_LAST_SYNC)
        }
        _syncIncludeTranscripts.value = include
    }

    /** Epoch ms of the last successful sync; null when no sync has completed yet. */
    var lastSyncEpochMs: Long?
        get() = prefs.getLong(KEY_LAST_SYNC, NONE).takeIf { it != NONE }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value)
        }

    companion object {
        const val FILE_NAME = "processing_settings"

        const val PROVIDER_DISABLED = "disabled"
        const val PROVIDER_ON_DEVICE = "on-device"
        const val PROVIDER_USER_API = "user-api"

        private const val KEY_PROCESSING_ENABLED = "processing_enabled"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_TRANSCRIPTION_API_KEY = "transcription_api_key"
        private const val KEY_TRANSCRIPTION_API_URL = "transcription_api_url"
        private const val TAG = "ProcessingSettings"
        private const val KEY_TRANSCRIPTION_API_KEY_SEALED = "transcription_api_key_sealed"
        private const val KEY_EXTRACTION_API_KEY_SEALED = "extraction_api_key_sealed"
        private const val KEY_SYNC_TOKEN_SEALED = "sync_token_sealed"
        private const val KEY_EXTRACTION_PROVIDER = "extraction_provider"
        private const val KEY_EXTRACTION_API_KEY = "extraction_api_key"
        private const val KEY_EXTRACTION_API_URL = "extraction_api_url"
        private const val KEY_EXTRACTION_MODEL = "extraction_model"
        private const val KEY_DELETE_AUDIO_AFTER_TRANSCRIPTION = "delete_audio_after_transcription"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_URL = "sync_url"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_SYNC_INCLUDE_TRANSCRIPTS = "sync_include_transcripts"
        private const val KEY_LAST_SYNC_FAILED = "last_sync_failed"
        private const val KEY_LAST_SYNC = "last_sync_epoch_ms"
        private const val NONE = Long.MIN_VALUE
    }
}