package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingConfigTest {
    private val ready = StageConfig(providerIsUserApi = true, urlValid = true, keyPresent = true)
    private val phoneOnly = StageConfig(providerIsUserApi = false, urlValid = false, keyPresent = false)

    @Test
    fun `fully configured with own keys has no gaps`() {
        assertEquals(
            emptyList(),
            ProcessingConfig.gaps(ready, ready, extractionModelPresent = true, onDeviceModelReady = false),
        )
    }

    @Test
    fun `phone transcription with summaries off is complete once the model is downloaded`() {
        assertEquals(
            emptyList(),
            ProcessingConfig.gaps(phoneOnly, phoneOnly, extractionModelPresent = false, onDeviceModelReady = true),
        )
    }

    @Test
    fun `phone transcription without its model is a model gap, and summaries off adds nothing`() {
        assertEquals(
            listOf(ConfigGap.TRANSCRIPTION_MODEL),
            ProcessingConfig.gaps(phoneOnly, phoneOnly, extractionModelPresent = false, onDeviceModelReady = false),
        )
    }

    @Test
    fun `a downloaded model is not required when transcription uses the person's key`() {
        assertEquals(
            emptyList(),
            ProcessingConfig.gaps(ready, phoneOnly, extractionModelPresent = false, onDeviceModelReady = false),
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
            ProcessingConfig.gaps(bad, bad, extractionModelPresent = false, onDeviceModelReady = false),
        )
    }
}
