package com.apercallc.aidailymemory.recording.processing

/**
 * Ready-made choices for the two own-provider stages, so a person picks from a list instead of
 * typing addresses and model names. Picking one fills the address and model; both stay editable,
 * and "Custom" leaves them to the person.
 *
 * Only providers whose wire format the app already speaks are listed:
 * transcription = Deepgram-style (audio body, `Token` auth, `results.channels` reply);
 * summary = OpenAI-style chat completions (`Bearer` auth, `choices[0].message.content`).
 * Model names go stale, so every model list is a suggestion and a typed model is always allowed.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    /** Address the app calls. For [modelInQuery] presets this is the base the model is added to. */
    val endpoint: String,
    val models: List<String>,
    /** Where a person gets an API key. */
    val keyUrl: String,
    /** The model is sent as `?model=` on the address instead of in the request body. */
    val modelInQuery: Boolean = false,
) {
    val defaultModel: String get() = models.first()

    /** The address to call for [model]; same as [endpoint] unless the model rides in the query. */
    fun endpointFor(model: String): String = if (modelInQuery) {
        "$endpoint?model=${model.trim()}&smart_format=true&diarize=true"
    } else {
        endpoint
    }
}

object ProviderPresets {
    const val CUSTOM_ID = "custom"

    val transcription: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepgram",
            label = "Deepgram",
            endpoint = "https://api.deepgram.com/v1/listen",
            models = listOf("nova-3", "nova-2"),
            keyUrl = "https://console.deepgram.com/signup",
            modelInQuery = true,
        ),
    )

    val summary: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            label = "OpenAI",
            endpoint = "https://api.openai.com/v1/chat/completions",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        ProviderPreset(
            id = "anthropic",
            label = "Anthropic (Claude)",
            endpoint = "https://api.anthropic.com/v1/chat/completions",
            models = listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
            keyUrl = "https://console.anthropic.com/settings/keys",
        ),
        ProviderPreset(
            id = "groq",
            label = "Groq",
            endpoint = "https://api.groq.com/openai/v1/chat/completions",
            models = listOf("llama-3.3-70b-versatile"),
            keyUrl = "https://console.groq.com/keys",
        ),
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            models = listOf("openai/gpt-4o-mini", "meta-llama/llama-3.3-70b-instruct"),
            keyUrl = "https://openrouter.ai/keys",
        ),
    )

    /**
     * Which preset a stored address belongs to, or null for a custom one. A Deepgram address with
     * any query still matches, so a model the person typed does not make it look custom.
     */
    fun match(presets: List<ProviderPreset>, url: String?): ProviderPreset? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        return presets.firstOrNull { text.substringBefore('?').equals(it.endpoint, ignoreCase = true) }
    }

    /** The `model` query value on a [ProviderPreset.modelInQuery] address, if any. */
    fun modelInUrl(url: String?): String? = url
        ?.substringAfter('?', "")
        ?.split('&')
        ?.firstOrNull { it.startsWith("model=") }
        ?.removePrefix("model=")
        ?.takeIf { it.isNotBlank() }
}
