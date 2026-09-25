package com.apercallc.aidailymemory.platform

import android.util.Log

/**
 * Logs a failure as a constant event name plus the exception type and the frame that threw.
 * Never the message: platform exception messages carry file paths and segment ids (UUIDv7,
 * which encode recording times). Mirrors the backend's redacted-logging rule.
 */
fun logFailure(tag: String, event: String, error: Throwable, warning: Boolean = false) {
    val frame = error.stackTrace.firstOrNull()
        ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        ?: "unknown"
    val line = "$event type=${error.javaClass.simpleName} at=$frame"
    if (warning) Log.w(tag, line) else Log.e(tag, line)
}
