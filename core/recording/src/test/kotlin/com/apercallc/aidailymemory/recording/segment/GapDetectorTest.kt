package com.apercallc.aidailymemory.recording.segment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GapDetectorTest {
    private val detector = GapDetector()

    private fun seg(
        sequence: Int,
        start: Long = sequence * 30_000L,
        duration: Long = 30_000,
        boot: String = "b1",
        continues: Boolean = sequence > 0,
    ) = SegmentTiming(sequence, boot, start, duration, continues)

    @Test
    fun `contiguous segments within tolerance have no gaps, in any input order`() {
        val segments = listOf(seg(2), seg(0), seg(1, start = 30_200), seg(3))

        assertTrue(detector.detect(segments).isEmpty())
        assertTrue(detector.detect(emptyList()).isEmpty())
    }

    @Test
    fun `missing sequences at the start and in the middle are reported with counts`() {
        val gaps = detector.detect(listOf(seg(2), seg(3), seg(7)))

        assertEquals(
            listOf(
                CaptureGap(GapKind.MISSING_SEGMENTS, null, 2, 2),
                CaptureGap(GapKind.MISSING_SEGMENTS, 3, 7, 3),
            ),
            gaps,
        )
    }

    @Test
    fun `time holes and overlaps inside continuous capture are reported`() {
        val gaps = detector.detect(
            listOf(seg(0), seg(1, start = 45_000), seg(2, start = 70_000)),
        )

        assertEquals(
            listOf(
                CaptureGap(GapKind.TIME_DISCONTINUITY, 0, 1, 15_000),
                CaptureGap(GapKind.OVERLAP, 1, 2, 5_000),
            ),
            gaps,
        )
    }

    @Test
    fun `intentional boundaries such as pause and resume are not gaps`() {
        val gaps = detector.detect(listOf(seg(0), seg(1, start = 3_600_000, continues = false)))

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `continuous capture across a reboot and duplicate sequences are flagged`() {
        val gaps = detector.detect(listOf(seg(0), seg(1, start = 10, boot = "b2"), seg(1)))

        assertEquals(
            setOf(GapKind.REBOOT, GapKind.DUPLICATE_SEQUENCE),
            gaps.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `tolerance must not be negative`() {
        assertFailsWith<IllegalArgumentException> { GapDetector(toleranceMs = -1) }
    }
}
