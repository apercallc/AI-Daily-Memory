package com.apercallc.aidailymemory.recording.server

/**
 * The default server. A phone is either Offline (nothing leaves it) or Connected, and unless the
 * person set their own address under Advanced ([ServerAddress], ADR-0023) it connects to this host.
 */
object HostedServer {
    const val HOST = "aidailymem.apercallc.com"
    const val BASE_URL = "https://$HOST"

    /** Where a person creates an account or finds the instructions. */
    const val WEBSITE_URL = "$BASE_URL/"

    /** Where daily reports are pushed once connected. */
    const val SYNC_URL = "$BASE_URL/v1/sync"

    fun isHostedUrl(url: String): Boolean {
        val text = url.trim().trimEnd('/')
        return text.equals(BASE_URL, ignoreCase = true)
    }
}

/** Where recordings are processed. Derived from whether an account is linked, never stored. */
enum class ServerMode {
    /** Nothing leaves the phone. Recordings are kept encrypted and unprocessed. */
    OFFLINE,

    /** Speech clips go to [HostedServer] to be processed. */
    CONNECTED,
    ;

    companion object {
        fun of(linked: Boolean): ServerMode = if (linked) CONNECTED else OFFLINE
    }
}

/** The result of "Test connection", mapped from what the network returned. */
enum class ConnectionCheck {
    OK,

    /** The server answered but refused the sign-in (revoked, expired or wrong). */
    SIGN_IN_REJECTED,

    /** The server answered with a failure of its own. */
    SERVER_ERROR,

    /** No answer: offline, DNS, timeout or TLS. */
    UNREACHABLE,

    /** No account is linked yet, so there is nothing to test. */
    NOT_CONNECTED,
    ;

    val isSuccess: Boolean get() = this == OK

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_SERVER_ERROR = 500
        private val SUCCESS = 200..299

        /** @param status the HTTP status, or null when no response arrived. */
        fun fromHttpStatus(status: Int?): ConnectionCheck = when {
            status == null -> UNREACHABLE
            status in SUCCESS -> OK
            status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> SIGN_IN_REJECTED
            status >= HTTP_SERVER_ERROR -> SERVER_ERROR
            else -> SERVER_ERROR
        }
    }
}
