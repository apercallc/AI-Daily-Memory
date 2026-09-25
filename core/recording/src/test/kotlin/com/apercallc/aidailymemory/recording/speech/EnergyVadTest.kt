package com.apercallc.aidailymemory.recording.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnergyVadTest {
    private val rate = 16_000
    private val frame = rate * EnergyVad.DEFAULT_FRAME_MS / 1000 // 480 samples

    /** Alternating +/- amplitude: a flat, noise-floor-like signal of known RMS. */
    private fun hiss(ms: Int, amplitude: Int = 30) =
        ShortArray(rate * ms / 1000) { (if (it % 2 == 0) amplitude else -amplitude).toShort() }

    private fun tone(ms: Int, amplitude: Double = 3_000.0) =
        ShortArray(rate * ms / 1000) { (amplitude * sin(2 * PI * 220 * it / rate)).toInt().toShort() }

    private fun decide(vad: EnergyVad, vararg parts: ShortArray): List<Boolean> {
        val out = mutableListOf<Boolean>()
        parts.forEach { vad.process(it) { speech -> out += speech } }
        return out
    }

    @Test
    fun `a voice-level burst over a quiet room is speech and the room is not`() {
        val frames = decide(EnergyVad(rate), hiss(1_000), tone(900), hiss(1_100))

        assertEquals(100, frames.size)
        assertTrue(frames.take(33).none { it }, "quiet room")
        assertTrue(frames.subList(34, 63).all { it }, "voice")
        assertTrue(frames.takeLast(34).none { it }, "quiet again")
    }

    @Test
    fun `the noise floor settles on a steady room within a couple of seconds`() {
        val vad = EnergyVad(rate, initialNoiseDb = -20.0)
        decide(vad, hiss(2_000))

        // hiss(30) is about -60.8 dBFS.
        assertTrue(vad.noiseFloorDb < -58.0, "floor ${vad.noiseFloorDb}")
    }

    @Test
    fun `sound below the absolute floor is never speech even far above a silent floor`() {
        val vad = EnergyVad(rate, initialNoiseDb = -110.0)

        // ~ -67 dBFS: 40+ dB above the floor, but under the -55 dBFS absolute floor.
        assertTrue(decide(vad, hiss(300, amplitude = 15)).none { it })
    }

    @Test
    fun `a long conversation lifts the floor only slowly`() {
        val vad = EnergyVad(rate)
        val frames = decide(vad, hiss(1_000), tone(20_000))

        assertTrue(frames.takeLast(10).all { it }, "still speech after 20 s")
    }

    @Test
    fun `partial frames carry over between calls`() {
        val vad = EnergyVad(rate)
        val out = mutableListOf<Boolean>()
        val half = hiss(15)

        vad.process(half) { out += it }
        assertEquals(0, out.size)
        vad.process(half) { out += it }
        assertEquals(1, out.size)
    }

    @Test
    fun `count limits the samples read`() {
        val vad = EnergyVad(rate)
        val out = mutableListOf<Boolean>()

        vad.process(hiss(60), count = frame) { out += it }

        assertEquals(1, out.size)
    }

    @Test
    fun `decibels maps full scale to zero and silence to the floor`() {
        assertEquals(0.0, EnergyVad.decibels(32768.0 * 32768.0), 1e-9)
        assertEquals(-120.0, EnergyVad.decibels(0.0))
        assertEquals(-120.0, EnergyVad.decibels(1e-12))
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { EnergyVad(0) }
        assertFailsWith<IllegalArgumentException> { EnergyVad(rate, frameMs = 5) }
        assertFailsWith<IllegalArgumentException> { EnergyVad(rate, frameMs = 101) }
        assertFailsWith<IllegalArgumentException> { EnergyVad(rate, marginDb = 0.0) }
        assertFailsWith<IllegalArgumentException> { EnergyVad(rate, riseDbPerSecond = -1.0) }
        assertFailsWith<IllegalArgumentException> { EnergyVad(50, frameMs = 10) }
        assertFailsWith<IllegalArgumentException> {
            EnergyVad(rate).process(ShortArray(4), count = 5) {}
        }
    }
}
