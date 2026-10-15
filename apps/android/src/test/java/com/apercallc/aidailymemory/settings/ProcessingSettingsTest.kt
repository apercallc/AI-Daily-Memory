package com.apercallc.aidailymemory.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ProcessingSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("processing-settings-test", Context.MODE_PRIVATE)

    private val aead = testAead()

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `changing transcript sync consent resends all reports once`() {
        val settings = ProcessingSettings(preferences) { aead }
        settings.lastSyncEpochMs = 10_000L

        settings.setSyncIncludeTranscripts(true)
        assertEquals(true, settings.syncIncludeTranscripts.value)
        assertEquals(null, settings.lastSyncEpochMs)

        settings.lastSyncEpochMs = 20_000L
        settings.setSyncIncludeTranscripts(true)
        assertEquals(20_000L, settings.lastSyncEpochMs)

        settings.setSyncIncludeTranscripts(false)
        assertEquals(false, settings.syncIncludeTranscripts.value)
        assertEquals(null, settings.lastSyncEpochMs)
    }

    @Test
    fun `secrets are sealed at rest and round trip`() {
        val settings = ProcessingSettings(preferences) { aead }

        settings.transcriptionApiKey = "tr-secret"
        settings.extractionApiKey = "ex-secret"

        val stored = preferences.all.values.filterIsInstance<String>()
        assertTrue(stored.none { it.contains("secret") })
        val reopened = ProcessingSettings(preferences) { aead }
        assertEquals("tr-secret", reopened.transcriptionApiKey)
        assertEquals("ex-secret", reopened.extractionApiKey)
    }

    @Test
    fun `plaintext values from older versions migrate on first read`() {
        preferences.edit()
            .putString("transcription_api_key", "old-key")
            .commit()
        val settings = ProcessingSettings(preferences) { aead }

        assertEquals("old-key", settings.transcriptionApiKey)

        assertFalse(preferences.contains("transcription_api_key"))
        assertEquals("old-key", ProcessingSettings(preferences) { aead }.transcriptionApiKey)
    }

    @Test
    fun `an undecryptable secret reads as unset and is not replaced by plaintext`() {
        ProcessingSettings(preferences) { aead }.extractionApiKey = "ex-secret"

        val otherAead = newTestAead()
        val other = ProcessingSettings(preferences) { otherAead }

        assertNull(other.extractionApiKey)
    }

    @Test
    fun `clearing a secret removes sealed and plaintext entries`() {
        preferences.edit().putString("extraction_api_key", "old").commit()
        val settings = ProcessingSettings(preferences) { aead }
        settings.extractionApiKey = "new"

        settings.extractionApiKey = null

        assertNull(ProcessingSettings(preferences) { aead }.extractionApiKey)
        assertFalse(preferences.contains("extraction_api_key"))
        assertFalse(preferences.contains("extraction_api_key_sealed"))
    }

    @Test
    fun `sync failure state is observable and persisted`() {
        val settings = ProcessingSettings(preferences) { aead }

        settings.setLastSyncFailed(true)
        assertEquals(true, settings.lastSyncFailed.value)

        assertEquals(true, ProcessingSettings(preferences) { aead }.lastSyncFailed.value)
    }

    @Test
    fun `last successful sync time persists separately from the report cursor`() {
        val settings = ProcessingSettings(preferences) { aead }
        settings.lastSyncEpochMs = 10_000L
        settings.setLastSyncSucceededAtEpochMs(20_000L)

        val reopened = ProcessingSettings(preferences) { aead }

        assertEquals(10_000L, reopened.lastSyncEpochMs)
        assertEquals(20_000L, reopened.lastSyncSucceededAtEpochMs.value)
    }

    @Test
    fun `changing extraction provider emits a configuration revision`() {
        val settings = ProcessingSettings(preferences) { aead }

        settings.setExtractionProvider(ProcessingSettings.PROVIDER_USER_API)

        assertEquals(1L, settings.configRevision.value)
        assertEquals(ProcessingSettings.PROVIDER_USER_API, settings.extractionProvider.value)
    }

    @Test
    fun `text configuration edits emit revisions and persist`() = runTest {
        val settings = ProcessingSettings(preferences) { aead }
        val revisions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            settings.configRevision.take(3).toList(revisions)
        }

        settings.transcriptionApiUrl = "https://speech.example/v1"
        settings.extractionModel = "test"
        advanceUntilIdle()

        assertEquals(listOf(0L, 1L, 2L), revisions)
        assertEquals("https://speech.example/v1", settings.transcriptionApiUrl)
        assertEquals("test", settings.extractionModel)
    }

    @Test
    fun `processing and sync are on by default and can be turned off`() {
        val settings = ProcessingSettings(preferences) { aead }

        assertTrue(settings.processingEnabled.value)
        assertTrue(settings.syncEnabled.value)
        assertTrue(settings.deleteAudioAfterTranscription.value)

        settings.setProcessingEnabled(false)
        settings.setSyncEnabled(false)
        val reopened = ProcessingSettings(preferences) { aead }
        assertFalse(reopened.processingEnabled.value)
        assertFalse(reopened.syncEnabled.value)
    }

    @Test
    fun `an earlier install's own sync address and token are removed, not left behind`() {
        preferences.edit()
            .putString("sync_url", "https://sync.example/v1/sync")
            .putString("sync_token", "old-token")
            .putString("sync_token_sealed", "AAAA")
            .commit()

        ProcessingSettings(preferences) { aead }

        assertFalse(preferences.contains("sync_url"))
        assertFalse(preferences.contains("sync_token"))
        assertFalse(preferences.contains("sync_token_sealed"))
    }

    @Test
    fun `transcription defaults to the phone and an earlier disabled value reads as the phone`() {
        assertEquals(ProcessingSettings.PROVIDER_ON_DEVICE, ProcessingSettings(preferences) { aead }.transcriptionProvider.value)

        preferences.edit().putString("transcription_provider", "disabled").commit()
        assertEquals(ProcessingSettings.PROVIDER_ON_DEVICE, ProcessingSettings(preferences) { aead }.transcriptionProvider.value)

        preferences.edit().putString("transcription_provider", "user-api").commit()
        assertEquals(ProcessingSettings.PROVIDER_USER_API, ProcessingSettings(preferences) { aead }.transcriptionProvider.value)
    }

    @Test
    fun `summaries are off by default`() {
        assertEquals(ProcessingSettings.PROVIDER_DISABLED, ProcessingSettings(preferences) { aead }.extractionProvider.value)
    }

    @Test
    fun `phone transcription waits only for the speech model`() {
        var ready = false
        val settings = ProcessingSettings(preferences, onDeviceModelReady = { ready }) { aead }

        assertEquals(
            listOf(com.apercallc.aidailymemory.recording.processing.ConfigGap.TRANSCRIPTION_MODEL),
            settings.configGaps(),
        )
        ready = true
        assertEquals(emptyList(), settings.configGaps())
    }

    @Test
    fun `an unknown transcription provider is stored as the phone, never as a half-set service`() {
        val settings = ProcessingSettings(preferences) { aead }

        settings.setTranscriptionProvider("something-else")

        assertEquals(ProcessingSettings.PROVIDER_ON_DEVICE, settings.transcriptionProvider.value)
    }
}
