package com.apercallc.aidailymemory.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.capture.CaptureOverview
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.HealthIssue
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.recording.segment.CaptureSummary
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Critical capture flows on the real composables (Robolectric, no device needed). */
@RunWith(RobolectricTestRunner::class)
class CaptureScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<String>()

    private fun show(
        status: RecordingStatus,
        problem: CaptureProblem? = null,
        health: CaptureHealth = CaptureHealth.Unknown,
        overview: CaptureOverview? = null,
        fontScale: Float = 1f,
    ) {
        val state = RecordingState(
            status = status,
            sessionId = if (status == RecordingStatus.IDLE) null else SessionId("s"),
            revision = 1,
            problem = problem,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
              AiDailyMemoryTheme(dynamicColor = false) {
                CaptureScreen(
                    model = CaptureStatusPresenter.present(state, health),
                    overview = overview,
                    batteryRestricted = false,
                    snackbar = SnackbarHostState(),
                    onPrimaryAction = { actions += "primary:$it" },
                    onRetry = { actions += "retry" },
                    onStop = { actions += "stop" },
                    onMark = { actions += "mark:$it" },
                    onBatterySettings = { actions += "battery" },
                    onOpenSessions = { actions += "sessions" },
                    onOpenSettings = { actions += "settings" },
                )
              }
            }
        }
    }

    @Test
    fun `recording offers a one-tap privacy pause and markers`() {
        show(RecordingStatus.RECORDING)

        compose.onNodeWithText("Recording").assertExists()
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Highlight").assertIsEnabled().performClick()
        compose.onNodeWithText("Quick Note").assertIsEnabled()
        compose.onNodeWithText("End Session").performClick()

        assertEquals(
            listOf("primary:${PrimaryAction.PAUSE}", "mark:${MarkerKind.HIGHLIGHT}", "stop"),
            actions,
        )
    }

    @Test
    fun `a stalled recorder never claims to be recording`() {
        show(RecordingStatus.RECORDING, health = CaptureHealth(listOf(HealthIssue.CAPTURE_STALLED)))

        compose.onNodeWithText("Not capturing audio").assertExists()
        compose.onAllNodesWithText("Recording").assertCountEquals(0)
        compose.onNodeWithText("Highlight").assertIsNotEnabled()
    }

    @Test
    fun `an interruption after a reboot explains the gap and offers resume`() {
        show(RecordingStatus.INTERRUPTED, problem = CaptureProblem.DEVICE_REBOOTED)

        compose.onNodeWithText("Recording interrupted").assertExists()
        compose.onNodeWithText(
            "Your phone restarted while recording. Nothing was recorded after that.",
        ).assertExists()
        compose.onNodeWithText("Resume recording").performClick()

        assertEquals(listOf("retry"), actions)
    }

    @Test
    fun `paused shows resume and says nothing is being recorded`() {
        show(RecordingStatus.PAUSED)

        compose.onNodeWithText("The microphone is off. Nothing is being recorded.").assertExists()
        compose.onNodeWithText("Resume").performClick()

        assertEquals(listOf("primary:${PrimaryAction.RESUME}"), actions)
    }

    @Test
    fun `saved and unsynced audio are reported honestly`() {
        show(
            RecordingStatus.RECORDING,
            overview = CaptureOverview(
                health = CaptureHealth.Unknown,
                summary = CaptureSummary(
                    capturedMs = 65 * 60_000L,
                    pendingUploadMs = 65 * 60_000L,
                    pendingUploadBytes = 1,
                    quarantinedSegments = 0,
                    lastPersistedSequence = 64,
                ),
                storage = StorageHealth(StorageLevel.OK, 125, pendingOverBudget = false),
            ),
        )

        // The status card merges its children for TalkBack; count the individual texts.
        compose.onAllNodesWithText("1 h 5 min", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("2 h 5 min", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `navigation opens sessions and settings without changing recording`() {
        show(RecordingStatus.RECORDING)
        compose.onNodeWithText("Sessions").performScrollTo().performClick()
        compose.onNodeWithText("Schedule & audio").performScrollTo().performClick()
        assertEquals(listOf("sessions", "settings"), actions)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `storage value stays readable at double font scale on a narrow phone`() {
        show(
            RecordingStatus.RECORDING,
            overview = CaptureOverview(
                health = CaptureHealth.Unknown,
                summary = CaptureSummary(0, 0, 0, 0, null),
                storage = StorageHealth(StorageLevel.OK, 125, pendingOverBudget = false),
            ),
            fontScale = 2f,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("2 h 5 min", useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(layouts.single().size.width > 0)
    }

    @Test
    fun `every control meets the 48dp touch target`() {
        show(RecordingStatus.INTERRUPTED, problem = CaptureProblem.PHONE_CALL)

        listOf("Pause", "Resume recording", "Highlight", "Quick Note", "End Session").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertExists()
            compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp)
        }
    }
}
