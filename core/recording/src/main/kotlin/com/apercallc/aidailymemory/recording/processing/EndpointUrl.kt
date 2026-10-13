package com.apercallc.aidailymemory.recording.processing

import java.net.URI
import java.net.URISyntaxException

/**
 * Validates a user-typed API endpoint (transcription, extraction, sync) before it is stored.
 * Bearer tokens and audio travel to these URLs, so only `https` with a real host is accepted
 * (mirrors the account connect rule), and credentials embedded in the URL are refused.
 */
sealed interface EndpointUrl {
    /** [value] is the trimmed URL that is safe to store and call. */
    data class Valid(val value: String) : EndpointUrl

    /** Nothing typed: the setting is cleared rather than flagged. */
    data object Blank : EndpointUrl

    data object Invalid : EndpointUrl

    companion object {
        private val HOST = Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
        private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")

        fun parse(input: String): EndpointUrl {
            val text = input.trim()
            if (text.isEmpty()) return Blank
            if (text.any { it.isWhitespace() || it.isISOControl() }) return Invalid
            val uri = try {
                URI(text)
            } catch (_: URISyntaxException) {
                return Invalid
            }
            val host = uri.host ?: return Invalid
            val validHost = HOST.matches(host) || IPV6.matches(host)
            val valid = uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null &&
                validHost &&
                host.length <= MAX_HOST
            return if (valid) Valid(text) else Invalid
        }

        private const val MAX_HOST = 253
    }
}
