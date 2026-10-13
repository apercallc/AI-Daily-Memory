package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionGateScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var allowed = 0
    private var settings = 0

    private fun show(mic: Boolean, notifications: Boolean, needsSettings: Boolean = false) {
        compose.setContent {
            AiDailyMemoryTheme {
                PermissionGateScreen(mic, notifications, needsSettings, onAllow = { allowed++ }, onOpenSettings = { settings++ })
            }
        }
    }

    @Test
    fun `it names what is still needed and asks on the button`() {
        show(mic = false, notifications = false)

        compose.onNodeWithText("Allow microphone and notifications").assertExists()
        compose.onNodeWithText("Allow").assertIsEnabled().performClick()

        assertEquals(1, allowed)
        assertEquals(0, settings)
    }

    @Test
    fun `a permission already allowed is marked as allowed`() {
        show(mic = true, notifications = false)

        compose.onNodeWithText("Allowed").assertExists()
        compose.onNodeWithText("Needed").assertExists()
    }

    @Test
    fun `after a permanent denial the button opens settings instead`() {
        show(mic = false, notifications = true, needsSettings = true)

        compose.onNodeWithText("Android won't ask again", substring = true).assertExists()
        compose.onNodeWithText("Open settings").performClick()

        assertEquals(1, settings)
        assertEquals(0, allowed)
    }
}
