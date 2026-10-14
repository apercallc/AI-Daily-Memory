package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var result: OnboardingResult? = null

    private fun show() = compose.setContent {
        AiDailyMemoryTheme { OnboardingScreen(onFinish = { result = it }) }
    }

    @Test
    fun `a new install walks through four steps and ends offline by default`() {
        show()

        compose.onNodeWithText("Step 1 of 4").assertExists()
        compose.onNodeWithText("Your day, remembered").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Where should your memory live?").assertExists()
        compose.onNodeWithText("Keep notes on this phone").assertIsSelected()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Allow what recording needs").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("You're ready").assertExists()
        compose.onNodeWithText("Get started").performClick()

        assertEquals(OnboardingResult.STAY_OFFLINE, result)
    }

    @Test
    fun `choosing to connect sends the person to set up their account`() {
        show()

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Sync my notes").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Set up my account").performClick()

        assertEquals(OnboardingResult.CONNECT_ACCOUNT, result)
    }

    @Test
    fun `skip leaves at once and back goes a step`() {
        show()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Step 1 of 4").assertExists()

        compose.onNodeWithText("Skip").performClick()

        assertEquals(OnboardingResult.STAY_OFFLINE, result)
    }

    @Test
    fun `skip never connects, even after choosing to`() {
        show()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Sync my notes").performClick()

        compose.onNodeWithText("Skip").performClick()

        assertEquals(OnboardingResult.STAY_OFFLINE, result)
    }

    @Test
    fun `the permission step waits until the microphone and notifications are allowed`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        // Robolectric grants what the manifest declares; this phone has allowed nothing yet.
        org.robolectric.Shadows.shadowOf(app).denyPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val permissions = com.apercallc.aidailymemory.platform.Permissions(app)
        compose.setContent { AiDailyMemoryTheme { OnboardingScreen(permissions) { result = it } } }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("Allow microphone").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }
}
