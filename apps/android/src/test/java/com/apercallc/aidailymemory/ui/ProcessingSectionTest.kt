package com.apercallc.aidailymemory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.ui.settings.ProcessingActions
import com.apercallc.aidailymemory.ui.settings.ProcessingSettingsSection
import com.apercallc.aidailymemory.ui.settings.ProcessingUi
import com.apercallc.aidailymemory.ondevice.ModelFailure
import com.apercallc.aidailymemory.ondevice.ModelState
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import androidx.compose.ui.test.assertCountEquals
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessingSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(ui: ProcessingUi, actions: ProcessingActions = ProcessingActions()) {
        compose.setContent {
            AiDailyMemoryTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { ProcessingSettingsSection(ui, actions) }
            }
        }
    }

    private val own = ProcessingUi(
        transcriptionOwnKey = true,
        summaries = true,
        transcriptionPresetId = "deepgram",
        transcriptionModel = "nova-3",
        extractionPresetId = "openai",
        extractionModel = "gpt-4o-mini",
    )

    @Test
    fun `provider and model show as lists with the pre-selected values`() {
        show(own)

        compose.onNodeWithText("Provider: Deepgram").assertExists()
        compose.onNodeWithText("Model name: nova-3").assertExists()
        compose.onNodeWithText("Provider: OpenAI").assertExists()
        compose.onNodeWithText("Model name: gpt-4o-mini").assertExists()
        compose.onNodeWithText("API endpoint URL").assertDoesNotExist()
    }

    @Test
    fun `picking from a list reports the choice`() {
        val picked = mutableListOf<String>()
        show(own, ProcessingActions(onExtractionPresetChange = { picked += it }))

        compose.onNodeWithText("Provider: OpenAI").performScrollTo().performClick()
        compose.onNodeWithText("Groq").performClick()

        assertEquals(listOf("groq"), picked)
    }

    @Test
    fun `a listed provider has a link to where its keys are issued`() {
        val opened = mutableListOf<String>()
        show(own, ProcessingActions(onOpenLink = { opened += it }))
        compose.onNodeWithText("Get a Deepgram API key").performScrollTo().performClick()
        assertEquals(listOf("https://console.deepgram.com/signup"), opened)
    }

    @Test
    fun `custom provider shows address and model fields`() {
        show(ProcessingUi(transcriptionOwnKey = true, summaries = true))

        compose.onAllNodesWithText("Provider: Custom").assertCountEquals(2)
        compose.onAllNodesWithText("API endpoint URL").assertCountEquals(2)
    }

    @Test
    fun `a stored model that is not in the list is shown in a text field`() {
        show(own.copy(extractionModel = "my-finetune"))

        compose.onNodeWithText("Model name: Other…").assertExists()
        compose.onNodeWithText("my-finetune").assertExists()
    }

    @Test
    fun `before the model is downloaded the section offers the download with its size`() {
        var downloads = 0
        show(ProcessingUi(modelSizeMb = 40), ProcessingActions(onDownloadModel = { downloads++ }))

        compose.onNodeWithText("Download speech model (40 MB)").performScrollTo().performClick()

        assertEquals(1, downloads)
    }

    @Test
    fun `a downloading model shows its progress and no second download button`() {
        show(ProcessingUi(modelState = ModelState.Downloading(37)))

        compose.onNodeWithText("Downloading… 37%").assertExists()
        compose.onAllNodesWithText("Download speech model", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an installed model can be removed`() {
        var removed = 0
        show(ProcessingUi(modelState = ModelState.Installed), ProcessingActions(onRemoveModel = { removed++ }))

        compose.onNodeWithText("Speech model ready", substring = true).assertExists()
        compose.onNodeWithText("Remove speech model").performScrollTo().performClick()

        assertEquals(1, removed)
    }

    @Test
    fun `a failed download says why and offers to try again`() {
        show(ProcessingUi(modelState = ModelState.Failed(ModelFailure.CORRUPT)))

        compose.onNodeWithText("The download didn't match the expected file, so it was thrown away. Try again.")
            .assertExists()
        compose.onNodeWithText("Download speech model (40 MB)").assertExists()
    }

    @Test
    fun `summaries are off by default and their key fields appear only when turned on`() {
        show(ProcessingUi())

        compose.onAllNodesWithText("API key").assertCountEquals(0)
        compose.onNodeWithText("Write summaries and find decisions").assertExists()
    }

    @Test
    fun `using your own transcription service hides the model download`() {
        show(ProcessingUi(transcriptionOwnKey = true))

        compose.onAllNodesWithText("Download speech model", substring = true).assertCountEquals(0)
    }
}
