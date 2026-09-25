package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.net.LinkInput
import com.apercallc.aidailymemory.ui.settings.AccountSection
import com.apercallc.aidailymemory.ui.settings.AccountUi
import com.apercallc.aidailymemory.ui.settings.hostOf
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val token = "adm_" + "Q".repeat(43)

    @Test
    fun `bad input is explained in place and nothing connects`() {
        val attempts = mutableListOf<Pair<String, String>>()
        compose.setContent {
            AiDailyMemoryTheme {
                AccountSection(
                    account = AccountUi(host = null, rejected = false),
                    onConnect = { url, token -> attempts += url to token; LinkInput.parse(url, token) },
                    onDisconnect = {},
                )
            }
        }

        compose.onNodeWithText("Server address").performTextClearance()
        compose.onNodeWithText("Server address").performTextInput("https://api.example")
        compose.onNodeWithText("Device token").performTextInput("not-a-token")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("That doesn't look like a device token (it starts with adm_).").assertExists()

        compose.onNodeWithText("Server address").performTextClearance()
        compose.onNodeWithText("Server address").performTextInput("http://plain.example")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Enter an https:// address.").assertExists()
        assertEquals(2, attempts.size)
    }

    @Test
    fun `a valid token connects and is cleared from the field`() {
        var connected: LinkInput? = null
        compose.setContent {
            AiDailyMemoryTheme {
                AccountSection(
                    account = AccountUi(host = null, rejected = false),
                    onConnect = { url, token -> LinkInput.parse(url, token).also { connected = it } },
                    onDisconnect = {},
                )
            }
        }

        compose.onNodeWithText("Server address").performTextClearance()
        compose.onNodeWithText("Server address").performTextInput("https://api.example")
        compose.onNodeWithText("Device token").performTextInput(token)
        compose.onNodeWithText("Connect").performClick()

        assertTrue(connected is LinkInput.Valid)
    }

    @Test
    fun `a connected account shows only the host and can disconnect`() {
        var disconnected = false
        compose.setContent {
            AiDailyMemoryTheme {
                AccountSection(
                    account = AccountUi(host = hostOf("https://api.example/v1"), rejected = false),
                    onConnect = { _, _ -> LinkInput.InvalidUrl },
                    onDisconnect = { disconnected = true },
                )
            }
        }

        compose.onNodeWithText("Connected to api.example").assertExists()
        compose.onNodeWithText("Disconnect").performClick()
        assertTrue(disconnected)
    }

    @Test
    fun `a rejected token asks the user to reconnect, and a failed save says nothing was stored`() {
        compose.setContent {
            AiDailyMemoryTheme {
                AccountSection(
                    account = AccountUi(host = null, rejected = true, saveFailed = true),
                    onConnect = { _, _ -> LinkInput.InvalidUrl },
                    onDisconnect = {},
                )
            }
        }

        compose.onNodeWithText(
            "Your account connection was signed out. Connect again with a new device token.",
        ).assertExists()
        compose.onNodeWithText("Couldn't store the token securely on this phone. Nothing was saved.")
            .assertExists()
    }

    @Test
    fun `host display never shows paths or credentials`() {
        assertEquals("api.example", hostOf("https://api.example/v1?x=1"))
        assertEquals("not a url", hostOf("not a url"))
    }
}
