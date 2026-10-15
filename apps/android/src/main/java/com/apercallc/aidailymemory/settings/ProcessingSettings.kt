package com.apercallc.aidailymemory.settings

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import com.apercallc.aidailymemory.recording.processing.ProcessingConfig
import com.apercallc.aidailymemory.recording.processing.StageConfig
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
 * Transcription runs on the phone with a free downloadable model unless the person supplies their
 * own provider key; a selected provider receives audio directly from the phone. Summaries are off
 * unless the person supplies a key, and receive transcript text rather than audio.
 * Sync is on by default and does nothing without a connected account; it has no address or token
 * of its own.
 *
 * Writes publish immediately; reads never throw. The transcription key, extraction key and
 * sync bearer token are sealed with a Keystore-backed AEAD (same scheme as the account token in
 * `LinkStore`) before they reach SharedPreferences, and are never logged. A value that can no
 * longer be decrypted (Keystore reset) reads as unset; it is never guessed at. Plaintext values
 * written by earlier versions are sealed on first read and the plaintext entry removed.
 * Nothing touches the Keystore until a secret is read or written (call off the main thread
 * where possible).
 */
class ProcessingSettings(
    private val prefs: SharedPreferences,
    /** True once the free on-device speech model is downloaded and verified. */
    private val onDeviceModelReady: () -> Boolean = { false },
    private val aead: () -> Aead,
) {

    /** One sealed secret: cached after the first read so the Keystore is hit once. */
    private inner class SealedSecret(
        private val legacyKey: String,
        private val sealedKey: String,
        private val purpose: String,
    ) {
        private val ad = "aidm:processing-secret:$purpose:v1".toByteArray(Charsets.UTF_8)
        private var cached: String? = null
        private var read = false

        @Volatile var lastSaveFailed = false

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
            // The key still works until the process ends, but it was not stored: the caller can
            // tell the person instead of letting it vanish on the next start.
            lastSaveFailed = sealed == null
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

    /** The last API key written could not be stored securely; it is held in memory only. */
    val secretSaveFailed: Boolean get() = transcriptionKey.lastSaveFailed || extractionKey.lastSaveFailed

    init {
        // Sync once had its own address and token. It now uses the connected account, so the old
        // values (including a sealed secret) are removed rather than left behind unused.
        if (prefs.contains(KEY_SYNC_URL) || prefs.contains(KEY_SYNC_TOKEN) || prefs.contains(KEY_SYNC_TOKEN_SEALED)) {
            prefs.edit {
                remove(KEY_SYNC_URL)
                remove(KEY_SYNC_TOKEN)
                remove(KEY_SYNC_TOKEN_SEALED)
            }
        }
    }

    private val _configRevision = MutableStateFlow(0L)
    /** Emits whenever a non-flow provider or sync setting changes. */
    val configRevision: StateFlow<Long> = _configRevision.asStateFlow()

    private fun configChanged() {
        _configRevision.value += 1
    }

    /** For changes that happen outside these settings, like the speech model finishing its download. */
    fun notifyConfigChanged() = configChanged()

    // --- Processing enable / disable -----------------------------------------------

    private val _processingEnabled = MutableStateFlow(prefs.getBoolean(KEY_PROCESSING_ENABLED, true))
    val processingEnabled: StateFlow<Boolean> = _processingEnabled.asStateFlow()

    fun setProcessingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PROCESSING_ENABLED, enabled) }
        _processingEnabled.value = enabled
    }

    // --- Transcription provider ----------------------------------------------------

    // Anything but the person's own key means the phone's free model, including the "disabled"
    // that earlier versions stored for "not set up".
    private val _transcriptionProvider = MutableStateFlow(
        normalizeTranscriptionProvider(prefs.getString(KEY_TRANSCRIPTION_PROVIDER, null)),
    )
    val transcriptionProvider: StateFlow<String> = _transcriptionProvider.asStateFlow()

    fun setTranscriptionProvider(provider: String) {
        val normalized = normalizeTranscriptionProvider(provider)
        prefs.edit { putString(KEY_TRANSCRIPTION_PROVIDER, normalized) }
        _transcriptionProvider.value = normalized
        configChanged()
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

    /** What is missing before processing can run; empty when fully configured. */
    fun configGaps(): List<ConfigGap> = ProcessingConfig.gaps(
        transcription = StageConfig(
            providerIsUserApi = transcriptionProvider.value == PROVIDER_USER_API,
            urlValid = EndpointUrl.parse(transcriptionApiUrl.orEmpty()) is EndpointUrl.Valid,
            keyPresent = !transcriptionApiKey.isNullOrBlank(),
        ),
        extraction = StageConfig(
            providerIsUserApi = extractionProvider.value == PROVIDER_USER_API,
            urlValid = EndpointUrl.parse(extractionApiUrl.orEmpty()) is EndpointUrl.Valid,
            keyPresent = !extractionApiKey.isNullOrBlank(),
        ),
        extractionModelPresent = !extractionModel.isNullOrBlank(),
        onDeviceModelReady = onDeviceModelReady(),
    )

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

    private val _syncEnabled = MutableStateFlow(prefs.getBoolean(KEY_SYNC_ENABLED, true))
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

    private val _lastSyncSucceededAtEpochMs = MutableStateFlow(
        prefs.getLong(KEY_LAST_SYNC_SUCCEEDED_AT, NONE).takeIf { it != NONE },
    )
    val lastSyncSucceededAtEpochMs: StateFlow<Long?> = _lastSyncSucceededAtEpochMs.asStateFlow()

    fun setLastSyncSucceededAtEpochMs(epochMs: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC_SUCCEEDED_AT, epochMs) }
        _lastSyncSucceededAtEpochMs.value = epochMs
    }

    /** Report cursor; independent of the time the last request succeeded. */
    var lastSyncEpochMs: Long?
        get() = prefs.getLong(KEY_LAST_SYNC, NONE).takeIf { it != NONE }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value)
        }

    companion object {
        const val FILE_NAME = "processing_settings"

        /** Transcription on the phone with the free downloadable model (the default). */
        const val PROVIDER_ON_DEVICE = "on-device"

        /** Summaries off: the person keeps a transcript only (the default). */
        const val PROVIDER_DISABLED = "disabled"
        const val PROVIDER_USER_API = "user-api"

        private fun normalizeTranscriptionProvider(stored: String?): String =
            if (stored == PROVIDER_USER_API) PROVIDER_USER_API else PROVIDER_ON_DEVICE

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
        private const val KEY_LAST_SYNC_SUCCEEDED_AT = "last_sync_succeeded_at_epoch_ms"
        private const val NONE = Long.MIN_VALUE
    }
}
