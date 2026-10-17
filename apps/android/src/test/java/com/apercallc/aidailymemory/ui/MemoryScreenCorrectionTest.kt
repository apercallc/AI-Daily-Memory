package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.ui.memory.MemoryDay
import com.apercallc.aidailymemory.ui.memory.MemoryDisplayItem
import com.apercallc.aidailymemory.ui.memory.MemoryLoadState
import com.apercallc.aidailymemory.ui.memory.MemoryScreen
import com.apercallc.aidailymemory.ui.memory.MemorySession
import com.apercallc.aidailymemory.ui.memory.DayLabel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import kotlinx.coroutines.flow.MutableSharedFlow
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
    fun `dismissing hides the item immediately but undo brings it back without ever dismissing`() {
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
        // Hidden at once, with a way back offered instead of being gone for good.
        compose.onNodeWithText("We decided to ship Friday").assertDoesNotExist()
        compose.onNodeWithText("Item dismissed").assertExists()

        compose.onNodeWithText("Undo").performClick()

        compose.onNodeWithText("We decided to ship Friday").assertExists()
        assertEquals(null, dismissed)
    }

    @Test
    fun `dismissing without tapping undo eventually calls back with the item's id`() {
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
        compose.onNodeWithText("Item dismissed").assertExists()
        // No undo: the snackbar closing on its own is what actually commits the dismissal.
        compose.waitUntil(timeoutMillis = 8_000) {
            dismissed != null
        }

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

    @Test
    fun `a write-failure message from the view model reaches the on-screen snackbar`() {
        val errorMessages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        compose.setContent {
            AiDailyMemoryTheme {
                MemoryScreen(
                    state = MemoryLoadState.Content(
                        listOf(MemoryDay(DayLabel.Today, "2026-10-15", listOf(session(listOf(item("i1", "We decided to ship Friday")))))),
                    ),
                    onRetry = {},
                    errorMessages = errorMessages,
                )
            }
        }

        compose.onNodeWithText("Couldn't dismiss this item. Try again.").assertDoesNotExist()
        errorMessages.tryEmit(R.string.memory_dismiss_failed)
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText("Couldn't dismiss this item. Try again.").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
