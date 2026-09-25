package com.apercallc.aidailymemory.recording.audio

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigitalSilenceDetectorTest {
    // 1 kHz and 10 ms threshold keep buffers small: the threshold is 10 samples.
    private val detector = DigitalSilenceDetector(sampleRateHz = 1_000, thresholdMs = 10)

    private fun zeros(n: Int) = ShortArray(n)

    @Test
    fun `quiet but non-zero audio is never silent`() {
        val noiseFloor = ShortArray(1_000) { if (it % 2 == 0) 1 else -1 }

        assertFalse(detector.process(noiseFloor))
    }

    @Test
    fun `a run of zeros spanning several buffers trips the detector`() {
        assertFalse(detector.process(zeros(4)))
        assertFalse(detector.process(zeros(5)))
        assertTrue(detector.process(zeros(1)))
        assertTrue(detector.isSilent)
    }

    @Test
    fun `any real sample resets the run, counting only trailing zeros`() {
        detector.process(zeros(9))
        val buffer = shortArrayOf(0, 0, 7, 0, 0, 0)

        assertFalse(detector.process(buffer))
        assertFalse(detector.process(zeros(6)))
        assertTrue(detector.process(zeros(1)))
    }

    @Test
    fun `only the first count samples are considered`() {
        val buffer = ShortArray(20)
        buffer[15] = 99

        assertTrue(detector.process(buffer, count = 12))
    }

    @Test
    fun `reset clears the run and empty buffers change nothing`() {
        detector.process(zeros(20))
        detector.reset()

        assertFalse(detector.isSilent)
        assertFalse(detector.process(zeros(0)))
    }

    @Test
    fun `invalid configuration and counts are rejected`() {
        assertFailsWith<IllegalArgumentException> { DigitalSilenceDetector(0, 10) }
        assertFailsWith<IllegalArgumentException> { DigitalSilenceDetector(1_000, 0) }
        assertFailsWith<IllegalArgumentException> { detector.process(zeros(2), count = 3) }
        assertFailsWith<IllegalArgumentException> { detector.process(zeros(2), count = -1) }
    }
}
