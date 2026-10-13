package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderPresetsTest {
    private val deepgram = ProviderPresets.transcription.single()

    @Test
    fun `every preset address and key link is a valid https url`() {
        (ProviderPresets.transcription + ProviderPresets.summary).forEach { preset ->
            assertTrue(EndpointUrl.parse(preset.endpoint) is EndpointUrl.Valid, preset.id)
            assertTrue(EndpointUrl.parse(preset.keyUrl) is EndpointUrl.Valid, preset.id)
            assertTrue(preset.models.isNotEmpty(), preset.id)
            assertEquals(preset.models.first(), preset.defaultModel)
        }
    }

    @Test
    fun `preset ids are unique and never the custom id`() {
        val ids = (ProviderPresets.transcription + ProviderPresets.summary).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ProviderPresets.CUSTOM_ID !in ids)
    }

    @Test
    fun `a transcription model rides in the address and still validates`() {
        val url = deepgram.endpointFor("nova-2")
        assertTrue(url.contains("model=nova-2"))
        assertTrue(EndpointUrl.parse(url) is EndpointUrl.Valid)
        assertEquals("nova-2", ProviderPresets.modelInUrl(url))
    }

    @Test
    fun `a typed model cannot add parameters to the address`() {
        val url = deepgram.endpointFor("x&callback=https://evil.example")
        assertTrue(!url.contains("callback="))
        assertTrue(url.contains("model=x%26callback%3Dhttps%3A%2F%2Fevil.example"))
    }

    @Test
    fun `a summary preset keeps its address whatever the model`() {
        val openai = ProviderPresets.summary.first { it.id == "openai" }
        assertEquals(openai.endpoint, openai.endpointFor("gpt-4o"))
    }

    @Test
    fun `a stored address finds its preset, with or without a query`() {
        assertEquals(deepgram, ProviderPresets.match(ProviderPresets.transcription, deepgram.endpointFor("nova-3")))
        assertEquals(deepgram, ProviderPresets.match(ProviderPresets.transcription, deepgram.endpoint.uppercase()))
    }

    @Test
    fun `an unknown, blank or missing address is custom`() {
        assertNull(ProviderPresets.match(ProviderPresets.summary, "https://llm.example/v1"))
        assertNull(ProviderPresets.match(ProviderPresets.summary, " "))
        assertNull(ProviderPresets.match(ProviderPresets.summary, null))
    }

    @Test
    fun `no model in the address reads as none`() {
        assertNull(ProviderPresets.modelInUrl(null))
        assertNull(ProviderPresets.modelInUrl(deepgram.endpoint))
        assertNull(ProviderPresets.modelInUrl("${deepgram.endpoint}?model=&x=1"))
        assertEquals("a", ProviderPresets.modelInUrl("${deepgram.endpoint}?x=1&model=a"))
    }
}
