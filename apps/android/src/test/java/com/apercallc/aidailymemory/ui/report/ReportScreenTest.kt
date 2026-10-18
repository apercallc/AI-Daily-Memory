package com.apercallc.aidailymemory.ui.report

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class ReportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a load failure shows an error and a retry action, not the loading or empty state`() {
        compose.setContent {
            AiDailyMemoryTheme {
                ReportScreen(state = ReportLoadState.Failed, onRetry = {})
            }
        }

        compose.onNodeWithText("Couldn't load this report. Try again.").assertExists()
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Loading report…").assertDoesNotExist()
        compose.onNodeWithText("No report yet").assertDoesNotExist()
    }

    @Test
    fun `tapping retry on the failed state invokes the callback`() {
        var retried = 0
        compose.setContent {
            AiDailyMemoryTheme {
                ReportScreen(state = ReportLoadState.Failed, onRetry = { retried++ })
            }
        }

        compose.onNodeWithText("Retry").performClick()

        assertEquals(1, retried)
    }
}
