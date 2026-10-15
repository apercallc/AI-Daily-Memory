package com.apercallc.aidailymemory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.apercallc.aidailymemory.ui.settings.SyncActions
import com.apercallc.aidailymemory.ui.settings.SyncSettingsSection
import com.apercallc.aidailymemory.ui.settings.SyncUi
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.text.DateFormat
import java.util.Date
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSettingsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(sync: SyncUi, actions: SyncActions = SyncActions()) {
        compose.setContent {
            AiDailyMemoryTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SyncSettingsSection(sync = sync, actions = actions)
                }
            }
        }
    }

    private fun assertPhoneLossWarning() {
        compose.onNodeWithText("Notes that have not synced are stored only on this phone.", substring = true)
            .assertExists()
        compose.onNodeWithText("If this phone is lost or damaged, those notes can be lost.", substring = true)
            .assertExists()
    }

    @Test
    fun `failed sync shows acknowledged last success and offers a retry without clearing failure`() {
        var retries = 0
        val lastSuccess = 1_791_986_400_000L
        show(
            SyncUi(
                connected = true,
                lastSyncSucceededAtEpochMs = lastSuccess,
                lastSyncFailed = true,
                pendingDays = listOf("2026-10-14", "2026-10-15"),
            ),
            SyncActions(onRetry = { retries++ }),
        )

        assertPhoneLossWarning()
        val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSuccess))
        compose.onNodeWithText("Last successful notes sync: $formatted").assertExists()
        compose.onNodeWithText("The last sync failed.", substring = true).assertExists()
        compose.onNodeWithText("Pending report: 2026-10-14").assertExists()
        compose.onNodeWithText("Pending report: 2026-10-15").assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, retries)
        compose.onNodeWithText("The last sync failed.", substring = true).assertExists()
        compose.onNodeWithText("Pending report: 2026-10-14").assertExists()
    }

    @Test
    fun `empty queue with no success never claims notes are backed up`() {
        show(SyncUi(connected = true))

        assertPhoneLossWarning()
        compose.onNodeWithText("No successful notes sync yet.").assertExists()
        compose.onNodeWithText("Retry sync").assertDoesNotExist()
        compose.onNodeWithText("backed up", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("All notes synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `pending reports list every exact date and offer a charging and wifi constrained retry`() {
        var retries = 0
        show(
            SyncUi(connected = true, pendingDays = listOf("2026-10-01", "2026-10-09", "2026-10-15")),
            SyncActions(onRetry = { retries++ }),
        )

        compose.onNodeWithText("Reports are waiting to sync.").assertExists()
        listOf("2026-10-01", "2026-10-09", "2026-10-15").forEach { day ->
            compose.onNodeWithText("Pending report: $day").assertExists()
        }
        compose.onNodeWithText("The last sync failed.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Retry schedules another attempt when charging on Wi-Fi.").assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `disconnected pending reports remain visible but retry is disabled`() {
        show(SyncUi(connected = false, pendingDays = listOf("2026-10-15")))

        assertPhoneLossWarning()
        compose.onNodeWithText("Waiting for an account.", substring = true).assertExists()
        compose.onNodeWithText("Pending report: 2026-10-15").assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `disconnected failed sync offers only a disabled retry`() {
        show(SyncUi(connected = false, lastSyncFailed = true))

        compose.onNodeWithText("The last sync failed.", substring = true).assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `sync off retains warning and success history without claiming backup`() {
        show(SyncUi(enabled = false, connected = true, lastSyncSucceededAtEpochMs = 1_791_986_400_000L))

        assertPhoneLossWarning()
        compose.onNodeWithText("Last successful notes sync:", substring = true).assertExists()
        compose.onNodeWithText("Also sync full transcripts").assertDoesNotExist()
        compose.onNodeWithText("Retry sync").assertDoesNotExist()
    }

    @Test
    fun `sync off keeps pending dates visible but cannot retry report uploads`() {
        show(SyncUi(enabled = false, connected = true, pendingDays = listOf("2026-10-15")))

        assertPhoneLossWarning()
        compose.onNodeWithText("Pending report: 2026-10-15").assertExists()
        compose.onNodeWithText("Report sync is off. Turn it on to send pending reports.").assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `deletion queue stays visible and retryable when report sync is off`() {
        var retries = 0
        show(
            SyncUi(enabled = false, connected = true, pendingDeletions = 2),
            SyncActions(onRetry = { retries++ }),
        )

        assertPhoneLossWarning()
        compose.onNodeWithText("2 deletions waiting for server confirmation.").assertExists()
        compose.onNodeWithText("Server copies may remain until deletion is confirmed.", substring = true).assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("2 deletions waiting for server confirmation.").assertExists()
    }

    @Test
    fun `disconnected deletion queue stays visible with report sync off`() {
        show(SyncUi(enabled = false, connected = false, pendingDeletions = 1))

        compose.onNodeWithText("1 deletion waiting for server confirmation.").assertExists()
        compose.onNodeWithText("Waiting for an account.", substring = true).assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `failed sync is retryable even without pending report dates`() {
        show(SyncUi(connected = true, lastSyncFailed = true))

        compose.onNodeWithText("The last sync failed.", substring = true).assertExists()
        compose.onNodeWithText("Retry sync").performScrollTo().assertIsEnabled()
    }
}
