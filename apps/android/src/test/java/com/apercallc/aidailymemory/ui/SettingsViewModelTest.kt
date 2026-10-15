package com.apercallc.aidailymemory.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.processing.ProviderPresets
import com.apercallc.aidailymemory.recording.server.HostedServer
import com.apercallc.aidailymemory.ondevice.ModelState
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.ui.settings.AccountUi
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>()
    private val container = app.container
    private val viewModels = mutableListOf<SettingsViewModel>()

    private fun newViewModel() = SettingsViewModel(container).also { viewModels += it }

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = runBlocking {
        viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private fun withViewModel(block: (SettingsViewModel) -> Unit) = runBlocking {
        val viewModel = newViewModel()
        // Main is an unconfined test dispatcher, so these collectors are live before block runs.
        val warm = launch(Dispatchers.Main) {
            launch { viewModel.processing.collect {} }
            launch { viewModel.sync.collect {} }
        }
        block(viewModel)
        warm.cancel()
    }

    @Test
    fun `a valid https endpoint is stored and an http one is kept as an error draft`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionApiUrlChange("https://asr.example/v1")
        assertEquals("https://asr.example/v1", container.processingSettings.transcriptionApiUrl)
        assertFalse(vm.processing.value.transcriptionApiUrlInvalid)

        vm.processingActions.onTranscriptionApiUrlChange("http://asr.example/v1")

        // The saved value is untouched; the field shows what was typed, flagged.
        assertEquals("https://asr.example/v1", container.processingSettings.transcriptionApiUrl)
        assertEquals("http://asr.example/v1", vm.processing.value.transcriptionApiUrl)
        assertTrue(vm.processing.value.transcriptionApiUrlInvalid)
    }

    @Test
    fun `clearing an endpoint removes it and clears the error`() = withViewModel { vm ->
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onExtractionApiUrlChange("ftp://nope")
        assertTrue(vm.processing.value.extractionApiUrlInvalid)

        vm.processingActions.onExtractionApiUrlChange("")

        assertNull(container.processingSettings.extractionApiUrl)
        assertFalse(vm.processing.value.extractionApiUrlInvalid)
        assertEquals("", vm.processing.value.extractionApiUrl)
    }

    @Test
    fun `processing is on by default, on the phone, waiting only for the free speech model`() = withViewModel { vm ->
        assertTrue(vm.processing.value.enabled)
        assertFalse(vm.processing.value.transcriptionOwnKey)
        assertFalse(vm.processing.value.summaries)
        assertEquals(ModelState.NotInstalled, vm.processing.value.modelState)
        assertEquals(listOf(ConfigGap.TRANSCRIPTION_MODEL), vm.processing.value.configGaps)
        assertTrue(vm.sync.value.enabled)
        assertFalse(vm.sync.value.connected)
    }

    @Test
    fun `using your own services reports what is missing until they are fully configured`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionOwnKeyChange(true)
        vm.processingActions.onSummariesChange(true)
        assertTrue(vm.processing.value.transcriptionOwnKey)
        assertTrue(vm.processing.value.summaries)
        assertTrue(vm.processing.value.configGaps.isNotEmpty())

        vm.processingActions.onTranscriptionApiUrlChange("https://asr.example/v1")
        vm.processingActions.onTranscriptionApiKeyChange("k1")
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onExtractionApiKeyChange("k2")
        vm.processingActions.onExtractionModelChange("model-x")

        assertEquals(emptyList(), vm.processing.value.configGaps)
        vm.processingActions.onTranscriptionOwnKeyChange(false)
        vm.processingActions.onSummariesChange(false)
        assertFalse(vm.processing.value.transcriptionOwnKey)
        assertFalse(vm.processing.value.summaries)
        // Back on the phone's own model, which still has to be downloaded.
        assertEquals(listOf(ConfigGap.TRANSCRIPTION_MODEL), vm.processing.value.configGaps)
    }

    @Test
    fun `turning processing on needs no provider and picks none`() = withViewModel { vm ->
        vm.processingActions.onEnableChange(false)
        vm.processingActions.onEnableChange(true)

        assertFalse(vm.processing.value.transcriptionOwnKey)
        assertFalse(vm.processing.value.summaries)
    }

    @Test
    fun `sync status observes queued report dates and deletions while sync is off`() = runBlocking {
        container.processingSettings.setSyncEnabled(false)
        container.database.dailyReports().upsert(
            com.apercallc.aidailymemory.data.DailyReportEntity(
                id = "report-test", memoryDay = "2026-10-15", zoneId = "UTC",
                headline = "Test", summary = "", reportJson = "{}",
                generatedAtEpochMs = 5_000, extractionModel = "none:transcript-only",
                extractionVersion = 1,
            ),
        )
        container.database.syncDeletions().enqueue(
            com.apercallc.aidailymemory.data.SyncDeletionQueueEntity("deleted-session", 1_000),
        )
        val vm = newViewModel()
        val warm = launch(Dispatchers.Main) { vm.sync.collect {} }
        try {
            val pending = withTimeout(5_000) {
                vm.sync.first { it.pendingDays == listOf("2026-10-15") && it.pendingDeletions == 1 }
            }
            assertFalse(pending.enabled)
            container.processingSettings.lastSyncEpochMs = 5_000
            withTimeout(5_000) { vm.sync.first { it.pendingDays.isEmpty() && it.pendingDeletions == 1 } }
            container.database.syncDeletions().clear(listOf("deleted-session"))
            withTimeout(5_000) { vm.sync.first { it.pendingDays.isEmpty() && it.pendingDeletions == 0 } }
            Unit
        } finally {
            warm.cancel()
        }
    }

    @Test
    fun `disabling notes sync does not cancel queued deletion delivery`() = withViewModel { vm ->
        val jobs = app.getSystemService(android.app.job.JobScheduler::class.java)
        vm.syncActions.onEnableChange(false)
        assertTrue(jobs.getPendingJob(com.apercallc.aidailymemory.processing.SyncScheduler.JOB_ID) != null)
    }

    @Test
    fun `changing a setting confirms that it was saved`() = runBlocking {
        val viewModel = newViewModel()
        val messages = mutableListOf<Int>()
        val job = launch(Dispatchers.Main) { viewModel.messages.collect { messages += it } }

        viewModel.processingActions.onEnableChange(false)
        viewModel.syncActions.onEnableChange(false)
        viewModel.updateThemeMode(ThemeMode.DARK)

        assertEquals(List(3) { R.string.settings_saved }, messages)
        assertEquals(ThemeMode.DARK, container.settings.themeMode.value)
        job.cancel()
    }

    @Test
    fun `testing the connection while offline says there is nothing to test`() = runBlocking {
        val viewModel = newViewModel()
        val messages = CompletableDeferred<Int>()
        val job = launch(Dispatchers.Main) { messages.complete(viewModel.messages.first()) }

        viewModel.testConnection()

        assertEquals(R.string.test_not_connected, withTimeout(5_000) { messages.await() })
        job.cancel()
    }

    @Test
    fun `turning on your own services pre-fills a provider and model for each stage`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionOwnKeyChange(true)
        vm.processingActions.onSummariesChange(true)

        assertEquals("deepgram", vm.processing.value.transcriptionPresetId)
        assertEquals("nova-3", vm.processing.value.transcriptionModel)
        assertEquals("openai", vm.processing.value.extractionPresetId)
        assertEquals("gpt-6-luna", vm.processing.value.extractionModel)
        // Only the keys are left.
        assertEquals(2, vm.processing.value.configGaps.size)
    }

    @Test
    fun `an address the person already set is not overwritten by the pre-fill`() = withViewModel { vm ->
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onSummariesChange(true)

        assertEquals("https://llm.example/v1", container.processingSettings.extractionApiUrl)
        assertEquals(ProviderPresets.CUSTOM_ID, vm.processing.value.extractionPresetId)
    }

    @Test
    fun `choosing a provider and model fills the address and the model`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionPresetChange("deepgram")
        vm.processingActions.onTranscriptionModelChange("nova-2")
        vm.processingActions.onExtractionPresetChange("groq")

        assertTrue(container.processingSettings.transcriptionApiUrl.orEmpty().contains("model=nova-2"))
        assertEquals("nova-2", vm.processing.value.transcriptionModel)
        assertEquals("groq", vm.processing.value.extractionPresetId)
        assertEquals("llama-3.3-70b-versatile", container.processingSettings.extractionModel)
    }

    @Test
    fun `switching to a preset keeps a model that belongs to it`() = withViewModel { vm ->
        vm.processingActions.onExtractionModelChange("gpt-6.1-sol")
        vm.processingActions.onExtractionPresetChange("openai")

        assertEquals("gpt-6.1-sol", container.processingSettings.extractionModel)
    }

    @Test
    fun `choosing custom clears a preset address and leaves a typed one alone`() = withViewModel { vm ->
        vm.processingActions.onExtractionPresetChange("openai")
        vm.processingActions.onExtractionPresetChange(ProviderPresets.CUSTOM_ID)
        assertNull(container.processingSettings.extractionApiUrl)
        assertNull(container.processingSettings.extractionModel)

        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onExtractionPresetChange(ProviderPresets.CUSTOM_ID)
        assertEquals("https://llm.example/v1", container.processingSettings.extractionApiUrl)

        vm.processingActions.onTranscriptionPresetChange("deepgram")
        vm.processingActions.onTranscriptionPresetChange(ProviderPresets.CUSTOM_ID)
        assertNull(container.processingSettings.transcriptionApiUrl)
    }

    @Test
    fun `a model change without a preset leaves the address alone`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionApiUrlChange("https://asr.example/v1")
        vm.processingActions.onTranscriptionModelChange("nova-2")

        assertEquals("https://asr.example/v1", container.processingSettings.transcriptionApiUrl)
    }

    @Test
    fun `the server defaults to hosted, takes an https address and resets`() = runBlocking {
        val vm = newViewModel()
        val warm = launch(Dispatchers.Main) { vm.account.collect {} }
        // The account state also waits on the database, so wait for it rather than read at once.
        suspend fun settled(matches: (AccountUi) -> Boolean) = withTimeout(5_000) { vm.account.first(matches) }
        settled { it.serverUrl == HostedServer.BASE_URL && it.host == null }

        vm.changeServer("https://memory.example/")
        assertNull(settled { it.serverUrl == "https://memory.example" }.serverDraft)

        vm.changeServer("http://plain.example")
        val draft = settled { it.serverDraft != null }
        assertEquals("https://memory.example", draft.serverUrl)
        assertEquals("http://plain.example", draft.serverDraft)

        vm.changeServer("")
        assertNull(settled { it.serverUrl == HostedServer.BASE_URL }.serverDraft)
        warm.cancel()
    }

    @Test
    fun `an api key is forgotten when its address moves to another host`() = withViewModel { vm ->
        vm.processingActions.onExtractionPresetChange("openai")
        vm.processingActions.onExtractionApiKeyChange("sk-openai")

        vm.processingActions.onExtractionModelChange("gpt-4o")
        assertEquals("sk-openai", container.processingSettings.extractionApiKey)

        vm.processingActions.onExtractionPresetChange("groq")
        assertNull(container.processingSettings.extractionApiKey)
    }

    @Test
    fun `an api key survives edits that keep the same host`() = withViewModel { vm ->
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1/a")
        vm.processingActions.onExtractionApiKeyChange("k")
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1/b")

        assertEquals("k", container.processingSettings.extractionApiKey)

        vm.processingActions.onExtractionApiUrlChange("https://other.example/v1")
        assertNull(container.processingSettings.extractionApiKey)
    }

    @Test
    fun `leaving a preset for custom forgets its key`() = withViewModel { vm ->
        vm.processingActions.onTranscriptionPresetChange("deepgram")
        vm.processingActions.onTranscriptionApiKeyChange("dg-key")
        vm.processingActions.onTranscriptionPresetChange(ProviderPresets.CUSTOM_ID)

        assertNull(container.processingSettings.transcriptionApiKey)
    }
}
