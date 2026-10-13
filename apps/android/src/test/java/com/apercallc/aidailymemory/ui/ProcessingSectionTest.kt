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
        ownProvider = true,
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
        show(ProcessingUi(ownProvider = true))

        compose.onAllNodesWithText("Provider: Custom").assertCountEquals(2)
        compose.onAllNodesWithText("API endpoint URL").assertCountEquals(2)
    }

    @Test
    fun `a stored model that is not in the list is shown in a text field`() {
        show(own.copy(extractionModel = "my-finetune"))

        compose.onNodeWithText("Model name: Other…").assertExists()
        compose.onNodeWithText("my-finetune").assertExists()
    }
}
