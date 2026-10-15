package com.apercallc.aidailymemory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.ui.settings.SyncActions
import com.apercallc.aidailymemory.ui.settings.SyncSettingsSection
import com.apercallc.aidailymemory.ui.settings.SyncUi
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSettingsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `failed sync shows last success and offers a retry`() {
        var retries = 0
        compose.setContent {
            AiDailyMemoryTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SyncSettingsSection(
                        sync = SyncUi(
                            enabled = true,
                            connected = true,
                            lastSyncSucceededAtEpochMs = 1_791_986_400_000L,
                            lastSyncFailed = true,
                        ),
                        actions = SyncActions(onRetry = { retries++ }),
                    )
                }
            }
        }

        compose.onNodeWithText("Notes that have not synced are stored only on this phone.", substring = true)
            .assertExists()
        compose.onNodeWithText("Last successful notes sync:", substring = true).assertExists()
        compose.onNodeWithText("Unsynced changes remain on this phone", substring = true).assertExists()
        compose.onNodeWithText("Retry sync").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `sync with no recorded success says so and does not show retry`() {
        compose.setContent {
            AiDailyMemoryTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SyncSettingsSection(
                        sync = SyncUi(enabled = true, connected = true),
                        actions = SyncActions(),
                    )
                }
            }
        }

        compose.onNodeWithText("No successful notes sync yet.").assertExists()
        compose.onNodeWithText("Retry sync").assertDoesNotExist()
    }
}
