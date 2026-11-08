package com.apercallc.aidailymemory.ui.ask

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.recording.recall.RecallAnswer
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class AskCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: AskState, onAsk: (String) -> Unit = {}, onClear: () -> Unit = {}) =
        compose.setContent { AiDailyMemoryTheme { AskCard(state, onAsk, onClear) } }

    @Test
    fun `ask is disabled until there is a question, then sends it`() {
        val asked = mutableListOf<String>()
        show(AskState.Idle, onAsk = { asked += it })
        compose.onNodeWithText("Ask").assertIsNotEnabled()
        compose.onNodeWithText("Ask your memory").performTextInput("What did I decide?")
        compose.onNodeWithText("Ask").assertIsEnabled().performClick()
        assertEquals(listOf("What did I decide?"), asked)
    }

    @Test
    fun `working disables ask and says so`() {
        show(AskState.Working)
        compose.onNodeWithText("Looking…").assertExists()
        compose.onNodeWithText("Ask").assertIsNotEnabled()
    }

    @Test
    fun `an answer is shown and can be cleared`() {
        var cleared = 0
        show(
            AskState.Answered("q", RecallAnswer("Here is what your memory has: x", listOf("a"), false, null)),
            onClear = { cleared++ },
        )
        compose.onNodeWithText("Here is what your memory has: x").assertExists()
        compose.onNodeWithText("Clear answer").performClick()
        assertEquals(1, cleared)
    }
}
