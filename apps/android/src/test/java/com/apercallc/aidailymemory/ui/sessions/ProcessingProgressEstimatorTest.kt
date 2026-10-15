package com.apercallc.aidailymemory.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessingProgressEstimatorTest {
    @Test
    fun `reports percent and estimates remaining time from completed segments`() {
        val estimate = ProcessingProgressEstimator.estimate(
            completedSegments = 2,
            totalSegments = 10,
            processingTimeMs = 1_000_000L,
        )

        assertEquals(20, estimate?.percentComplete)
        assertEquals(4_000_000L, estimate?.remainingMs)
    }

    @Test
    fun `does not invent an eta before any segment completes`() {
        val estimate = ProcessingProgressEstimator.estimate(
            completedSegments = 0,
            totalSegments = 4,
            processingTimeMs = 0L,
        )

        assertEquals(0, estimate?.percentComplete)
        assertNull(estimate?.remainingMs)
    }

    @Test
    fun `does not estimate when there is no segment workload`() {
        assertNull(
            ProcessingProgressEstimator.estimate(
                completedSegments = 0,
                totalSegments = 0,
                processingTimeMs = 0L,
            ),
        )
    }
}
