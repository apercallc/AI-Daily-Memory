package com.apercallc.aidailymemory.recording.server

/**
 * A tappable link that connects the app: `aidailymem://connect?token=adm_…`. The website shows
 * it after sign-in, so a person never copies a token by hand.
 *
 * Only the token is read. Any `host` or `server` parameter is ignored on purpose: the server is
 * chosen in Settings, and a link must not be able to point the app somewhere else.
 */
object ConnectLink {
    const val SCHEME = "aidailymem"
    const val HOST = "connect"
    private const val TOKEN_PARAMETER = "token"
    private val TOKEN = Regex("^adm_[A-Za-z0-9_-]{20,120}$")

    /** The token inside [link], or null when it is not a well-formed connect link. */
    fun tokenFrom(link: String?): String? {
        val text = link?.trim() ?: return null
        val prefix = "$SCHEME://$HOST"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val rest = text.substring(prefix.length)
        if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
        val query = rest.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        val value = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == TOKEN_PARAMETER }
            ?.second
            ?: return null
        return value.takeIf { TOKEN.matches(it) }
    }

    /** Accepts a pasted token or a pasted connect link; returns the token or null. */
    fun tokenFromPaste(input: String): String? {
        val text = input.trim()
        return tokenFrom(text) ?: text.takeIf { TOKEN.matches(it) }
    }
}
