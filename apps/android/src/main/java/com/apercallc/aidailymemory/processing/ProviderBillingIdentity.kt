package com.apercallc.aidailymemory.processing

import java.net.URL

/** Maps only exact first-party API hosts to stable billing identities. */
internal object ProviderBillingIdentity {
    fun fromUrl(raw: String): String? = runCatching { URL(raw).host.lowercase() }
        .getOrNull()
        ?.let { host ->
            when (host) {
                "api.deepgram.com" -> "deepgram"
                "api.openai.com" -> "openai"
                "api.groq.com" -> "groq"
                "openrouter.ai" -> "openrouter"
                else -> null
            }
        }
}
