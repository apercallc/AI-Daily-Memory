package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ProviderFailure
import com.apercallc.aidailymemory.recording.processing.ProviderStatus
import java.io.IOException
import java.net.HttpURLConnection

/** Reads only a small error prefix and returns a machine classification; provider text is never logged. */
internal fun HttpURLConnection.providerFailureFor(status: Int): ProviderFailure? {
    val errorCode = if (status == PAYMENT_REQUIRED || status == TOO_MANY_REQUESTS) {
        readProviderErrorCode()
    } else {
        null
    }
    return ProviderStatus.failureFor(status, errorCode)
}

private fun HttpURLConnection.readProviderErrorCode(): String? {
    val stream = errorStream ?: return null
    return try {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(MAX_ERROR_BODY_CHARS)
            val count = reader.read(buffer)
            if (count <= 0) null else ProviderStatus.errorCodeFromBody(String(buffer, 0, count))
        }
    } catch (_: IOException) {
        // Fall back to the HTTP status when the optional machine-readable body is unavailable.
        null
    }
}

private const val PAYMENT_REQUIRED = 402
private const val TOO_MANY_REQUESTS = 429
private const val MAX_ERROR_BODY_CHARS = 8 * 1024
