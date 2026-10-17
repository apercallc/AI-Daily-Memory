package com.apercallc.aidailymemory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.apercallc.aidailymemory.net.ConnectError
import com.apercallc.aidailymemory.net.LinkProblem
import com.apercallc.aidailymemory.ui.settings.AccountCallbacks
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

    private fun show(account: AccountUi, callbacks: AccountCallbacks = AccountCallbacks()) =
        compose.setContent {
            // Scrollable like the real settings screen, so lower controls can be reached.
            AiDailyMemoryTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { AccountSection(account, callbacks) }
            }
        }

    @Test
    fun `offline says so, explains the steps, and offers no server address field`() {
        show(AccountUi())

        compose.onNodeWithText("Offline. Nothing leaves this phone.").assertExists()
        compose.onNodeWithText("To connect:", substring = true).assertExists()
        compose.onAllNodesWithText("Server address").assertCountEquals(0)
        compose.onAllNodesWithText("Sync endpoint URL").assertCountEquals(0)
    }

    @Test
    fun `creating an account sends the typed email and password and the backlog choice, off by default`() {
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        show(AccountUi(), AccountCallbacks(onSignUp = { e, p, b -> sent += Triple(e, p, b) }))

        compose.onNodeWithText("Email").performScrollTo().performTextInput("me@example.com")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("a-long-enough-password")
        compose.onNodeWithText("Create account and connect").performScrollTo().performClick()
        compose.onNodeWithText("Also sync notes made before I connected").performScrollTo().performClick()
        compose.onNodeWithText("Create account and connect").performScrollTo().performClick()

        assertEquals(
            listOf(
                Triple("me@example.com", "a-long-enough-password", false),
                Triple("me@example.com", "a-long-enough-password", true),
            ),
            sent,
        )
    }

    @Test
    fun `signing in sends to the sign-in callback instead`() {
        val signedIn = mutableListOf<Pair<String, String>>()
        show(AccountUi(), AccountCallbacks(onLogIn = { e, p, _ -> signedIn += e to p }))

        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.onNodeWithText("Email").performScrollTo().performTextInput("me@example.com")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("a-long-enough-password")
        compose.onNodeWithText("Sign in and connect").performScrollTo().performClick()

        assertEquals(listOf("me@example.com" to "a-long-enough-password"), signedIn)
    }

    @Test
    fun `a pasted token connects with the token callback`() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        show(
            AccountUi(),
            AccountCallbacks(onConnectToken = { t, b -> sent += t to b }, clipboardText = { token }),
        )

        compose.onNodeWithText("I have a connect token instead").performScrollTo().performClick()
        compose.onNodeWithText("Paste from clipboard").performScrollTo().performClick()
        compose.onNodeWithText("Connect").performScrollTo().performClick()

        assertEquals(listOf(token to false), sent)
    }

    @Test
    fun `tapping the website link asks to open the hosted site`() {
        var opened = 0
        show(AccountUi(), AccountCallbacks(onOpenWebsite = { opened++ }))

        compose.onNodeWithText("Open aidailymem.apercallc.com").performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `editing clears a shown error`() {
        var edits = 0
        show(AccountUi(error = ConnectError.INVALID_EMAIL), AccountCallbacks(onEdit = { edits++ }))

        compose.onNodeWithText("Enter a valid email address.").assertExists()
        compose.onNodeWithText("Email").performScrollTo().performTextInput("a")

        assertEquals(1, edits)
    }

    private fun assertExplained(error: ConnectError, text: String) {
        show(AccountUi(error = error))
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun `an email that is already taken points to sign in`() =
        assertExplained(ConnectError.EMAIL_TAKEN, "That email already has an account. Choose Sign in instead.")

    @Test
    fun `a weak password says how long it must be`() =
        assertExplained(ConnectError.WEAK_PASSWORD, "Use at least 12 characters.")

    @Test
    fun `wrong credentials are named without saying which half was wrong`() =
        assertExplained(ConnectError.WRONG_CREDENTIALS, "That email and password don't match.")

    @Test
    fun `too many attempts says to wait`() =
        assertExplained(ConnectError.TOO_MANY_ATTEMPTS, "Too many attempts. Wait a few minutes and try again.")

    @Test
    fun `an unreachable server is reported`() = assertExplained(
        ConnectError.UNREACHABLE,
        "Couldn't reach the server. Check your internet connection and try again.",
    )

    @Test
    fun `a keystore failure says nothing was saved`() = assertExplained(
        ConnectError.SAVE_FAILED,
        "Couldn't store the token securely on this phone. Nothing was saved.",
    )

    @Test
    fun `a rejected token is explained`() {
        show(AccountUi(error = ConnectError.REJECTED))
        compose.onNodeWithText("I have a connect token instead").performScrollTo().performClick()
        compose.onNodeWithText("The server didn't accept this token. Check it or get a new one from the website.")
            .assertExists()
    }

    @Test
    fun `while checking, connect can't be pressed twice`() {
        show(AccountUi(connecting = true))

        compose.onNodeWithText("Checking…").assertIsNotEnabled()
    }

    @Test
    fun `a signed-out, unreadable or removed link asks the user to reconnect`() {
        show(AccountUi(problem = LinkProblem.UNREADABLE))
        compose.onNodeWithText("This phone can no longer read its saved connection. Connect again.").assertExists()
    }

    @Test
    fun `a saved server address that could not be used is explained`() {
        show(AccountUi(problem = LinkProblem.OTHER_SERVER))

        compose.onNodeWithText("saved server address could not be used", substring = true).assertExists()
    }

    @Test
    fun `the hosted server is the default and a server field is one tap away`() {
        val typed = mutableListOf<String>()
        show(AccountUi(), AccountCallbacks(onServerChange = { typed += it }))

        compose.onNodeWithText("Server: aidailymem.apercallc.com (default)").assertExists()
        compose.onNodeWithText("Server address").assertDoesNotExist()
        compose.onNodeWithText("Use my own server").performClick()
        compose.onNodeWithText("Server address").performTextInput("https://memory.example")

        assertEquals("https://memory.example", typed.last())
    }

    @Test
    fun `an own server is named, hides the hosted website link and can go back to the default`() {
        val typed = mutableListOf<String>()
        show(AccountUi(serverUrl = "https://memory.example"), AccountCallbacks(onServerChange = { typed += it }))

        compose.onNodeWithText("Server: memory.example (your own)").assertExists()
        compose.onNodeWithText("Open aidailymem.apercallc.com").assertDoesNotExist()
        compose.onNodeWithText("Use the default server").performClick()

        assertEquals(listOf(""), typed)
    }

    @Test
    fun `an own server is named as the place credentials go before sign-in`() {
        show(AccountUi(serverUrl = "https://memory.example"))

        compose.onNodeWithText("go to memory.example, not to aidailymem.apercallc.com", substring = true).assertExists()
    }

    @Test
    fun `the hosted default shows no credentials warning`() {
        show(AccountUi())

        compose.onNodeWithText("Only continue if you run that server", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a server address that is not usable shows why`() {
        show(AccountUi(serverDraft = "http://plain.example"))

        compose.onNodeWithText("Use an https address", substring = true).assertExists()
    }

    @Test
    fun `a connected account shows the host, test and disconnect`() {
        var disconnected = false
        var tested = 0
        show(
            AccountUi(host = hostOf("https://aidailymem.apercallc.com/v1")),
            AccountCallbacks(
                onDisconnect = { disconnected = true },
                onTest = { tested++ },
            ),
        )

        compose.onNodeWithText("Connected to aidailymem.apercallc.com").assertExists()
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertEquals(1, tested)
        assertTrue(disconnected)
    }

    @Test
    fun `the test button is disabled while a test is running`() {
        show(AccountUi(host = "aidailymem.apercallc.com", testing = true))

        compose.onNodeWithText("Testing…").assertIsNotEnabled()
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
    fun `connection inputs cannot change while their consent is being checked`() {
        show(AccountUi(connecting = true))
        compose.onNodeWithText("Email").assertIsNotEnabled()
        compose.onNodeWithText("Password").assertIsNotEnabled()
        compose.onNodeWithText("Also sync notes made before I connected").assertIsNotEnabled()
    }

    @Test
    fun `connecting explains what leaves the phone`() {
        show(AccountUi())
        compose.onNodeWithText("Connecting syncs your notes", substring = true)
            .assertExists()
    }

    @Test
    fun `host display never shows paths or credentials`() {
        assertEquals("api.example", hostOf("https://api.example/v1?x=1"))
        assertEquals("not a url", hostOf("not a url"))
    }
}
