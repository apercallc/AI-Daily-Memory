package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCostEstimatorTest {
    @Test
    fun `known audio and token rates produce seven day and per audio hour estimates`() {
        val now = 1_800_000_000_000L
        val samples = listOf(
            ProviderUsageSample(
                sessionId = "session-a",
                stage = ProviderUsageSample.Stage.TRANSCRIPTION,
                providerId = "deepgram",
                model = "nova-3",
                requestAudioMs = 3_600_000,
                sessionAudioMs = 3_600_000,
                inputTokens = null,
                outputTokens = null,
                recordedAtEpochMs = now,
            ),
            ProviderUsageSample(
                sessionId = "session-a",
                stage = ProviderUsageSample.Stage.SUMMARY,
                providerId = "openai",
                model = "gpt-4o-mini",
                requestAudioMs = 0,
                sessionAudioMs = 3_600_000,
                inputTokens = 1_000_000,
                outputTokens = 1_000_000,
                recordedAtEpochMs = now,
            ),
        )

        val estimate = ProviderCostEstimator.estimate(samples, now)

        assertEquals(2, estimate.requestCount)
        assertEquals(3_600_000, estimate.audioMs)
        assertEquals(1_000_000, estimate.inputTokens)
        assertEquals(1_000_000, estimate.outputTokens)
        assertEquals(1.008, estimate.totalUsd!!, absoluteTolerance = 0.000001)
        assertEquals(1.008, estimate.usdPerAudioHour!!, absoluteTolerance = 0.000001)
        assertEquals(1.008 / 7, estimate.averageDailyUsd!!, absoluteTolerance = 0.000001)
        assertFalse(estimate.pricingUnknown)
        assertEquals("2026-10-14", estimate.pricingAsOf)
        assertEquals(2, estimate.pricingSourceUrls.size)
    }

    @Test
    fun `unknown provider pricing never becomes a fabricated zero cost`() {
        val now = 1_800_000_000_000L
        val estimate = ProviderCostEstimator.estimate(
            listOf(
                ProviderUsageSample(
                    sessionId = "session-custom",
                    stage = ProviderUsageSample.Stage.SUMMARY,
                    providerId = null,
                    model = "private-model",
                    requestAudioMs = 0,
                    sessionAudioMs = 900_000,
                    inputTokens = 1_000,
                    outputTokens = 200,
                    recordedAtEpochMs = now,
                ),
            ),
            now,
        )

        assertTrue(estimate.pricingUnknown)
        assertNull(estimate.totalUsd)
        assertNull(estimate.averageDailyUsd)
        assertNull(estimate.usdPerAudioHour)
        assertEquals(1_000, estimate.inputTokens)
        assertEquals(200, estimate.outputTokens)
    }

    @Test
    fun `summary usage without provider token counts is unknown`() {
        val now = 1_800_000_000_000L
        val estimate = ProviderCostEstimator.estimate(
            listOf(
                ProviderUsageSample(
                    sessionId = "session-a",
                    stage = ProviderUsageSample.Stage.SUMMARY,
                    providerId = "openai",
                    model = "gpt-4o-mini",
                    requestAudioMs = 0,
                    sessionAudioMs = 1_000,
                    inputTokens = null,
                    outputTokens = null,
                    recordedAtEpochMs = now,
                ),
            ),
            now,
        )

        assertTrue(estimate.pricingUnknown)
        assertNull(estimate.totalUsd)
    }

    @Test
    fun `current OpenAI model uses its current dated list rate`() {
        val now = 1_800_000_000_000L
        val sample = ProviderUsageSample(
            sessionId = "session-new-model",
            stage = ProviderUsageSample.Stage.SUMMARY,
            providerId = "openai",
            model = "gpt-6-luna",
            requestAudioMs = 0,
            sessionAudioMs = 3_600_000,
            inputTokens = 1_000_000,
            outputTokens = 1_000_000,
            recordedAtEpochMs = now,
        )

        val estimate = ProviderCostEstimator.estimate(listOf(sample), now)

        assertEquals(0.60, estimate.totalUsd!!, absoluteTolerance = 0.000001)
        assertEquals("2026-10-14", estimate.pricingAsOf)
    }

    @Test
    fun `samples older than the rolling week are excluded`() {
        val now = 1_800_000_000_000L
        val old = ProviderUsageSample(
            sessionId = "old",
            stage = ProviderUsageSample.Stage.TRANSCRIPTION,
            providerId = "deepgram",
            model = "nova-3",
            requestAudioMs = 3_600_000,
            sessionAudioMs = 3_600_000,
            inputTokens = null,
            outputTokens = null,
            recordedAtEpochMs = now - 8L * 24 * 60 * 60 * 1_000,
        )

        val estimate = ProviderCostEstimator.estimate(listOf(old), now)

        assertEquals(0, estimate.requestCount)
        assertNull(estimate.totalUsd)
        assertFalse(estimate.pricingUnknown)
    }
}
