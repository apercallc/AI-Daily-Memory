package com.apercallc.aidailymemory.recording.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoragePolicyTest {
    private val policy = StoragePolicy(
        warningFreeBytes = 1_000,
        hardStopFreeBytes = 100,
        maxPendingBytes = 500,
        estimatedBytesPerHour = 60,
    )

    @Test
    fun `levels follow thresholds with inclusive boundaries`() {
        assertEquals(StorageLevel.OK, policy.evaluate(1_001, 0).level)
        assertEquals(StorageLevel.LOW, policy.evaluate(1_000, 0).level)
        assertEquals(StorageLevel.LOW, policy.evaluate(101, 0).level)
        assertEquals(StorageLevel.CRITICAL, policy.evaluate(100, 0).level)
        assertEquals(StorageLevel.CRITICAL, policy.evaluate(0, 0).level)
    }

    @Test
    fun `only critical storage forces capture to stop`() {
        assertTrue(policy.evaluate(100, 0).mustStopCapture)
        assertFalse(policy.evaluate(500, 0).mustStopCapture)
    }

    @Test
    fun `recording time left is measured to the hard stop, never negative`() {
        assertEquals(900, policy.evaluate(1_000, 0).recordingMinutesLeft)
        assertEquals(0, policy.evaluate(50, 0).recordingMinutesLeft)
    }

    @Test
    fun `unknown or nonsensical free space is never reported as OK`() {
        val unknown = policy.evaluate(null, 0)
        assertEquals(StorageLevel.UNKNOWN, unknown.level)
        assertNull(unknown.recordingMinutesLeft)
        assertEquals(StorageLevel.UNKNOWN, policy.evaluate(-5, 0).level)
    }

    @Test
    fun `pending upload over budget warns even with plenty of free space`() {
        val health = policy.evaluate(1_000_000, 501)

        assertEquals(StorageLevel.LOW, health.level)
        assertTrue(health.pendingOverBudget)
        assertTrue(policy.evaluate(null, 501).pendingOverBudget)
        assertFalse(policy.evaluate(1_000_000, 500).pendingOverBudget)
    }

    @Test
    fun `invalid inputs and policies are rejected`() {
        assertFailsWith<IllegalArgumentException> { policy.evaluate(10, -1) }
        assertFailsWith<IllegalArgumentException> { policy.copy(hardStopFreeBytes = 0) }
        assertFailsWith<IllegalArgumentException> { policy.copy(warningFreeBytes = 100) }
        assertFailsWith<IllegalArgumentException> { policy.copy(maxPendingBytes = 0) }
        assertFailsWith<IllegalArgumentException> { policy.copy(estimatedBytesPerHour = 0) }
    }

    @Test
    fun `provisional policy is internally consistent`() {
        val provisional = StoragePolicy.Provisional

        assertTrue(provisional.warningFreeBytes > provisional.hardStopFreeBytes)
        assertEquals(StorageLevel.OK, provisional.evaluate(Long.MAX_VALUE / 120, 0).level)
    }
}
