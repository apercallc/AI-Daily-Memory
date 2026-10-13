package com.apercallc.aidailymemory.recording.server

import com.apercallc.aidailymemory.recording.processing.EndpointUrl

/**
 * The server address a person may type under Advanced. The default is [HostedServer]; anyone
 * running their own copy of the open-source backend can point the app there instead.
 *
 * Only an origin is kept (`https://host[:port]`): the app adds its own paths, so a pasted
 * `https://my.example/v1/sync` or a trailing slash is reduced to the origin. Tokens and
 * recordings travel to this address, so the same rule as every other endpoint applies: https
 * with a real host and no credentials in the URL.
 */
sealed interface ServerAddress {
    /** [origin] is safe to store and to build request URLs from. */
    data class Valid(val origin: String) : ServerAddress

    /** Nothing typed: the default server is used. */
    data object Blank : ServerAddress

    data object Invalid : ServerAddress

    companion object {
        fun parse(input: String): ServerAddress {
            val text = input.trim()
            if (text.isEmpty()) return Blank
            val endpoint = EndpointUrl.parse(text) as? EndpointUrl.Valid ?: return Invalid
            val uri = java.net.URI(endpoint.value)
            if (uri.rawQuery != null || uri.rawFragment != null) return Invalid
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return Valid("https://${uri.host.lowercase()}$port")
        }
    }
}
