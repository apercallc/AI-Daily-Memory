package com.apercallc.aidailymemory.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.net.ConnectError
import com.apercallc.aidailymemory.net.LinkProblem
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

    private fun show(
        account: AccountUi,
        onConnect: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onEdit: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) = compose.setContent {
        AiDailyMemoryTheme { AccountSection(account, onConnect, onEdit, onDisconnect, onRetry) }
    }

    @Test
    fun `connect sends the typed values and the backlog choice, off by default`() {
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        show(AccountUi(), onConnect = { u, t, b -> sent += Triple(u, t, b) })

        compose.onNodeWithText("Server address").performTextClearance()
        compose.onNodeWithText("Server address").performTextInput("https://api.example")
        compose.onNodeWithText("Device token").performTextInput(token)
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Also transcribe speech already recorded on this phone").performClick()
        compose.onNodeWithText("Connect").performClick()

        assertEquals(
            listOf(
                Triple("https://api.example", token, false),
                Triple("https://api.example", token, true),
            ),
            sent,
        )
    }

    @Test
    fun `editing clears a shown error`() {
        var edits = 0
        show(AccountUi(error = ConnectError.INVALID_URL), onEdit = { edits++ })

        compose.onNodeWithText("Enter an https:// address.").assertExists()
        compose.onNodeWithText("Device token").performTextInput("a")

        assertEquals(1, edits)
    }

    @Test
    fun `each connect problem is explained`() {
        show(AccountUi(error = ConnectError.REJECTED))
        compose.onNodeWithText("The server didn't accept this token. Check it or ask for a new one.")
            .assertExists()
    }

    @Test
    fun `unreachable and save failures are explained`() {
        show(AccountUi(error = ConnectError.UNREACHABLE))
        compose.onNodeWithText("Couldn't reach the server. Check the address and your connection.")
            .assertExists()
    }

    @Test
    fun `while checking, connect can't be pressed twice`() {
        show(AccountUi(connecting = true))

        compose.onNodeWithText("Checking…").assertIsNotEnabled()
    }

    @Test
    fun `a signed-out or unreadable link asks the user to reconnect`() {
        show(AccountUi(problem = LinkProblem.UNREADABLE))

        compose.onNodeWithText("This phone can no longer read its saved connection. Connect again.")
            .assertExists()
    }

    @Test
    fun `a connected account shows the host, upload problems with retry, and disconnect`() {
        var retried = false
        var disconnected = false
        show(
            AccountUi(host = hostOf("https://api.example/v1"), uploadProblems = 2),
            onDisconnect = { disconnected = true },
            onRetry = { retried = true },
        )

        compose.onNodeWithText("Connected to api.example").assertExists()
        compose.onNodeWithText("2 clips couldn't upload. Their audio stays on this phone.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        assertTrue(retried)
        assertTrue(disconnected)
    }

    @Test
    fun `a revoke still waiting for the network is shown`() {
        show(AccountUi(revokePending = true))

        compose.onNodeWithText(
            "Your previous connection will be revoked on the server as soon as this phone is online.",
        ).assertExists()
    }

    @Test
    fun `a token the phone could not revoke itself is flagged`() {
        show(AccountUi(revokeManual = true))

        compose.onNodeWithText(
            "An old connection couldn't be revoked from this phone. Ask for its device token to be revoked on the server.",
        ).assertExists()
    }

    @Test
    fun `host display never shows paths or credentials`() {
        assertEquals("api.example", hostOf("https://api.example/v1?x=1"))
        assertEquals("not a url", hostOf("not a url"))
    }
}
