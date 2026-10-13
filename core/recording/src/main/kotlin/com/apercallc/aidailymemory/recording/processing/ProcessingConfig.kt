package com.apercallc.aidailymemory.recording.processing

/** What is still missing before processing can run. Each maps to one settings field. */
enum class ConfigGap {
    /** Transcription runs on the phone, but its free speech model is not downloaded yet. */
    TRANSCRIPTION_MODEL,
    TRANSCRIPTION_URL,
    TRANSCRIPTION_KEY,
    EXTRACTION_URL,
    EXTRACTION_KEY,
    EXTRACTION_MODEL,
}

/** One stage's settings as stored, before validation. */
data class StageConfig(
    val providerIsUserApi: Boolean,
    val urlValid: Boolean,
    val keyPresent: Boolean,
)

/**
 * Decides whether processing is configured. An incomplete setup is shown to the user and the
 * pipeline waits; it must never run with a placeholder provider that marks every session
 * FAILED (AGENTS.md: no silent failure, and failures should be correctable).
 */
object ProcessingConfig {
    /**
     * Transcription defaults to the phone's own free model; the person's API key is an
     * alternative. Summaries are off unless the person supplies a key: there is no free summary
     * engine, so an unset summary stage is a valid, complete setup (transcript only), not a gap.
     */
    fun gaps(
        transcription: StageConfig,
        extraction: StageConfig,
        extractionModelPresent: Boolean,
        onDeviceModelReady: Boolean,
    ): List<ConfigGap> = buildList {
        if (!transcription.providerIsUserApi) {
            if (!onDeviceModelReady) add(ConfigGap.TRANSCRIPTION_MODEL)
        } else {
            if (!transcription.urlValid) add(ConfigGap.TRANSCRIPTION_URL)
            if (!transcription.keyPresent) add(ConfigGap.TRANSCRIPTION_KEY)
        }
        if (extraction.providerIsUserApi) {
            if (!extraction.urlValid) add(ConfigGap.EXTRACTION_URL)
            if (!extraction.keyPresent) add(ConfigGap.EXTRACTION_KEY)
            if (!extractionModelPresent) add(ConfigGap.EXTRACTION_MODEL)
        }
    }
}
