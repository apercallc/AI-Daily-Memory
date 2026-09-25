package com.apercallc.aidailymemory.recording.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClipPlannerTest {
    private val rate = 16_000 // 64 ms per frame

    private fun region(start: Long, end: Long) = SpeechRegion(start, end, end - start, lowConfidence = false)

    @Test
    fun `a region becomes the whole frames that cover it`() {
        // 100..700 ms: frames 1 (64..128) through 10 (640..704).
        val clip = ClipPlanner.plan(listOf(region(100, 700)), frameCount = 100, sampleRateHz = rate).single()

        assertEquals(PlannedClip(firstFrame = 1, endFrame = 11, offsetMs = 64, durationMs = 640), clip)
        assertEquals(10, clip.frameCount)
    }

    @Test
    fun `a region ending exactly on a frame boundary does not take the next frame`() {
        val clip = ClipPlanner.plan(listOf(region(0, 640)), frameCount = 100, sampleRateHz = rate).single()

        assertEquals(10, clip.endFrame)
    }

    @Test
    fun `clips never run past the frames the segment has`() {
        val clips = ClipPlanner.plan(listOf(region(600, 10_000), region(20_000, 30_000)), 12, rate)

        assertEquals(listOf(PlannedClip(9, 12, 576, 192)), clips)
    }

    @Test
    fun `long regions are split into clips of at most the maximum`() {
        val clips = ClipPlanner.plan(listOf(region(0, 2_560)), 100, rate, maxClipMs = 1_024)

        assertEquals(listOf(0 to 16, 16 to 32, 32 to 40), clips.map { it.firstFrame to it.endFrame })
        assertEquals(2_560, clips.sumOf { it.durationMs })
    }

    @Test
    fun `neighbours that round into the same frame never upload it twice`() {
        // 0..100 ends in frame 1; 110..300 starts in frame 1.
        val clips = ClipPlanner.plan(listOf(region(110, 300), region(0, 100)), 100, rate)

        assertEquals(listOf(0 to 2, 2 to 5), clips.map { it.firstFrame to it.endFrame })
    }

    @Test
    fun `a neighbour fully inside the previous clip adds nothing`() {
        val clips = ClipPlanner.plan(listOf(region(0, 200), region(130, 190)), 100, rate)

        assertEquals(1, clips.size)
    }

    @Test
    fun `no regions or no frames means no clips`() {
        assertEquals(emptyList(), ClipPlanner.plan(emptyList(), 100, rate))
        assertEquals(emptyList(), ClipPlanner.plan(listOf(region(0, 1_000)), 0, rate))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { ClipPlanner.plan(emptyList(), -1, rate) }
        assertFailsWith<IllegalArgumentException> { ClipPlanner.plan(emptyList(), 1, 0) }
        assertFailsWith<IllegalArgumentException> { ClipPlanner.plan(emptyList(), 1, rate, maxClipMs = 10) }
        assertFailsWith<IllegalArgumentException> { PlannedClip(3, 3, 0, 0) }
        assertFailsWith<IllegalArgumentException> { PlannedClip(-1, 3, 0, 0) }
    }
}
