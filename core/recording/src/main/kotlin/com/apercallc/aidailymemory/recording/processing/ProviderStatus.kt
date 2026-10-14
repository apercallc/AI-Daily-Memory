package com.apercallc.aidailymemory.recording.processing

import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a provider's HTTP answer decides what happens next. One place, so transcription and
 * summary agree: a bad key is named and never retried, an overloaded or rate-limited service
 * is retried later, and only a request that can never succeed is given up on.
 */
data class ProviderFailure(val code: String, val retryable: Boolean)

object ProviderStatus {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_EARLY = 425
    private const val TOO_MANY_REQUESTS = 429
    private const val PAYMENT_REQUIRED = 402
    private const val SERVER_ERROR = 500
    private const val SERVER_ERROR_LAST = 599
    private const val CLIENT_ERROR = 400
    private const val CLIENT_ERROR_LAST = 499

    private const val MAX_ERROR_BODY_CHARS = 8 * 1024
    private val QUOTA_ERROR_CODES = setOf(
        "asr_payment_required",
        "billing_hard_limit_reached",
        "credit_balance_exhausted",
        "insufficient_credits",
        "insufficient_quota",
        "organization_spend_limit_exceeded",
        "organization_usage_limit_exceeded",
        "project_spend_limit_exceeded",
        "quota_exceeded",
        "quota_exhausted",
        "resource_exhausted",
    )

    /** Null for 200: nothing went wrong. */
    fun failureFor(status: Int, providerErrorCode: String? = null): ProviderFailure? {
        val normalizedCode = providerErrorCode?.trim()?.lowercase(Locale.ROOT)
        if (status == PAYMENT_REQUIRED ||
            (status == TOO_MANY_REQUESTS && normalizedCode in QUOTA_ERROR_CODES)
        ) {
            return ProviderFailure("provider_quota_exhausted", retryable = false)
        }

        return when (status) {
        200 -> null
        UNAUTHORIZED, FORBIDDEN -> ProviderFailure("provider_auth_failed", retryable = false)
        TOO_MANY_REQUESTS -> ProviderFailure("provider_rate_limited", retryable = true)
        REQUEST_TIMEOUT, TOO_EARLY -> ProviderFailure("provider_busy", retryable = true)
        in SERVER_ERROR..SERVER_ERROR_LAST -> ProviderFailure("provider_server_error", retryable = true)
        in CLIENT_ERROR..CLIENT_ERROR_LAST -> ProviderFailure("provider_client_error", retryable = false)
        else -> ProviderFailure("provider_unexpected", retryable = false)
        }
    }

    /** Extract only the provider's machine-readable error code; never retain or expose its message. */
    fun errorCodeFromBody(responseBody: String): String? {
        val body = responseBody.take(MAX_ERROR_BODY_CHARS)
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (_: SerializationException) {
            return null
        }

        val error = root["error"] as? JsonObject
        val candidates = sequenceOf(
            root["err_code"],
            error?.get("code"),
            error?.get("type"),
            error?.get("status"),
        )
        return candidates
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() && it.length <= 64 }
    }
}
