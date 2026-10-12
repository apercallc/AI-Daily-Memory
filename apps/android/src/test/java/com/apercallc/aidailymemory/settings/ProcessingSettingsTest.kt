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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ProcessingSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("processing-settings-test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `changing transcript sync consent resends all reports once`() {
        val settings = ProcessingSettings(preferences)
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
    fun `sync failure state is observable and persisted`() {
        val settings = ProcessingSettings(preferences)

        settings.setLastSyncFailed(true)
        assertEquals(true, settings.lastSyncFailed.value)

        assertEquals(true, ProcessingSettings(preferences).lastSyncFailed.value)
    }

    @Test
    fun `changing extraction provider emits a configuration revision`() {
        val settings = ProcessingSettings(preferences)

        settings.setExtractionProvider(ProcessingSettings.PROVIDER_USER_API)

        assertEquals(1L, settings.configRevision.value)
        assertEquals(ProcessingSettings.PROVIDER_USER_API, settings.extractionProvider.value)
    }

    @Test
    fun `text configuration edits emit revisions and persist`() = runTest {
        val settings = ProcessingSettings(preferences)
        val revisions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            settings.configRevision.take(3).toList(revisions)
        }

        settings.transcriptionApiUrl = "https://speech.example/v1"
        settings.syncToken = "test"
        advanceUntilIdle()

        assertEquals(listOf(0L, 1L, 2L), revisions)
        assertEquals("https://speech.example/v1", settings.transcriptionApiUrl)
        assertEquals("test", settings.syncToken)
    }
}
