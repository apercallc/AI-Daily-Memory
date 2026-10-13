package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.apercallc.aidailymemory.data.TranscriptText
import com.apercallc.aidailymemory.ui.sessions.TranscriptScreen
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.ZonedDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class TranscriptScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `lines show the time they were said, in the zone they were recorded in`() {
        val said = ZonedDateTime.parse("2026-09-25T14:03:00+02:00[Europe/Paris]").toInstant().toEpochMilli()
        compose.setContent {
            AiDailyMemoryTheme {
                TranscriptScreen(
                    lines = listOf(
                        TranscriptText("u1:0", said, "Europe/Paris", "Let's ship on Friday.", "A"),
                        TranscriptText("u1:1", said + 60_000, "Europe/Paris", "Sounds good.", null),
                    ),
                )
            }
        }

        compose.onNodeWithText("Let's ship on Friday.").assertExists()
        compose.onNodeWithText("Speaker A").assertExists()
        compose.onNodeWithContentDescription("2:03 PM, Speaker A, Let's ship on Friday.").assertExists()
        compose.onNodeWithContentDescription("2:04 PM, Sounds good.").assertExists()
    }

    @Test
    fun `loading a transcript is visible rather than a blank screen`() {
        compose.setContent { AiDailyMemoryTheme { TranscriptScreen(lines = null) } }
        compose.onNodeWithText("Loading transcript…").assertExists()
    }

    @Test
    fun `no transcript yet explains when one will appear`() {
        compose.setContent { AiDailyMemoryTheme { TranscriptScreen(lines = emptyList()) } }

        compose.onNodeWithText("No transcript yet").assertExists()
        compose.onNodeWithText(
            "Speech is turned into text on this phone while it charges. Download the free speech model in Settings if you haven't yet.",
        ).assertExists()
    }
}
