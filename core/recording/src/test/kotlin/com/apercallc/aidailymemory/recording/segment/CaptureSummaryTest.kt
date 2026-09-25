package com.apercallc.aidailymemory.recording.segment

import com.apercallc.aidailymemory.recording.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureSummaryTest {
    private val session = SessionId("s")

    private fun segment(
        sequence: Int,
        state: SegmentState,
        durationMs: Long = 60_000,
        bytes: Long = 1_000,
        defect: SegmentDefect? = null,
    ) = AudioSegment(
        sessionId = session,
        sequence = sequence,
        state = state,
        byteSize = bytes,
        durationMs = durationMs,
        acknowledgedAtEpochMs = if (state == SegmentState.ACKNOWLEDGED) 1 else null,
        defect = defect,
    )

    @Test
    fun `no segments means nothing captured`() {
        assertEquals(CaptureSummary.Empty, CaptureSummary.of(emptyList()))
    }

    @Test
    fun `only sealed or later audio counts as captured`() {
        val summary = CaptureSummary.of(
            listOf(
                segment(0, SegmentState.ACKNOWLEDGED),
                segment(1, SegmentState.UPLOADED),
                segment(2, SegmentState.SEALED),
                segment(3, SegmentState.QUARANTINED, defect = SegmentDefect.EMPTY),
                segment(4, SegmentState.WRITING),
            ),
        )

        assertEquals(3 * 60_000L, summary.capturedMs)
        assertEquals(2 * 60_000L, summary.pendingUploadMs)
        assertEquals(2_000L, summary.pendingUploadBytes)
        assertEquals(1, summary.quarantinedSegments)
        assertEquals(2, summary.lastPersistedSequence)
    }

    @Test
    fun `deleted segments keep their history`() {
        val summary = CaptureSummary.of(
            listOf(
                segment(5, SegmentState.DELETED),
                segment(9, SegmentState.DELETED, defect = SegmentDefect.ZERO_DURATION),
                segment(1, SegmentState.ACKNOWLEDGED),
            ),
        )

        assertEquals(2 * 60_000L, summary.capturedMs)
        assertEquals(0L, summary.pendingUploadMs)
        assertEquals(1, summary.quarantinedSegments)
        // Order-independent, and a quarantined segment never counts as the latest capture.
        assertEquals(5, summary.lastPersistedSequence)
    }
}
