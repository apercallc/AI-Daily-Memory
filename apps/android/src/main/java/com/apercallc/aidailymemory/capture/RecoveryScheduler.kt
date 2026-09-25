package com.apercallc.aidailymemory.capture

import android.util.Log
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.recovery.InterruptionRecoveryPolicy
import com.apercallc.aidailymemory.recording.recovery.RecoveryCondition
import com.apercallc.aidailymemory.recording.recovery.RecoveryDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
) {
    private var attempts = 0
    private var attemptsSession: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        controller.state.collectLatest { state -> onState(state) }
    }

    private suspend fun onState(state: RecordingState) {
        val sessionId = state.sessionId
        if (sessionId?.value != attemptsSession) {
            attemptsSession = sessionId?.value
            attempts = 0
        }
        if (state.status == RecordingStatus.RECORDING) attempts = 0
        if (state.status != RecordingStatus.INTERRUPTED || sessionId == null) return

        when (val decision = policy.decide(state.problem, attempts)) {
            is RecoveryDecision.RetryAfter -> delay(decision.delayMs)
            is RecoveryDecision.WaitFor -> while (!conditionHolds(decision.condition)) {
                delay(pollMs)
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
    }
}
