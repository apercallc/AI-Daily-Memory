package com.apercallc.aidailymemory.recording.recovery

import com.apercallc.aidailymemory.recording.CaptureProblem

/** A condition the engine waits for before it tries to reopen the microphone by itself. */
enum class RecoveryCondition {
    /** The phone call that took the microphone has ended. */
    CALL_ENDED,

    /** Free space is back above the hard-stop threshold. */
    STORAGE_AVAILABLE,
}

sealed interface RecoveryDecision {
    /** Try to reopen the microphone after [delayMs]. */
    data class RetryAfter(val delayMs: Long) : RecoveryDecision

    /** Do nothing until [condition] holds, then retry immediately. */
    data class WaitFor(val condition: RecoveryCondition) : RecoveryDecision

    /**
     * Automatic recovery cannot help (permission removed, background start refused, retries
     * exhausted, restart). The session stays INTERRUPTED and visible until the user acts.
     */
    data object NeedsUser : RecoveryDecision
}

/**
 * Bounded automatic recovery for an interrupted session (TODO.md "All background work has
 * bounded retry behavior"). Pure: the service owns timers and callbacks.
 *
 * Retries back off exponentially from [initialDelayMs] up to [maxDelayMs] and stop after
 * [maxAttempts]; after that the problem is the user's to see, not the engine's to hide.
 */
data class InterruptionRecoveryPolicy(
    val initialDelayMs: Long,
    val maxDelayMs: Long,
    val maxAttempts: Int,
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be at least initialDelayMs" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * @param attempt number of automatic recovery attempts already made for this interruption
     * (0 for the first decision).
     */
    fun decide(problem: CaptureProblem?, attempt: Int): RecoveryDecision {
        require(attempt >= 0) { "attempt must not be negative" }
        return when (problem) {
            CaptureProblem.PHONE_CALL -> RecoveryDecision.WaitFor(RecoveryCondition.CALL_ENDED)
            CaptureProblem.STORAGE_LOW ->
                RecoveryDecision.WaitFor(RecoveryCondition.STORAGE_AVAILABLE)
            CaptureProblem.MICROPHONE_UNAVAILABLE, CaptureProblem.UNKNOWN, null ->
                if (attempt < maxAttempts) {
                    RecoveryDecision.RetryAfter(delayFor(attempt))
                } else {
                    RecoveryDecision.NeedsUser
                }
            // Reopening would fail again or silently record nothing: the user has to act.
            CaptureProblem.PERMISSION_DENIED,
            CaptureProblem.FOREGROUND_START_NOT_ALLOWED,
            CaptureProblem.ENCRYPTION_UNAVAILABLE,
            CaptureProblem.INDICATOR_HIDDEN,
            CaptureProblem.PROCESS_RESTARTED,
            CaptureProblem.DEVICE_REBOOTED,
            -> RecoveryDecision.NeedsUser
        }
    }

    private fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceAtMost(MAX_SHIFT)
        // Compare before shifting so a large attempt count can never overflow.
        return if (initialDelayMs > maxDelayMs shr shift) {
            maxDelayMs
        } else {
            initialDelayMs shl shift
        }
    }

    companion object {
        private const val MAX_SHIFT = 62

        /**
         * Provisional: 2 s, 4 s, … capped at 60 s, forty tries (about 35 minutes). Long enough to
         * outlast a video recording or voice chat in another app that holds the microphone.
         */
        val Default = InterruptionRecoveryPolicy(
            initialDelayMs = 2_000,
            maxDelayMs = 60_000,
            maxAttempts = 40,
        )
    }
}
