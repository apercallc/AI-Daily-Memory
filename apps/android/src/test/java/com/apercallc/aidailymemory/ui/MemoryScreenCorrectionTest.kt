package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.ui.memory.MemoryDay
import com.apercallc.aidailymemory.ui.memory.MemoryDisplayItem
import com.apercallc.aidailymemory.ui.memory.MemoryLoadState
import com.apercallc.aidailymemory.ui.memory.MemoryScreen
import com.apercallc.aidailymemory.ui.memory.MemorySession
import com.apercallc.aidailymemory.ui.memory.DayLabel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class MemoryScreenCorrectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun session(items: List<MemoryDisplayItem>) = MemorySession(
        sessionId = "s1",
        title = SessionTitle.Default(com.apercallc.aidailymemory.recording.session.PartOfDay.MORNING, null),
        startTime = "9:00 AM",
        capturedMs = 60_000,
        summary = "summary",
        items = items,
        transcriptLines = emptyList(),
        audioDeleted = false,
        freedBytes = 0,
    )

    private fun item(id: String, text: String, isCorrected: Boolean = false) = MemoryDisplayItem(
        id = id,
        kind = "decision",
        text = text,
        confidence = "likely",
        quote = null,
        owner = null,
        due = null,
        sessionId = "s1",
        isCorrected = isCorrected,
    )

    @Test
    fun `dismissing an item calls back with its id`() {
        var dismissed: String? = null
        compose.setContent {
            AiDailyMemoryTheme {
                MemoryScreen(
                    state = MemoryLoadState.Content(
                        listOf(MemoryDay(DayLabel.Today, "2026-10-15", listOf(session(listOf(item("i1", "We decided to ship Friday")))))),
                    ),
                    onRetry = {},
                    onDismiss = { dismissed = it },
                )
            }
        }

        compose.onNodeWithText("Dismiss").performClick()

        assertEquals("i1", dismissed)
    }

    @Test
    fun `correcting an item opens the current text and calls back with the edit`() {
        var corrected: Pair<String, String>? = null
        compose.setContent {
            AiDailyMemoryTheme {
                MemoryScreen(
                    state = MemoryLoadState.Content(
                        listOf(MemoryDay(DayLabel.Today, "2026-10-15", listOf(session(listOf(item("i1", "We decided to ship Friday")))))),
                    ),
                    onRetry = {},
                    onCorrect = { id, text -> corrected = id to text },
                )
            }
        }

        compose.onNodeWithText("Correct").performClick()
        compose.onNodeWithTag("correct_item_text_field").performTextClearance()
        compose.onNodeWithTag("correct_item_text_field").performTextInput("We decided to ship Monday instead")
        compose.onNodeWithText("Save").performClick()

        assertEquals("i1" to "We decided to ship Monday instead", corrected)
    }

    @Test
    fun `a corrected item is labelled as corrected`() {
        compose.setContent {
            AiDailyMemoryTheme {
                MemoryScreen(
                    state = MemoryLoadState.Content(
                        listOf(MemoryDay(DayLabel.Today, "2026-10-15", listOf(session(listOf(item("i1", "The corrected version", isCorrected = true)))))),
                    ),
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Corrected").assertExists()
    }
}
