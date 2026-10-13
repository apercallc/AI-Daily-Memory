package com.apercallc.aidailymemory.recording.processing

/** What is still missing before processing can run. Each maps to one settings field. */
enum class ConfigGap {
    TRANSCRIPTION_PROVIDER,
    TRANSCRIPTION_URL,
    TRANSCRIPTION_KEY,
    EXTRACTION_PROVIDER,
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
    fun gaps(
        transcription: StageConfig,
        extraction: StageConfig,
        extractionModelPresent: Boolean,
    ): List<ConfigGap> = buildList {
        if (!transcription.providerIsUserApi) {
            add(ConfigGap.TRANSCRIPTION_PROVIDER)
        } else {
            if (!transcription.urlValid) add(ConfigGap.TRANSCRIPTION_URL)
            if (!transcription.keyPresent) add(ConfigGap.TRANSCRIPTION_KEY)
        }
        if (!extraction.providerIsUserApi) {
            add(ConfigGap.EXTRACTION_PROVIDER)
        } else {
            if (!extraction.urlValid) add(ConfigGap.EXTRACTION_URL)
            if (!extraction.keyPresent) add(ConfigGap.EXTRACTION_KEY)
            if (!extractionModelPresent) add(ConfigGap.EXTRACTION_MODEL)
        }
    }
}
