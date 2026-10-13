package com.apercallc.aidailymemory.recording.processing

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
    private const val SERVER_ERROR = 500
    private const val SERVER_ERROR_LAST = 599
    private const val CLIENT_ERROR = 400
    private const val CLIENT_ERROR_LAST = 499

    /** Null for 200: nothing went wrong. */
    fun failureFor(status: Int): ProviderFailure? = when (status) {
        200 -> null
        UNAUTHORIZED, FORBIDDEN -> ProviderFailure("provider_auth_failed", retryable = false)
        TOO_MANY_REQUESTS -> ProviderFailure("provider_rate_limited", retryable = true)
        REQUEST_TIMEOUT, TOO_EARLY -> ProviderFailure("provider_busy", retryable = true)
        in SERVER_ERROR..SERVER_ERROR_LAST -> ProviderFailure("provider_server_error", retryable = true)
        in CLIENT_ERROR..CLIENT_ERROR_LAST -> ProviderFailure("provider_client_error", retryable = false)
        else -> ProviderFailure("provider_unexpected", retryable = false)
    }
}
