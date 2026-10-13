package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingConfigTest {
    private val ready = StageConfig(providerIsUserApi = true, urlValid = true, keyPresent = true)

    @Test
    fun `fully configured has no gaps`() {
        assertEquals(emptyList(), ProcessingConfig.gaps(ready, ready, extractionModelPresent = true))
    }

    @Test
    fun `non user-api providers are a provider gap and hide field gaps`() {
        val off = StageConfig(providerIsUserApi = false, urlValid = false, keyPresent = false)
        assertEquals(
            listOf(ConfigGap.TRANSCRIPTION_PROVIDER, ConfigGap.EXTRACTION_PROVIDER),
            ProcessingConfig.gaps(off, off, extractionModelPresent = false),
        )
    }

    @Test
    fun `each missing field is reported`() {
        val bad = StageConfig(providerIsUserApi = true, urlValid = false, keyPresent = false)
        assertEquals(
            listOf(
                ConfigGap.TRANSCRIPTION_URL,
                ConfigGap.TRANSCRIPTION_KEY,
                ConfigGap.EXTRACTION_URL,
                ConfigGap.EXTRACTION_KEY,
                ConfigGap.EXTRACTION_MODEL,
            ),
            ProcessingConfig.gaps(bad, bad, extractionModelPresent = false),
        )
    }
}
