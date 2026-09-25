package com.apercallc.aidailymemory.recording.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdtsFramingTest {
    private val writer = AdtsHeaderWriter(sampleRateHz = 16_000, channelCount = 1)

    /** A stream of [count] frames, each with [payload] bytes of filler. */
    private fun stream(count: Int, payload: Int = 100): ByteArray {
        val frame = AdtsHeaderWriter.HEADER_SIZE + payload
        val out = ByteArray(frame * count)
        repeat(count) { i ->
            writer.write(payload, out, i * frame)
            out.fill(0x55, i * frame + AdtsHeaderWriter.HEADER_SIZE, (i + 1) * frame)
        }
        return out
    }

    @Test
    fun `header matches a known-good reference encoding`() {
        // AAC-LC, 44.1 kHz, stereo, frame length 0x0180, hand-encoded from ISO/IEC 14496-3.
        val header = ByteArray(7)
        AdtsHeaderWriter(44_100, 2).write(payloadSize = 0x0180 - 7, target = header)

        assertContentEquals(
            intArrayOf(0xFF, 0xF1, 0x50, 0x80, 0x30, 0x1F, 0xFC).map { it.toByte() },
            header.toList(),
        )
    }

    @Test
    fun `written frames scan back with the right count, rate, and duration`() {
        val scan = AdtsScanner.scan(stream(count = 25))

        assertEquals(25, scan.completeFrames)
        assertEquals(16_000, scan.sampleRateHz)
        assertEquals(25L * 1024 * 1000 / 16_000, scan.durationMs)
        assertFalse(scan.corrupt)
    }

    @Test
    fun `a torn final frame is a bounded loss, not corruption`() {
        val full = stream(count = 3)
        listOf(full.size - 1, full.size - 50, 2 * 107 + 3, 2 * 107 + 1).forEach { cut ->
            val scan = AdtsScanner.scan(full, cut)
            assertEquals(2, scan.completeFrames, "cut=$cut")
            assertEquals(2 * 107, scan.validBytes, "cut=$cut")
            assertFalse(scan.corrupt, "cut=$cut")
        }
    }

    @Test
    fun `garbage where a header should be is corruption`() {
        val data = stream(count = 2)
        data[107] = 0x12
        val scan = AdtsScanner.scan(data)

        assertEquals(1, scan.completeFrames)
        assertTrue(scan.corrupt)
    }

    @Test
    fun `a torn tail that is not a header prefix is corruption`() {
        val data = stream(count = 1) + byteArrayOf(0x01, 0x02)
        assertTrue(AdtsScanner.scan(data).corrupt)
        assertTrue(AdtsScanner.scan(stream(1) + byteArrayOf(0x01)).corrupt)
        assertFalse(AdtsScanner.scan(stream(1) + byteArrayOf(0xFF.toByte())).corrupt)
    }

    @Test
    fun `impossible frame lengths and rate indexes are corruption`() {
        val shortFrame = stream(count = 1)
        shortFrame[3] = 0
        shortFrame[4] = 0
        shortFrame[5] = 0x1F // length 0
        assertTrue(AdtsScanner.scan(shortFrame).corrupt)

        val badRate = stream(count = 1)
        badRate[2] = (badRate[2].toInt() or 0x3C).toByte() // index 15
        assertTrue(AdtsScanner.scan(badRate).corrupt)
    }

    @Test
    fun `empty input has no frames and no duration`() {
        val scan = AdtsScanner.scan(ByteArray(0))

        assertEquals(0, scan.completeFrames)
        assertNull(scan.sampleRateHz)
        assertEquals(0, scan.durationMs)
    }

    @Test
    fun `invalid writer and scanner arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { AdtsHeaderWriter(12_345, 1) }
        assertFailsWith<IllegalArgumentException> { AdtsHeaderWriter(16_000, 0) }
        assertFailsWith<IllegalArgumentException> { AdtsHeaderWriter(16_000, 8) }
        assertFailsWith<IllegalArgumentException> { writer.write(0, ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { writer.write(0x1FFF, ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { writer.write(10, ByteArray(6)) }
        assertFailsWith<IllegalArgumentException> { writer.write(10, ByteArray(7), -1) }
        assertFailsWith<IllegalArgumentException> { AdtsScanner.scan(ByteArray(1), 2) }
    }
}
