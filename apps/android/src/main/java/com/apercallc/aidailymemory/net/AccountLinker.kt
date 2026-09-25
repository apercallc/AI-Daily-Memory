package com.apercallc.aidailymemory.net

import com.apercallc.aidailymemory.platform.logFailure
import java.io.IOException
import java.security.GeneralSecurityException

/** Why connecting didn't work, for the settings screen. */
enum class ConnectError { INVALID_URL, INVALID_TOKEN, REJECTED, UNREACHABLE, SAVE_FAILED }

/**
 * Connect and disconnect. Blocking (network + Keystore): call from a background dispatcher.
 *
 * A token is verified with the server before it is stored, so a typo is caught on the spot
 * instead of at the next charging window. Disconnect revokes the token on the server too
 * (AGENTS rule 4); if that call can't be made, the local copy is still gone and the token can
 * be revoked by the operator.
 */
class AccountLinker(
    private val links: LinkStore,
    private val api: AccountApi,
    private val onLinked: () -> Unit,
    private val onUnlinked: () -> Unit,
    /** The server revoke didn't go through: arrange a retry when online. */
    private val onRevokePending: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun connect(url: String, token: String, includeBacklog: Boolean): ConnectError? {
        val link = when (val input = LinkInput.parse(url, token)) {
            LinkInput.InvalidUrl -> return ConnectError.INVALID_URL
            LinkInput.InvalidToken -> return ConnectError.INVALID_TOKEN
            is LinkInput.Valid -> input.link
        }
        try {
            api.me(link)
        } catch (e: ApiException) {
            return if (e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN) {
                ConnectError.REJECTED
            } else {
                ConnectError.UNREACHABLE
            }
        } catch (e: IOException) {
            logFailure(TAG, "account_check_failed", e, warning = true)
            return ConnectError.UNREACHABLE
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
        val pending = links.pendingRevoke() ?: return true
        return try {
            api.revokeCurrent(pending)
            links.revokeDone()
            true
        } catch (e: ApiException) {
            // Already revoked or expired: the goal is met.
            val gone = e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN
            if (gone) links.revokeDone() else logFailure(TAG, "account_revoke_failed", e, warning = true)
            gone
        } catch (e: IOException) {
            logFailure(TAG, "account_revoke_failed", e, warning = true)
            false
        }
    }

    private companion object {
        const val TAG = "AccountLinker"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
