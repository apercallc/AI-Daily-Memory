package com.apercallc.aidailymemory.ui

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
        vm.syncActions.onUrlChange("https://sync.example/v1")
        vm.syncActions.onUrlChange("ftp://nope")
        assertTrue(vm.sync.value.urlInvalid)

        vm.syncActions.onUrlChange("")

        assertNull(container.processingSettings.syncUrl)
        assertFalse(vm.sync.value.urlInvalid)
        assertEquals("", vm.sync.value.url)
    }

    @Test
    fun `processing reports what is missing until it is fully configured`() = withViewModel { vm ->
        vm.processingActions.onEnableChange(true)
        assertTrue(vm.processing.value.configGaps.isNotEmpty())

        vm.processingActions.onTranscriptionProviderChange("user-api")
        vm.processingActions.onTranscriptionApiUrlChange("https://asr.example/v1")
        vm.processingActions.onTranscriptionApiKeyChange("k1")
        vm.processingActions.onExtractionProviderChange("user-api")
        vm.processingActions.onExtractionApiUrlChange("https://llm.example/v1")
        vm.processingActions.onExtractionApiKeyChange("k2")
        vm.processingActions.onExtractionModelChange("model-x")

        assertEquals(emptyList(), vm.processing.value.configGaps)
        vm.processingActions.onEnableChange(false)
    }

    @Test
    fun `turning processing on selects the api-key provider so setup is just url and key`() =
        withViewModel { vm ->
            vm.processingActions.onEnableChange(true)

            assertEquals("user-api", vm.processing.value.transcriptionProvider)
            assertEquals("user-api", vm.processing.value.extractionProvider)
            // Only the address, key and model are still missing; the provider is never a gap.
            assertTrue(vm.processing.value.configGaps.none { it.name.endsWith("PROVIDER") })
            vm.processingActions.onEnableChange(false)
        }
}
