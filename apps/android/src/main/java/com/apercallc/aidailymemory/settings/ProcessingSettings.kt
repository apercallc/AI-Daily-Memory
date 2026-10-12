package com.apercallc.aidailymemory.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings for on-device processing and second-brain sync (ADR-0020). Backed by
 * SharedPreferences, which are excluded from backup and device transfer like all app data
 * (data_extraction_rules.xml).
 *
 * Processing is disabled by default: the user must configure a provider (on-device or a
 * user-supplied API key) before any transcription or extraction runs. Sync is also off by
 * default until the user enters a second-brain endpoint and auth token.
 *
 * Writes publish immediately; reads never throw. API keys and tokens are stored in
 * SharedPreferences (app-private, credential-encrypted storage) and never logged.
 */
class ProcessingSettings(private val prefs: SharedPreferences) {

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
        get() = prefs.getString(KEY_TRANSCRIPTION_API_KEY, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_TRANSCRIPTION_API_KEY) else putString(KEY_TRANSCRIPTION_API_KEY, value)
        }

    var transcriptionApiUrl: String?
        get() = prefs.getString(KEY_TRANSCRIPTION_API_URL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_TRANSCRIPTION_API_URL) else putString(KEY_TRANSCRIPTION_API_URL, value)
        }

    // --- Extraction provider -------------------------------------------------------

    private val _extractionProvider = MutableStateFlow(
        prefs.getString(KEY_EXTRACTION_PROVIDER, PROVIDER_DISABLED) ?: PROVIDER_DISABLED,
    )
    val extractionProvider: StateFlow<String> = _extractionProvider.asStateFlow()

    fun setExtractionProvider(provider: String) {
        prefs.edit { putString(KEY_EXTRACTION_PROVIDER, provider) }
        _extractionProvider.value = provider
    }

    var extractionApiKey: String?
        get() = prefs.getString(KEY_EXTRACTION_API_KEY, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_EXTRACTION_API_KEY) else putString(KEY_EXTRACTION_API_KEY, value)
        }

    var extractionApiUrl: String?
        get() = prefs.getString(KEY_EXTRACTION_API_URL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_EXTRACTION_API_URL) else putString(KEY_EXTRACTION_API_URL, value)
        }

    var extractionModel: String?
        get() = prefs.getString(KEY_EXTRACTION_MODEL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_EXTRACTION_MODEL) else putString(KEY_EXTRACTION_MODEL, value)
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

    var syncUrl: String?
        get() = prefs.getString(KEY_SYNC_URL, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SYNC_URL) else putString(KEY_SYNC_URL, value)
        }

    var syncToken: String?
        get() = prefs.getString(KEY_SYNC_TOKEN, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SYNC_TOKEN) else putString(KEY_SYNC_TOKEN, value)
        }

    private val _syncIncludeTranscripts = MutableStateFlow(prefs.getBoolean(KEY_SYNC_INCLUDE_TRANSCRIPTS, false))
    val syncIncludeTranscripts: StateFlow<Boolean> = _syncIncludeTranscripts.asStateFlow()

    fun setSyncIncludeTranscripts(include: Boolean) {
        prefs.edit { putBoolean(KEY_SYNC_INCLUDE_TRANSCRIPTS, include) }
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
        private const val KEY_EXTRACTION_PROVIDER = "extraction_provider"
        private const val KEY_EXTRACTION_API_KEY = "extraction_api_key"
        private const val KEY_EXTRACTION_API_URL = "extraction_api_url"
        private const val KEY_EXTRACTION_MODEL = "extraction_model"
        private const val KEY_DELETE_AUDIO_AFTER_TRANSCRIPTION = "delete_audio_after_transcription"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_URL = "sync_url"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_SYNC_INCLUDE_TRANSCRIPTS = "sync_include_transcripts"
        private const val KEY_LAST_SYNC = "last_sync_epoch_ms"
        private const val NONE = Long.MIN_VALUE
    }
}