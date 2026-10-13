package com.apercallc.aidailymemory.net

import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.server.ConnectionCheck
import java.io.IOException
import java.security.GeneralSecurityException

/** Why connecting didn't work, for the settings screen. */
enum class ConnectError {
    INVALID_TOKEN,
    REJECTED,
    UNREACHABLE,
    SAVE_FAILED,
    INVALID_EMAIL,
    WEAK_PASSWORD,
    EMAIL_TAKEN,
    WRONG_CREDENTIALS,
    TOO_MANY_ATTEMPTS,
}

/**
 * Connect, test and disconnect. Blocking (network + Keystore): call from a background dispatcher.
 *
 * There is one server ([com.apercallc.aidailymemory.recording.server.HostedServer]); a person
 * either creates an account or signs in (the app then holds a device token) or pastes a token
 * from the website. Either way the token is verified with the server before it is stored, so a
 * typo is caught on the spot instead of at the next charging window. Disconnect revokes the
 * token on the server too (AGENTS rule 4); if that call can't be made, the local copy is still
 * gone and the token can be revoked by the operator.
 */
class AccountLinker(
    private val links: LinkStore,
    private val api: AccountApi,
    private val onLinked: () -> Unit,
    private val onUnlinked: () -> Unit,
    /** The server revoke didn't go through: arrange a retry when online. */
    private val onRevokePending: () -> Unit = {},
    /** Registers this phone's device key for a just-verified link (ADR-0014); throws on failure. */
    private val registerKey: (ServerLink) -> Unit = {},
    private val signIn: SignInApi? = null,
    /** Where sign-in goes: the hosted server unless the person chose their own. */
    private val server: () -> String = { links.serverUrl.value },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun connect(token: String, includeBacklog: Boolean): ConnectError? {
        val link = when (val input = LinkInput.parse(token, server())) {
            LinkInput.InvalidToken -> return ConnectError.INVALID_TOKEN
            is LinkInput.Valid -> input.link
        }
        return verifyAndSave(link, includeBacklog)
    }

    /** Creates an account on the server and connects this phone to it. */
    fun signUp(email: String, password: String, deviceName: String, includeBacklog: Boolean): ConnectError? =
        authenticate(includeBacklog) { checkNotNull(signIn).signUp(server(), email.trim(), password, deviceName) }

    /** Signs in to an existing account and connects this phone to it. */
    fun logIn(email: String, password: String, deviceName: String, includeBacklog: Boolean): ConnectError? =
        authenticate(includeBacklog) { checkNotNull(signIn).logIn(server(), email.trim(), password, deviceName) }

    private fun authenticate(includeBacklog: Boolean, issue: () -> String): ConnectError? {
        val token = try {
            issue()
        } catch (e: ApiException) {
            return authError(e)
        } catch (e: IOException) {
            logFailure(TAG, "sign_in_unreachable", e, warning = true)
            return ConnectError.UNREACHABLE
        }
        return connect(token, includeBacklog)
    }

    private fun authError(e: ApiException): ConnectError = when (e.code) {
        "invalid_email" -> ConnectError.INVALID_EMAIL
        "weak_password" -> ConnectError.WEAK_PASSWORD
        "email_taken" -> ConnectError.EMAIL_TAKEN
        "invalid_credentials" -> ConnectError.WRONG_CREDENTIALS
        else -> if (e.status == HTTP_TOO_MANY_REQUESTS) ConnectError.TOO_MANY_ATTEMPTS else ConnectError.UNREACHABLE
    }

    private fun verifyAndSave(link: ServerLink, includeBacklog: Boolean): ConnectError? {
        // Any disconnected token still waiting for its revoke goes first.
        retryPendingRevoke()
        try {
            api.me(link)
            // Close the window in which someone else holding this token could register a key.
            registerKey(link)
        } catch (e: ApiException) {
            val refused = e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN
            return if (refused || e.code == "device_key_mismatch") {
                ConnectError.REJECTED
            } else {
                ConnectError.UNREACHABLE
            }
        } catch (e: IOException) {
            logFailure(TAG, "account_check_failed", e, warning = true)
            return ConnectError.UNREACHABLE
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "device_key_unavailable", e)
            return ConnectError.SAVE_FAILED
        }
        try {
            links.save(link, now(), includeBacklog)
        } catch (e: GeneralSecurityException) {
            // Keystore unusable: nothing is stored in the clear.
            logFailure(TAG, "account_link_save_failed", e)
            return ConnectError.SAVE_FAILED
        }
        onLinked()
        return null
    }

    /**
     * Asks the server who this phone is, without changing anything. Safe to run any time; a
     * refusal is reported but does not unlink (use Connect again with a new sign-in).
     */
    fun testConnection(): ConnectionCheck {
        val link = links.link.value ?: return ConnectionCheck.NOT_CONNECTED
        return try {
            api.me(link)
            ConnectionCheck.OK
        } catch (e: ApiException) {
            ConnectionCheck.fromHttpStatus(e.status)
        } catch (e: IOException) {
            logFailure(TAG, "connection_test_failed", e, warning = true)
            ConnectionCheck.UNREACHABLE
        }
    }

    fun disconnect() {
        val link = links.link.value ?: return
        links.clearKeepingForRevoke(link)
        onUnlinked()
        if (!retryPendingRevoke()) onRevokePending()
    }

    /**
     * Revokes a disconnected token on the server. True when nothing is left pending: revoked
     * now, already invalid, or nothing to do. False means try again when online.
     */
    fun retryPendingRevoke(): Boolean {
        var allDone = true
        for ((id, pending) in links.pendingRevokes()) {
            try {
                api.revokeCurrent(pending)
                links.revokeDone(id)
            } catch (e: ApiException) {
                // Already revoked or expired: the goal is met.
                if (e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN) {
                    links.revokeDone(id)
                } else {
                    logFailure(TAG, "account_revoke_failed", e, warning = true)
                    allDone = false
                }
            } catch (e: IOException) {
                logFailure(TAG, "account_revoke_failed", e, warning = true)
                allDone = false
            }
        }
        return allDone
    }

    private companion object {
        const val TAG = "AccountLinker"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
