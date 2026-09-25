package com.apercallc.aidailymemory.recording.segment

import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SegmentLifecycleTest {
    private val window = 24 * 60 * 60_000L
    private val lifecycle = SegmentLifecycle(recoveryWindowMs = window)
    private val sha = "a".repeat(64)
    private val seal = SegmentEvent.Seal(byteSize = 90_000, durationMs = 30_000, sha256 = sha)

    private fun writing() = AudioSegment.writing(SessionId("s"), sequence = 3)

    private fun inState(state: SegmentState): AudioSegment {
        val sealed = lifecycle.reduce(writing(), seal).segment
        return when (state) {
            SegmentState.WRITING -> writing()
            SegmentState.SEALED -> sealed
            SegmentState.UPLOADED -> sealed.copy(state = SegmentState.UPLOADED)
            SegmentState.ACKNOWLEDGED ->
                sealed.copy(state = SegmentState.ACKNOWLEDGED, acknowledgedAtEpochMs = 1_000)
            SegmentState.QUARANTINED -> sealed.copy(state = SegmentState.QUARANTINED)
            SegmentState.DELETED -> sealed.copy(state = SegmentState.DELETED)
        }
    }

    @Test
    fun `happy path seals, uploads, acknowledges, and deletes after the window`() {
        var segment = writing()
        val events = listOf(
            seal,
            SegmentEvent.UploadCompleted,
            SegmentEvent.ServerAcknowledged(sha.uppercase(), atEpochMs = 1_000),
            SegmentEvent.RetentionDelete(nowEpochMs = 1_000 + window),
        )
        val states = events.map { event ->
            val result = lifecycle.reduce(segment, event)
            assertIs<SegmentTransition.Applied>(result, "$event")
            segment = result.segment
            segment.state
        }

        assertEquals(
            listOf(
                SegmentState.SEALED,
                SegmentState.UPLOADED,
                SegmentState.ACKNOWLEDGED,
                SegmentState.DELETED,
            ),
            states,
        )
    }

    @Test
    fun `invalid seals are quarantined with the defect and no checksum`() {
        val cases = mapOf(
            seal.copy(byteSize = 0) to SegmentDefect.EMPTY,
            seal.copy(durationMs = 0) to SegmentDefect.ZERO_DURATION,
            seal.copy(sha256 = "not-a-hash") to SegmentDefect.MALFORMED_CHECKSUM,
        )
        cases.forEach { (event, defect) ->
            val result = lifecycle.reduce(writing(), event).segment
            assertEquals(SegmentState.QUARANTINED, result.state)
            assertEquals(defect, result.defect)
            assertNull(result.sha256)
        }
    }

    @Test
    fun `unacknowledged audio is never deleted by retention, whatever the clock says`() {
        listOf(SegmentState.WRITING, SegmentState.SEALED, SegmentState.UPLOADED).forEach {
            val result = lifecycle.reduce(inState(it), SegmentEvent.RetentionDelete(Long.MAX_VALUE))
            assertEquals(
                SegmentRejection.NOT_ACKNOWLEDGED,
                assertIs<SegmentTransition.Rejected>(result, "$it").reason,
            )
        }
    }

    @Test
    fun `acknowledged audio is kept for the full recovery window`() {
        val justBefore = lifecycle.reduce(
            inState(SegmentState.ACKNOWLEDGED),
            SegmentEvent.RetentionDelete(nowEpochMs = 1_000 + window - 1),
        )
        val clockWentBack = lifecycle.reduce(
            inState(SegmentState.ACKNOWLEDGED),
            SegmentEvent.RetentionDelete(nowEpochMs = 0),
        )

        listOf(justBefore, clockWentBack).forEach {
            assertEquals(
                SegmentRejection.RECOVERY_WINDOW_OPEN,
                assertIs<SegmentTransition.Rejected>(it).reason,
            )
        }
    }

    @Test
    fun `kept audio outlives the recovery window until the user's setting allows deletion`() {
        val acknowledged = inState(SegmentState.ACKNOWLEDGED) // acknowledged at 1_000
        val day = 24 * 60 * 60_000L
        fun delete(now: Long, retention: AudioRetention) =
            lifecycle.reduce(acknowledged, SegmentEvent.RetentionDelete(now, retention))

        assertIs<SegmentTransition.Applied>(delete(1_000 + window, AudioRetention.TRANSCRIPT_ONLY))
        assertEquals(
            SegmentRejection.RETAINED_BY_USER,
            assertIs<SegmentTransition.Rejected>(delete(1_000 + window, AudioRetention.KEEP_7_DAYS)).reason,
        )
        assertIs<SegmentTransition.Applied>(delete(1_000 + 7 * day, AudioRetention.KEEP_7_DAYS))
        assertEquals(
            SegmentRejection.RETAINED_BY_USER,
            assertIs<SegmentTransition.Rejected>(
                delete(Long.MAX_VALUE, AudioRetention.KEEP_UNTIL_DELETED),
            ).reason,
        )
        // No setting shortens the recovery window.
        assertEquals(
            SegmentRejection.RECOVERY_WINDOW_OPEN,
            assertIs<SegmentTransition.Rejected>(delete(1_000, AudioRetention.TRANSCRIPT_ONLY)).reason,
        )
        // The user can always delete kept audio themselves.
        assertIs<SegmentTransition.Applied>(lifecycle.reduce(acknowledged, SegmentEvent.UserDelete))
    }

    @Test
    fun `quarantined segments can be cleaned up and deletes are idempotent`() {
        assertIs<SegmentTransition.Applied>(
            lifecycle.reduce(inState(SegmentState.QUARANTINED), SegmentEvent.RetentionDelete(0)),
        )
        assertIs<SegmentTransition.Ignored>(
            lifecycle.reduce(inState(SegmentState.DELETED), SegmentEvent.RetentionDelete(0)),
        )
        assertIs<SegmentTransition.Ignored>(
            lifecycle.reduce(inState(SegmentState.DELETED), SegmentEvent.UserDelete),
        )
    }

    @Test
    fun `user deletion works in any finished state but not while writing`() {
        listOf(
            SegmentState.SEALED,
            SegmentState.UPLOADED,
            SegmentState.ACKNOWLEDGED,
            SegmentState.QUARANTINED,
        ).forEach {
            val result = lifecycle.reduce(inState(it), SegmentEvent.UserDelete)
            assertEquals(SegmentState.DELETED, assertIs<SegmentTransition.Applied>(result).segment.state)
        }
        val writing = lifecycle.reduce(inState(SegmentState.WRITING), SegmentEvent.UserDelete)
        assertEquals(
            SegmentRejection.STILL_WRITING,
            assertIs<SegmentTransition.Rejected>(writing).reason,
        )
    }

    @Test
    fun `checksum mismatch keeps the local copy for re-upload`() {
        val result = lifecycle.reduce(
            inState(SegmentState.UPLOADED),
            SegmentEvent.ServerAcknowledged("b".repeat(64), atEpochMs = 5),
        )

        val rejected = assertIs<SegmentTransition.Rejected>(result)
        assertEquals(SegmentRejection.CHECKSUM_MISMATCH, rejected.reason)
        assertEquals(SegmentState.UPLOADED, rejected.segment.state)
    }

    @Test
    fun `ack that races ahead of the local upload record is accepted`() {
        val result = lifecycle.reduce(
            inState(SegmentState.SEALED),
            SegmentEvent.ServerAcknowledged(sha, atEpochMs = 9),
        )

        assertEquals(SegmentState.ACKNOWLEDGED, assertIs<SegmentTransition.Applied>(result).segment.state)
    }

    @Test
    fun `retried uploads and acks are idempotent`() {
        assertIs<SegmentTransition.Ignored>(
            lifecycle.reduce(inState(SegmentState.UPLOADED), SegmentEvent.UploadCompleted),
        )
        assertIs<SegmentTransition.Ignored>(
            lifecycle.reduce(inState(SegmentState.ACKNOWLEDGED), SegmentEvent.UploadCompleted),
        )
        assertIs<SegmentTransition.Ignored>(
            lifecycle.reduce(
                inState(SegmentState.ACKNOWLEDGED),
                SegmentEvent.ServerAcknowledged(sha, atEpochMs = 99),
            ),
        )
    }

    @Test
    fun `events out of order are rejected without changing the segment`() {
        val invalid = listOf(
            SegmentState.SEALED to seal,
            SegmentState.WRITING to SegmentEvent.UploadCompleted,
            SegmentState.QUARANTINED to SegmentEvent.UploadCompleted,
            SegmentState.DELETED to SegmentEvent.UploadCompleted,
            SegmentState.WRITING to SegmentEvent.ServerAcknowledged(sha, 1),
            SegmentState.QUARANTINED to SegmentEvent.ServerAcknowledged(sha, 1),
            SegmentState.DELETED to SegmentEvent.ServerAcknowledged(sha, 1),
        )
        invalid.forEach { (state, event) ->
            val segment = inState(state)
            val result = lifecycle.reduce(segment, event)
            val rejected = assertIs<SegmentTransition.Rejected>(result, "$state + $event")
            assertEquals(SegmentRejection.INVALID_IN_STATE, rejected.reason)
            assertEquals(segment, rejected.segment)
        }
    }

    @Test
    fun `a segment left writing by a dead process is quarantined as incomplete`() {
        val result = lifecycle.reduce(writing(), SegmentEvent.Abandon)

        val applied = assertIs<SegmentTransition.Applied>(result)
        assertEquals(SegmentState.QUARANTINED, applied.segment.state)
        assertEquals(SegmentDefect.INCOMPLETE, applied.segment.defect)
        assertNull(applied.segment.sha256)
    }

    @Test
    fun `abandon never downgrades a segment that already sealed`() {
        SegmentState.entries.filter { it != SegmentState.WRITING }.forEach { state ->
            val segment = inState(state)
            assertEquals(
                SegmentTransition.Ignored(segment),
                lifecycle.reduce(segment, SegmentEvent.Abandon),
                "$state",
            )
        }
    }

    @Test
    fun `constructor arguments are validated`() {
        assertFailsWith<IllegalArgumentException> { SegmentLifecycle(recoveryWindowMs = -1) }
        assertFailsWith<IllegalArgumentException> { AudioSegment.writing(SessionId("s"), -1) }
        // Corrupt persisted rows fail at load time, not deep inside the reducer.
        assertFailsWith<IllegalArgumentException> {
            inState(SegmentState.SEALED).copy(state = SegmentState.ACKNOWLEDGED)
        }
        assertFailsWith<IllegalArgumentException> {
            inState(SegmentState.SEALED).copy(acknowledgedAtEpochMs = 5)
        }
    }
}
