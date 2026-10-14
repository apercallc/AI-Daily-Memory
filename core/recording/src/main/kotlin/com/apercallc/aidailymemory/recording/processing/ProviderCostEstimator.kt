package com.apercallc.aidailymemory.recording.processing

/** A content-free local record of one successful external processing request. */
data class ProviderUsageSample(
    val sessionId: String,
    val stage: Stage,
    val providerId: String?,
    val model: String,
    /** Duration of audio in this one successful transcription request; zero for summaries. */
    val requestAudioMs: Long,
    /** Session audio duration in milliseconds; used only to normalize cost per audio hour. */
    val sessionAudioMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val recordedAtEpochMs: Long,
) {
    enum class Stage { TRANSCRIPTION, SUMMARY }
}

data class ProviderCostEstimate(
    val requestCount: Int,
    val audioMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    /** Null when any included request has unpriced or unreported usage. */
    val totalUsd: Double?,
    val averageDailyUsd: Double?,
    val usdPerAudioHour: Double?,
    val pricingUnknown: Boolean,
    val pricingAsOf: String?,
    val pricingSourceUrls: List<String>,
)

/**
 * A transparent estimate from successful provider responses during a rolling seven-day window.
 * Rates are list-price references, not a statement of the user's account bill or negotiated tier.
 */
object ProviderCostEstimator {
    private const val TOKENS_PER_MILLION = 1_000_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MILLIS_PER_HOUR = 3_600_000.0
    private const val WINDOW_DAYS = 7
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1_000L
    private const val PRICING_AS_OF = "2026-10-14"

    private data class Rate(
        val providerId: String,
        val model: String,
        val audioUsdPerMinute: Double? = null,
        val inputUsdPerMillionTokens: Double? = null,
        val outputUsdPerMillionTokens: Double? = null,
        val sourceUrl: String,
    )

    // Deepgram pre-recorded pay-as-you-go Nova-3 monolingual rate.
    // https://deepgram.com/pricing
    // OpenAI standard text-token list rates for short-context prompts.
    // https://developers.openai.com/api/docs/models/gpt-4o-mini
    // https://developers.openai.com/api/docs/models/gpt-4o
    // https://developers.openai.com/api/docs/models/gpt-6-luna
    // https://developers.openai.com/api/docs/models/gpt-6.1-sol
    private val rates = listOf(
        Rate("deepgram", "nova-3", audioUsdPerMinute = 0.0043, sourceUrl = "https://deepgram.com/pricing"),
        Rate(
            "openai", "gpt-4o-mini", inputUsdPerMillionTokens = 0.15, outputUsdPerMillionTokens = 0.60,
            sourceUrl = "https://developers.openai.com/api/docs/models/gpt-4o-mini",
        ),
        Rate(
            "openai", "gpt-4o", inputUsdPerMillionTokens = 2.50, outputUsdPerMillionTokens = 10.0,
            sourceUrl = "https://developers.openai.com/api/docs/models/gpt-4o",
        ),
        Rate(
            "openai", "gpt-6-luna", inputUsdPerMillionTokens = 0.10, outputUsdPerMillionTokens = 0.50,
            sourceUrl = "https://developers.openai.com/api/docs/models/gpt-6-luna",
        ),
        Rate(
            "openai", "gpt-6.1-sol", inputUsdPerMillionTokens = 2.0, outputUsdPerMillionTokens = 10.0,
            sourceUrl = "https://developers.openai.com/api/docs/models/gpt-6.1-sol",
        ),
    )

    fun estimate(samples: List<ProviderUsageSample>, nowEpochMs: Long): ProviderCostEstimate {
        val fromEpochMs = nowEpochMs - WINDOW_MS
        val recent = samples.filter { it.recordedAtEpochMs in fromEpochMs..nowEpochMs }
        val distinctSessionAudioMs = recent.groupBy { it.sessionId }
            .values
            .sumOf { session -> session.maxOfOrNull { it.sessionAudioMs.coerceAtLeast(0) } ?: 0L }
        val audioMs = recent
            .filter { it.stage == ProviderUsageSample.Stage.TRANSCRIPTION }
            .sumOf { it.requestAudioMs.coerceAtLeast(0) }
        val summaries = recent.filter { it.stage == ProviderUsageSample.Stage.SUMMARY }
        val inputTokens = summaries.takeIf { rows -> rows.all { it.inputTokens != null } }
            ?.sumOf { it.inputTokens?.coerceAtLeast(0) ?: 0L }
        val outputTokens = summaries.takeIf { rows -> rows.all { it.outputTokens != null } }
            ?.sumOf { it.outputTokens?.coerceAtLeast(0) ?: 0L }

        if (recent.isEmpty()) {
            return ProviderCostEstimate(0, 0, null, null, null, null, null, false, null, emptyList())
        }

        var totalUsd = 0.0
        var unknown = false
        val usedRates = mutableSetOf<Rate>()
        for (sample in recent) {
            val rate = rates.firstOrNull {
                it.providerId == sample.providerId && it.model.equals(sample.model, ignoreCase = true)
            }
            if (rate == null) {
                unknown = true
                continue
            }
            usedRates += rate
            when (sample.stage) {
                ProviderUsageSample.Stage.TRANSCRIPTION -> {
                    val audioRate = rate.audioUsdPerMinute
                    if (audioRate == null || sample.requestAudioMs <= 0) {
                        unknown = true
                    } else {
                        totalUsd += sample.requestAudioMs / MILLIS_PER_MINUTE * audioRate
                    }
                }
                ProviderUsageSample.Stage.SUMMARY -> {
                    val inputRate = rate.inputUsdPerMillionTokens
                    val outputRate = rate.outputUsdPerMillionTokens
                    val input = sample.inputTokens
                    val output = sample.outputTokens
                    if (inputRate == null || outputRate == null || input == null || output == null) {
                        unknown = true
                    } else {
                        totalUsd += input / TOKENS_PER_MILLION * inputRate
                        totalUsd += output / TOKENS_PER_MILLION * outputRate
                    }
                }
            }
        }

        val exactTotal = totalUsd.takeUnless { unknown }
        val audioHours = distinctSessionAudioMs / MILLIS_PER_HOUR
        return ProviderCostEstimate(
            requestCount = recent.size,
            audioMs = audioMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalUsd = exactTotal,
            averageDailyUsd = exactTotal?.div(WINDOW_DAYS),
            usdPerAudioHour = exactTotal?.takeIf { audioHours > 0.0 }?.div(audioHours),
            pricingUnknown = unknown,
            pricingAsOf = usedRates.takeIf { it.isNotEmpty() }?.let { PRICING_AS_OF },
            pricingSourceUrls = usedRates.map { it.sourceUrl }.distinct(),
        )
    }
}
