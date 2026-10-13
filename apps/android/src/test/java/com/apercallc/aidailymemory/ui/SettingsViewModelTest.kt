package com.apercallc.aidailymemory.ui

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.processing.ProviderPresets
import com.apercallc.aidailymemory.recording.server.HostedServer
import com.apercallc.aidailymemory.recording.server.ServerMode
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

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun withViewModel(block: (SettingsViewModel) -> Unit) = runBlocking {
        val viewModel = SettingsViewModel(container)
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
    fun `processing is on by default and offline until an account is connected`() = withViewModel { vm ->
        assertTrue(vm.processing.value.enabled)
        assertEquals(ServerMode.OFFLINE, vm.processing.value.mode)
        assertFalse(vm.processing.value.ownProvider)
        assertTrue(vm.sync.value.enabled)
        assertFalse(vm.sync.value.connected)
    }

    @Test
    fun `using your own provider reports what is missing until it is fully configured`() = withViewModel { vm ->
        vm.processingActions.onOwnProviderChange(true)
        assertTrue(vm.processing.value.ownProvider)
        assertTrue(vm.processing.value.configGaps.isNotEmpty())

        vm.processingActions.onTranscriptionApiUrlChange("https://asr.example/v1")
        vm.processingActions.onTranscriptionApiKeyChange("k1")
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onExtractionApiKeyChange("k2")
        vm.processingActions.onExtractionModelChange("model-x")

        assertEquals(emptyList(), vm.processing.value.configGaps)
        vm.processingActions.onOwnProviderChange(false)
        assertFalse(vm.processing.value.ownProvider)
    }

    @Test
    fun `turning processing on needs no provider and picks none`() = withViewModel { vm ->
        vm.processingActions.onEnableChange(false)
        vm.processingActions.onEnableChange(true)

        assertEquals("disabled", vm.processing.value.transcriptionProvider)
        assertEquals("disabled", vm.processing.value.extractionProvider)
    }

    @Test
    fun `changing a setting confirms that it was saved`() = runBlocking {
        val viewModel = SettingsViewModel(container)
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
        val viewModel = SettingsViewModel(container)
        val messages = CompletableDeferred<Int>()
        val job = launch(Dispatchers.Main) { messages.complete(viewModel.messages.first()) }

        viewModel.testConnection()

        assertEquals(R.string.test_not_connected, withTimeout(5_000) { messages.await() })
        job.cancel()
    }

    @Test
    fun `turning on your own provider pre-fills a provider and model for each stage`() = withViewModel { vm ->
        vm.processingActions.onOwnProviderChange(true)

        assertEquals("deepgram", vm.processing.value.transcriptionPresetId)
        assertEquals("nova-3", vm.processing.value.transcriptionModel)
        assertEquals("openai", vm.processing.value.extractionPresetId)
        assertEquals("gpt-4o-mini", vm.processing.value.extractionModel)
        // Only the keys are left.
        assertEquals(2, vm.processing.value.configGaps.size)
    }

    @Test
    fun `an address the person already set is not overwritten by the pre-fill`() = withViewModel { vm ->
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onOwnProviderChange(true)

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
        vm.processingActions.onExtractionModelChange("gpt-4o")
        vm.processingActions.onExtractionPresetChange("openai")

        assertEquals("gpt-4o", container.processingSettings.extractionModel)
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
        val vm = SettingsViewModel(container)
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
}
