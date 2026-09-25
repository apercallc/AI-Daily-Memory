package com.apercallc.aidailymemory.capture

import android.util.Log
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.recovery.InterruptionRecoveryPolicy
import com.apercallc.aidailymemory.recording.recovery.RecoveryCondition
import com.apercallc.aidailymemory.recording.recovery.RecoveryDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the CPU running while a retry is pending. A foreground service does not keep the CPU
 * awake once the microphone is closed, so without this a retry scheduled for "in 4 seconds"
 * could fire whenever the screen next turns on.
 */
interface KeepAwake {
    suspend fun <T> whileWaiting(maxMs: Long, block: suspend () -> T): T

    object None : KeepAwake {
        override suspend fun <T> whileWaiting(maxMs: Long, block: suspend () -> T): T = block()
    }
}

/**
 * Carries out [InterruptionRecoveryPolicy] while the recorder service runs. Each new state
 * cancels the previous wait (collectLatest), so a pause, stop, or manual resume always wins
 * over a pending automatic retry.
 */
class RecoveryScheduler(
    private val controller: RecordingController,
    private val policy: InterruptionRecoveryPolicy,
    private val conditionHolds: (RecoveryCondition) -> Boolean,
    private val onNeedsUser: (RecordingState) -> Unit,
    private val pollMs: Long = 2_000,
    /**
     * How long RECORDING must hold before the retry budget resets. A mic that "recovers" and
     * fails again seconds later is the same interruption, not a new one; resetting on the
     * first buffer would retry forever and flash "Recording" every few seconds.
     */
    private val stableMs: Long = 60_000,
    private val keepAwake: KeepAwake = KeepAwake.None,
) {
    private var attempts = 0
    private var attemptsSession: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        // Only interruptions observed by this process are retried. One restored from the
        // journal (the process died meanwhile) needs the user: reopening the mic without them
        // would be an automatic restart, which ADR-0010 forbids.
        controller.awaitRestored()
        val restoredRevision = controller.state.value.revision
        controller.state.collectLatest { state ->
            if (state.revision <= restoredRevision) return@collectLatest
            try {
                onState(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One failed retry (e.g. a journal write) must not end recovery for the rest
                // of the service's life. Logged; the next state change tries again.
                logFailure(TAG, "recovery_step_failed", e)
            }
        }
    }

    private suspend fun onState(state: RecordingState) {
        val sessionId = state.sessionId
        if (sessionId?.value != attemptsSession) {
            attemptsSession = sessionId?.value
            attempts = 0
        }
        if (state.status == RecordingStatus.RECORDING) {
            // Cancelled by collectLatest if the state changes before it has held long enough.
            delay(stableMs)
            attempts = 0
            return
        }
        if (state.status != RecordingStatus.INTERRUPTED || sessionId == null) return

        when (val decision = policy.decide(state.problem, attempts)) {
            is RecoveryDecision.RetryAfter ->
                keepAwake.whileWaiting(decision.delayMs + WAKE_MARGIN_MS) { delay(decision.delayMs) }
            is RecoveryDecision.WaitFor -> {
                // A phone call keeps the CPU awake anyway, so holding it for the call costs
                // little and resumes promptly when it ends. Storage only comes back through
                // user action, which wakes the phone, so it is polled without a wake lock.
                val wait: suspend () -> Unit = {
                    while (!conditionHolds(decision.condition)) delay(pollMs)
                }
                if (decision.condition == RecoveryCondition.CALL_ENDED) {
                    keepAwake.whileWaiting(MAX_CALL_WAIT_MS) { wait() }
                } else {
                    wait()
                }
            }
            RecoveryDecision.NeedsUser -> {
                Log.i(TAG, "recovery_needs_user")
                onNeedsUser(state)
                return
            }
        }
        attempts++
        controller.dispatch(CaptureSignal.RecoveryStarted(sessionId))
    }

    private companion object {
        const val TAG = "RecoveryScheduler"
        const val WAKE_MARGIN_MS = 5_000L

        /** Upper bound on holding the CPU for one call; the wait itself continues after it. */
        const val MAX_CALL_WAIT_MS = 3 * 60 * 60 * 1000L
    }
}
