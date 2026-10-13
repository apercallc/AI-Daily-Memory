package com.apercallc.aidailymemory.service

import android.content.BroadcastReceiver
import com.apercallc.aidailymemory.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A receiver must finish well inside the platform's limit (about ten seconds). */
internal const val BROADCAST_BUDGET_MS = 8_000L

/**
 * Runs [block] and reports whether it finished within [timeoutMs]. A timeout is not an error:
 * the work keeps its own scope, the receiver just stops holding the process open for it.
 */
internal suspend fun awaitWithin(timeoutMs: Long, block: suspend () -> Unit): Boolean =
    withTimeoutOrNull(timeoutMs) {
        block()
        true
    } ?: false

/**
 * Keeps the process alive for [block] with `goAsync()`, always releasing the broadcast when
 * the work is done or the budget runs out, so a Pause or Stop tap is not lost to a reclaimed
 * process and a stuck task can never hold the receiver open.
 */
internal fun BroadcastReceiver.finishAfter(
    container: AppContainer,
    scope: CoroutineScope = container.appScope,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    scope.launch {
        try {
            awaitWithin(BROADCAST_BUDGET_MS, block)
        } finally {
            pending.finish()
        }
    }
}
