package com.apercallc.aidailymemory.recording.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechRegionsTest {
    private val frameMs = 100

    /** "." = no speech, "#" = speech, one character per 100 ms frame. */
    private fun frames(pattern: String) = BooleanArray(pattern.length) { pattern[it] == '#' }

    private fun build(pattern: String, totalMs: Long = pattern.length * 100L, config: SpeechConfig = SpeechConfig()) =
        frames(pattern).let { SpeechRegions.build(it, it.size, frameMs, totalMs, config) }

    @Test
    fun `silence yields no regions and nothing to transcribe`() {
        val summary = build(".".repeat(600))

        assertEquals(emptyList(), summary.regions)
        assertEquals(0, summary.paddedMs)
        assertEquals(0.0, summary.transcribedRatio)
    }

    @Test
    fun `a speech run is padded by pre and post roll`() {
        val summary = build(".".repeat(50) + "#".repeat(20) + ".".repeat(100))

        assertEquals(listOf(SpeechRegion(4_000, 9_000, 2_000, lowConfidence = false)), summary.regions)
        assertEquals(2_000, summary.speechMs)
        assertEquals(5_000, summary.paddedMs)
    }

    @Test
    fun `padding is clamped to the segment`() {
        val summary = build("#####" + ".".repeat(5))

        assertEquals(SpeechRegion(0, 1_000, 500, lowConfidence = false), summary.regions.single())
    }

    @Test
    fun `runs closer than the merge gap become one region`() {
        // 2.9 s gap merges; the combined speech is counted without the gap.
        val summary = build(".".repeat(20) + "#####" + ".".repeat(29) + "#####" + ".".repeat(60))

        val region = summary.regions.single()
        assertEquals(1_000, region.speechMs)
        assertEquals(1_000, region.startMs)
        assertEquals(7_900, region.endMs)
    }

    @Test
    fun `runs far apart stay separate`() {
        val summary = build(".".repeat(20) + "#####" + ".".repeat(80) + "#####" + ".".repeat(60))

        assertEquals(2, summary.regions.size)
    }

    @Test
    fun `padding overlap joins regions the gap rule kept apart`() {
        val config = SpeechConfig(preRollMs = 1_000, postRollMs = 2_000, mergeGapMs = 0)
        val summary = build(".".repeat(20) + "###" + ".".repeat(25) + "###" + ".".repeat(40), config = config)

        val region = summary.regions.single()
        assertEquals(600, region.speechMs)
        assertFalse(region.lowConfidence)
    }

    @Test
    fun `short blips are kept but marked low confidence`() {
        val summary = build(".".repeat(50) + "##" + ".".repeat(100))

        val region = summary.regions.single()
        assertTrue(region.lowConfidence)
        assertEquals(200, region.speechMs)
    }

    @Test
    fun `joined blips below the minimum stay low confidence`() {
        val config = SpeechConfig(mergeGapMs = 0, minSpeechMs = 500)
        val summary = build(".".repeat(20) + "#" + ".".repeat(10) + "#" + ".".repeat(40), config = config)

        assertTrue(summary.regions.single().lowConfidence)
    }

    @Test
    fun `speech running to the end of the segment closes at the real duration`() {
        // Last frame is partial: the segment is 1,050 ms, not 1,100.
        val summary = build(".".repeat(8) + "###", totalMs = 1_050)

        val region = summary.regions.single()
        assertEquals(1_050, region.endMs)
        assertEquals(250, region.speechMs)
    }

    @Test
    fun `frames past the real duration are ignored`() {
        val summary = build("....####", totalMs = 400)

        assertEquals(emptyList(), summary.regions)
    }

    @Test
    fun `transcribed ratio is padded time over recorded time`() {
        val summary = build(".".repeat(50) + "#".repeat(20) + ".".repeat(30))

        assertEquals(0.5, summary.transcribedRatio)
    }

    @Test
    fun `count limits the frames read`() {
        val all = frames("....####")

        assertEquals(emptyList(), SpeechRegions.build(all, 4, frameMs, 800).regions)
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { SpeechRegions.build(BooleanArray(2), 3, 100, 0) }
        assertFailsWith<IllegalArgumentException> { SpeechRegions.build(BooleanArray(2), 2, 0, 0) }
        assertFailsWith<IllegalArgumentException> { SpeechRegions.build(BooleanArray(2), 2, 100, -1) }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(preRollMs = -1) }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(postRollMs = -1) }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(mergeGapMs = -1) }
        assertFailsWith<IllegalArgumentException> { SpeechConfig(minSpeechMs = -1) }
        assertFailsWith<IllegalArgumentException> { SpeechRegion(5, 4, 0, false) }
        assertFailsWith<IllegalArgumentException> { SpeechRegion(-1, 4, 0, false) }
        assertFailsWith<IllegalArgumentException> { SpeechRegion(0, 4, 5, false) }
        assertFailsWith<IllegalArgumentException> { SpeechRegion(0, 4, -1, false) }
    }
}
