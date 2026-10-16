package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.apercallc.aidailymemory.data.ProcessingStateEntity
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

    @Test
    fun `no speech detected is a terminal outcome once the session is never queued for it`() {
        compose.setContent {
            AiDailyMemoryTheme { TranscriptScreen(lines = emptyList(), noSpeechDetected = true) }
        }
        compose.onNodeWithText("No words recognized").assertExists()
        compose.onNodeWithText("No transcript yet").assertDoesNotExist()
    }

    @Test
    fun `no speech detected wins over a stale pending processing card`() {
        compose.setContent {
            AiDailyMemoryTheme {
                TranscriptScreen(
                    lines = emptyList(),
                    processingState = ProcessingStateEntity("session", "PENDING", false, null, 0L),
                    noSpeechDetected = true,
                )
            }
        }
        compose.onNodeWithText("No words recognized").assertExists()
        compose.onNodeWithText("Waiting for the phone to charge before transcription starts.").assertDoesNotExist()
    }

    @Test
    fun `completed processing with no words shows a final outcome rather than pending`() {
        compose.setContent {
            AiDailyMemoryTheme {
                TranscriptScreen(
                    lines = emptyList(),
                    processingState = ProcessingStateEntity("session", "COMPLETED", false, null, 0),
                )
            }
        }
        compose.onNodeWithText("No words recognized").assertExists()
        compose.onNodeWithText("No transcript yet").assertDoesNotExist()
    }

    @Test
    fun `retryable processing failure does not claim to be waiting for charging`() {
        compose.setContent {
            AiDailyMemoryTheme {
                TranscriptScreen(
                    lines = emptyList(),
                    processingState = ProcessingStateEntity("session", "PENDING", false, "io_error", 0),
                )
            }
        }
        compose.onNodeWithText("Processing could not finish. A retry is pending.").assertExists()
        compose.onNodeWithText("Waiting for the phone to charge before transcription starts.").assertDoesNotExist()
    }

    @Test
    fun `transcription status shows measured percent and remaining time`() {
        val state = ProcessingStateEntity(
            sessionId = "session",
            state = "TRANSCRIBING",
            audioDeleted = false,
            lastError = null,
            updatedAtEpochMs = 0L,
            completedSegments = 1,
            totalSegments = 4,
            processingTimeMs = 1_000L,
        )
        compose.setContent {
            AiDailyMemoryTheme {
                TranscriptScreen(lines = emptyList(), processingState = state)
            }
        }

        compose.onNodeWithText(
            "Transcribing on this phone. 25% complete, about 1 min left.",
        ).assertExists()
    }
}
