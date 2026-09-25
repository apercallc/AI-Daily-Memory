package com.apercallc.aidailymemory.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.journal.JournalCodec
import com.apercallc.aidailymemory.recording.journal.RestoredState
import com.apercallc.aidailymemory.recording.segment.SegmentEvent
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class CaptureDatabaseTest {
    private lateinit var db: CaptureDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `journal restores the newest transition after a restart`() = runTest {
        val session = SessionId("session-1")
        var state = RecordingState.Initial
        listOf(RecordingCommand.Start(session), RecordingCommand.Stop).forEach { event ->
            val applied = assertIs<Transition.Applied>(RecordingStateMachine.reduce(state, event))
            db.transitions().append(TransitionEntity.of(JournalCodec.encode(applied), captureTime()))
            state = applied.state
        }

        val restored = JournalCodec.restore(db.transitions().latest()?.toRecord())

        assertEquals(RestoredState.Intact(state), restored)
        assertEquals(2, db.transitions().forSession("session-1").size)
    }

    @Test
    fun `an empty journal has no latest entry`() = runTest {
        assertNull(db.transitions().latest())
    }

    @Test
    fun `the journal refuses to overwrite a revision`() = runTest {
        val entity = TransitionEntity(1, "STARTING", "s", null, "command:Start", captureTime())
        db.transitions().append(entity)

        assertFailsWith<SQLiteConstraintException> {
            db.transitions().append(entity.copy(status = "RECORDING"))
        }
    }

    @Test
    fun `segment sequences are dense per session and unique`() = runTest {
        val segments = db.segments()
        assertEquals(0, segments.nextSequence("session-1"))
        segments.insert(segmentEntity(sequence = 0))
        segments.insert(segmentEntity(sequence = 1))
        segments.insert(segmentEntity(sessionId = "other", sequence = 0))

        assertEquals(2, segments.nextSequence("session-1"))
        assertFailsWith<SQLiteConstraintException> {
            segments.insert(segmentEntity(sequence = 1).copy(segmentId = "duplicate-sequence"))
        }
    }

    @Test
    fun `lifecycle results round-trip and only unacknowledged bytes are pending`() = runTest {
        val segments = db.segments()
        val lifecycle = SegmentLifecycle(recoveryWindowMs = 0)
        val entity = segmentEntity(sequence = 0)
        segments.insert(entity)
        segments.insert(segmentEntity(sequence = 1, state = "ACKNOWLEDGED", byteSize = 999).copy(
            acknowledgedAtEpochMs = 1,
        ))

        val sealed = lifecycle.reduce(
            entity.toSegment(),
            SegmentEvent.Seal(byteSize = 4_096, durationMs = 60_000, sha256 = "AB".repeat(32)),
        ).segment
        segments.update(entity.withSegment(sealed))

        val loaded = checkNotNull(segments.get(entity.segmentId))
        assertEquals(SegmentState.SEALED, loaded.toSegment().state)
        assertEquals("ab".repeat(32), loaded.sha256)
        assertEquals(4_096L, segments.observePendingBytes().first())
        assertEquals(listOf(0, 1), segments.forSession("session-1").map { it.sequence })
        assertEquals(1, segments.inState("ACKNOWLEDGED").size)
        assertEquals(60_000L, loaded.toTiming().durationMs)
    }

    @Test
    fun `latest marker of a kind is found for debouncing`() = runTest {
        val markers = db.markers()
        markers.insert(MarkerEntity("m1", "s", "HIGHLIGHT", 30_000, 5_000, captureTime(epochMs = 1_000)))
        markers.insert(MarkerEntity("m2", "s", "HIGHLIGHT", 30_000, 5_000, captureTime(epochMs = 2_000)))
        markers.insert(MarkerEntity("m3", "s", "QUICK_NOTE", 0, 60_000, captureTime(epochMs = 3_000)))

        assertEquals("m2", markers.latest("s", "HIGHLIGHT")?.markerId)
        assertNull(markers.latest("other", "HIGHLIGHT"))
        assertEquals(3, markers.observeCount("s").first())
    }

    @Test
    fun `capture time survives storage and re-validates on load`() {
        val columns = captureTime(zone = "Europe/Berlin")
        assertEquals(columns, CaptureTimeColumns.of(columns.toCaptureTime()))

        assertFailsWith<IllegalArgumentException> {
            columns.copy(utcOffsetMinutes = columns.utcOffsetMinutes + 60).toCaptureTime()
        }
    }
}
