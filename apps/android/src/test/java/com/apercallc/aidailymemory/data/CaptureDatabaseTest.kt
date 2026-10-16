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
    fun `processing queue includes only completed fully analyzed sessions with speech`() = runTest {
        val segments = db.segments()
        val transitions = db.transitions()
        val ready = db.speech()
        segments.insert(segmentEntity(sessionId = "ready", sequence = 0, state = "SEALED"))
        segments.insert(segmentEntity(sessionId = "ready", sequence = 1, state = "SEALED"))
        ready.upsert(speech("ready-0", "OK"))
        ready.upsert(speech("ready-1", "UNREADABLE"))
        transitions.append(TransitionEntity(1, "COMPLETED", "ready", null, "stop", captureTime()))

        segments.insert(segmentEntity(sessionId = "still-analyzing", state = "SEALED"))
        transitions.append(
            TransitionEntity(2, "COMPLETED", "still-analyzing", null, "stop", captureTime()),
        )

        segments.insert(segmentEntity(sessionId = "no-speech", state = "SEALED"))
        ready.upsert(speech("no-speech-0", "OK", speechMs = 0))
        transitions.append(
            TransitionEntity(3, "COMPLETED", "no-speech", null, "stop", captureTime()),
        )

        segments.insert(segmentEntity(sessionId = "active", state = "SEALED"))
        ready.upsert(speech("active-0", "OK"))
        transitions.append(TransitionEntity(4, "RECORDING", "active", null, "resume", captureTime()))

        val processing = db.processingState()
        processing.enqueueReadySessions("energy-v1", 99L)
        processing.enqueueReadySessions("energy-v1", 100L)

        assertEquals("PENDING", processing.forSession("ready")?.state)
        assertEquals(99L, processing.forSession("ready")?.updatedAtEpochMs)
        assertNull(processing.forSession("still-analyzing"))
        assertNull(processing.forSession("no-speech"))
        assertNull(processing.forSession("active"))
    }

    private fun speech(segmentId: String, outcome: String, speechMs: Long = 10_000) = SegmentSpeechEntity(
        segmentId = segmentId,
        analyzer = "energy-v1",
        outcome = outcome,
        totalMs = 60_000,
        speechMs = speechMs,
        paddedMs = speechMs,
        regionCount = if (speechMs > 0) 1 else 0,
        analyzedAtEpochMs = 1,
    )

    @Test
    fun `one session's speech summary totals its own fully analyzed segments only`() = runTest {
        val segments = db.segments()
        val transitions = db.transitions()
        val ready = db.speech()
        db.sessions().register(sessionInfo("quiet"))
        segments.insert(segmentEntity(sessionId = "quiet", sequence = 0, state = "SEALED").copy(durationMs = 60_000))
        ready.upsert(speech("quiet-0", "OK", speechMs = 0))
        transitions.append(TransitionEntity(1, "COMPLETED", "quiet", null, "stop", captureTime()))

        db.sessions().register(sessionInfo("talking"))
        segments.insert(segmentEntity(sessionId = "talking", sequence = 0, state = "SEALED").copy(durationMs = 60_000))
        ready.upsert(speech("talking-0", "OK", speechMs = 5_000))
        transitions.append(TransitionEntity(2, "COMPLETED", "talking", null, "stop", captureTime()))

        db.sessions().register(sessionInfo("still-analyzing"))
        segments.insert(segmentEntity(sessionId = "still-analyzing", sequence = 0, state = "SEALED").copy(durationMs = 60_000))

        val quiet = requireNotNull(db.sessions().observeSpeechSummary("quiet").first())
        assertEquals(60_000L, quiet.capturedMs)
        assertEquals(60_000L, quiet.analyzedMs)
        assertEquals(0L, quiet.speechMs)

        val talking = requireNotNull(db.sessions().observeSpeechSummary("talking").first())
        assertEquals(5_000L, talking.speechMs)

        val stillAnalyzing = requireNotNull(db.sessions().observeSpeechSummary("still-analyzing").first())
        assertEquals(0L, stillAnalyzing.analyzedMs)

        assertEquals(null, db.sessions().observeSpeechSummary("unknown").first())
    }

    private fun sessionInfo(id: String) = SessionInfoEntity(
        sessionId = id,
        memoryDay = "2026-10-11",
        zoneId = "UTC",
        startedAtEpochMs = 1,
        customTitle = null,
    )

    @Test
    fun `interrupted processing stages are eligible for restart recovery`() = runTest {
        val processing = db.processingState()
        val recoverableStates = listOf("PENDING", "TRANSCRIBING", "TRANSCRIBED", "EXTRACTING")
        recoverableStates.forEachIndexed { index, state ->
            processing.upsert(
                ProcessingStateEntity(
                    sessionId = "recover-$state",
                    state = state,
                    audioDeleted = false,
                    lastError = null,
                    updatedAtEpochMs = index.toLong(),
                ),
            )
        }
        processing.upsert(
            ProcessingStateEntity("finished", "COMPLETED", false, null, 4L),
        )
        processing.upsert(
            ProcessingStateEntity("failed", "FAILED", false, null, 5L),
        )

        assertEquals(
            recoverableStates,
            processing.pending(10).map { it.state },
        )
    }

    @Test
    fun `processing settings observe the newest failed machine reason`() = runTest {
        val processing = db.processingState()
        processing.upsert(
            ProcessingStateEntity("old-failure", "FAILED", false, "provider_quota_exhausted", 10L),
        )
        processing.upsert(
            ProcessingStateEntity("newer-pending", "PENDING", false, "provider_auth_failed", 30L),
        )
        processing.upsert(
            ProcessingStateEntity("new-failure", "FAILED", false, "provider_rate_limited", 20L),
        )

        assertEquals("provider_rate_limited", processing.observeMostRecentFailedError().first())
    }

    @Test
    fun `provider usage is observed locally and removed with its session`() = runTest {
        val usage = db.providerUsage()
        val now = 1_800_000_000_000L
        db.sessions().register(
            SessionInfoEntity("usage-session", "2026-10-14", "UTC", now, null),
        )
        usage.record(
            ProviderUsageEntity(
                requestId = "usage-request",
                sessionId = "usage-session",
                stage = "SUMMARY",
                providerId = "openai",
                model = "gpt-4o-mini",
                requestAudioMs = 0,
                sessionAudioMs = 60_000,
                inputTokens = 100,
                outputTokens = 20,
                recordedAtEpochMs = now,
            ),
            now,
        )

        assertEquals(1, usage.observeSince(now - 7L * 24 * 60 * 60 * 1_000).first().size)
        db.sessions().deleteSession("usage-session")
        assertEquals(0, usage.observeSince(now - 7L * 24 * 60 * 60 * 1_000).first().size)
    }

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
