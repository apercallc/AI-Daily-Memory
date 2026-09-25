package com.apercallc.aidailymemory.recording.recovery

import com.apercallc.aidailymemory.recording.CaptureProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RestartClassifierTest {
    @Test
    fun `same boot with the clock moving forward is a process restart`() {
        assertEquals(
            RestartCause.PROCESS_RESTARTED,
            RestartClassifier.classify(BootStamp("7", 1_000), BootStamp("7", 5_000)),
        )
    }

    @Test
    fun `a different boot id is a reboot even if the clock moved forward`() {
        assertEquals(
            RestartCause.DEVICE_REBOOTED,
            RestartClassifier.classify(BootStamp("7", 1_000), BootStamp("8", 5_000_000)),
        )
    }

    @Test
    fun `a monotonic clock that went backwards proves a reboot`() {
        assertEquals(
            RestartCause.DEVICE_REBOOTED,
            RestartClassifier.classify(BootStamp("7", 9_000), BootStamp("7", 100)),
        )
    }

    @Test
    fun `unknown boot ids fall back to the monotonic clock`() {
        assertEquals(
            RestartCause.PROCESS_RESTARTED,
            RestartClassifier.classify(BootStamp(null, 1_000), BootStamp("8", 2_000)),
        )
        assertEquals(
            RestartCause.PROCESS_RESTARTED,
            RestartClassifier.classify(BootStamp("7", 1_000), BootStamp(null, 2_000)),
        )
        assertEquals(
            RestartCause.DEVICE_REBOOTED,
            RestartClassifier.classify(BootStamp(null, 9_000), BootStamp(null, 2_000)),
        )
    }

    @Test
    fun `boot stamps reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { BootStamp("x", -1) }
        assertFailsWith<IllegalArgumentException> { BootStamp(" ", 0) }
    }
}

class InterruptionRecoveryPolicyTest {
    private val policy = InterruptionRecoveryPolicy(
        initialDelayMs = 1_000,
        maxDelayMs = 10_000,
        maxAttempts = 5,
    )

    @Test
    fun `transient microphone loss retries with capped exponential backoff then needs the user`() {
        val decisions = (0..5).map { policy.decide(CaptureProblem.MICROPHONE_UNAVAILABLE, it) }

        assertEquals(
            listOf(
                RecoveryDecision.RetryAfter(1_000),
                RecoveryDecision.RetryAfter(2_000),
                RecoveryDecision.RetryAfter(4_000),
                RecoveryDecision.RetryAfter(8_000),
                RecoveryDecision.RetryAfter(10_000),
                RecoveryDecision.NeedsUser,
            ),
            decisions,
        )
    }

    @Test
    fun `unknown causes use the same bounded retries`() {
        assertEquals(RecoveryDecision.RetryAfter(1_000), policy.decide(CaptureProblem.UNKNOWN, 0))
        assertEquals(RecoveryDecision.RetryAfter(1_000), policy.decide(null, 0))
        assertEquals(RecoveryDecision.NeedsUser, policy.decide(null, 5))
    }

    @Test
    fun `calls and storage wait for their condition instead of polling`() {
        assertEquals(
            RecoveryDecision.WaitFor(RecoveryCondition.CALL_ENDED),
            policy.decide(CaptureProblem.PHONE_CALL, 99),
        )
        assertEquals(
            RecoveryDecision.WaitFor(RecoveryCondition.STORAGE_AVAILABLE),
            policy.decide(CaptureProblem.STORAGE_LOW, 0),
        )
    }

    @Test
    fun `problems automation cannot fix always need the user`() {
        listOf(
            CaptureProblem.PERMISSION_DENIED,
            CaptureProblem.FOREGROUND_START_NOT_ALLOWED,
            CaptureProblem.ENCRYPTION_UNAVAILABLE,
            CaptureProblem.INDICATOR_HIDDEN,
            CaptureProblem.PROCESS_RESTARTED,
            CaptureProblem.DEVICE_REBOOTED,
        ).forEach { problem ->
            assertEquals(RecoveryDecision.NeedsUser, policy.decide(problem, 0), "$problem")
        }
    }

    @Test
    fun `huge attempt counts never overflow the delay`() {
        val generous = InterruptionRecoveryPolicy(
            initialDelayMs = 3,
            maxDelayMs = Long.MAX_VALUE,
            maxAttempts = Int.MAX_VALUE,
        )

        assertEquals(RecoveryDecision.RetryAfter(Long.MAX_VALUE), generous.decide(null, 1_000))
        assertEquals(RecoveryDecision.RetryAfter(3L shl 40), generous.decide(null, 40))
    }

    @Test
    fun `default policy is bounded`() {
        val default = InterruptionRecoveryPolicy.Default
        assertEquals(
            RecoveryDecision.NeedsUser,
            default.decide(CaptureProblem.MICROPHONE_UNAVAILABLE, default.maxAttempts),
        )
    }

    @Test
    fun `invalid configuration and inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { InterruptionRecoveryPolicy(0, 1, 1) }
        assertFailsWith<IllegalArgumentException> { InterruptionRecoveryPolicy(2, 1, 1) }
        assertFailsWith<IllegalArgumentException> { InterruptionRecoveryPolicy(1, 1, 0) }
        assertFailsWith<IllegalArgumentException> { policy.decide(null, -1) }
    }
}
