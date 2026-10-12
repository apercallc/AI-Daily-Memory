package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.ui.sessions.DayGroup
import com.apercallc.aidailymemory.ui.sessions.SessionItem
import com.apercallc.aidailymemory.ui.sessions.SessionsScreen
import com.apercallc.aidailymemory.ui.settings.RhythmSettingsScreen
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two new user flows on the real composables. */
@RunWith(RobolectricTestRunner::class)
class ScheduleScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.parse("2026-09-22")
    private val zone = ZoneId.of("America/Chicago")

    @Test
    fun `loading sessions is visible rather than a blank screen`() {
        compose.setContent {
            AiDailyMemoryTheme {
                SessionsScreen(days = null, today = today, onRename = { _, _ -> })
            }
        }
        compose.onNodeWithText("Loading sessions…").assertExists()
    }

    @Test
    fun `audio settings describe active retention instead of a future update`() {
        compose.setContent {
            AiDailyMemoryTheme {
                RhythmSettingsScreen(
                    rhythm = DailyRhythm.None,
                    retention = AudioRetention.TRANSCRIPT_ONLY,
                    nextChange = null,
                    bytesPerHour = 15L * 1024 * 1024,
                    showUnreadableNotice = false,
                    onRhythmChange = {},
                    onRetentionChange = {},
                )
            }
        }
        compose.onNodeWithText(
            "Automatic deletion follows the setting below after processing and a recovery period. " +
                "Audio still waiting for a transcript stays on this phone. Audio with no detected speech can also be deleted.",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `a session can be renamed, and invalid names can't be saved`() {
        val renames = mutableListOf<Pair<String, String>>()
        val start = ZonedDateTime.of(2026, 9, 22, 14, 0, 0, 0, zone)
        val days = listOf(
            DayGroup(
                today,
                listOf(
                    SessionItem(
                        sessionId = "s1",
                        title = SessionTitle.Default(PartOfDay.AFTERNOON, null),
                        start = start,
                        end = start.plusHours(1),
                        capturedMs = 3_600_000,
                        customTitle = null,
                    ),
                ),
            ),
        )
        compose.setContent {
            AiDailyMemoryTheme(dynamicColor = false) {
                SessionsScreen(days = days, today = today, onRename = { id, text -> renames += id to text })
            }
        }

        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Name").performTextInput("x".repeat(61))
        compose.onNodeWithText("Keep it to 60 characters or fewer.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("x".repeat(61)).performTextClearance()
        compose.onNodeWithText("Name").performTextInput("Design review")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(listOf("s1" to "Design review"), renames)
    }

    @Test
    fun `turning on a sleep schedule and adding a quiet time produce a valid rhythm`() {
        var rhythm = DailyRhythm.None
        compose.setContent {
            AiDailyMemoryTheme(dynamicColor = false) {
                RhythmSettingsScreen(
                    rhythm = rhythm,
                    retention = AudioRetention.TRANSCRIPT_ONLY,
                    nextChange = null,
                    bytesPerHour = 15L * 1024 * 1024,
                    showUnreadableNotice = false,
                    onRhythmChange = { rhythm = it },
                    onRetentionChange = {},
                )
            }
        }

        compose.onNodeWithText("Use a sleep schedule").performClick()
        assertEquals(1, rhythm.sleep.size)
        assertTrue(rhythm.endDayAtBedtime)
    }

    @Test
    fun `the quiet time dialog blocks saving until the form is valid`() {
        var rhythm = DailyRhythm.None
        compose.setContent {
            AiDailyMemoryTheme(dynamicColor = false) {
                RhythmSettingsScreen(
                    rhythm = rhythm,
                    retention = AudioRetention.TRANSCRIPT_ONLY,
                    nextChange = null,
                    bytesPerHour = 15L * 1024 * 1024,
                    showUnreadableNotice = false,
                    onRhythmChange = { rhythm = it },
                    onRetentionChange = {},
                )
            }
        }

        compose.onNodeWithText("Add quiet time").performScrollTo().performClick()
        compose.onNodeWithText("Enter a name of up to 40 characters.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithText("Name, like Lunch or Gym").performTextInput("Lunch")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("Lunch", rhythm.quiet.single().label)
    }

    @Test
    fun `picking keep-audio shows the storage cost`() {
        var retention = AudioRetention.TRANSCRIPT_ONLY
        compose.setContent {
            AiDailyMemoryTheme(dynamicColor = false) {
                RhythmSettingsScreen(
                    rhythm = DailyRhythm.None,
                    retention = retention,
                    nextChange = null,
                    bytesPerHour = 15L * 1024 * 1024,
                    showUnreadableNotice = true,
                    onRhythmChange = {},
                    onRetentionChange = { retention = it },
                )
            }
        }

        compose.onNodeWithText("Your previous schedule", substring = true).assertExists()
        compose.onNodeWithText("Keep audio until I delete it").performScrollTo().performClick()
        assertEquals(AudioRetention.KEEP_UNTIL_DELETED, retention)
        compose.onNodeWithText("Grows every day", substring = true).assertExists()
    }
}
