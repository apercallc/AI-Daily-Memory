package com.apercallc.aidailymemory.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SessionDeletionTest {
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
    fun `deleting one stopped session removes its derived rows and leaves other sessions intact`() =
        runTest {
            val target = "session-delete"
            val other = "session-keep"
            db.sessions().register(sessionInfo(target))
            db.sessions().register(sessionInfo(other))
            db.segments().insert(segmentEntity(target, state = "DELETED"))
            db.segments().insert(segmentEntity(other, state = "DELETED"))
            db.markers().insert(marker(target))
            db.markers().insert(marker(other))
            db.speech().save(
                SegmentSpeechEntity("$target-0", "energy-v1", "OK", 1000, 400, 800, 1, 1),
                listOf(SpeechRegionEntity("$target-0", 0, 500, 400, false)),
            )
            db.localTranscripts().insertAll(
                listOf(
                    LocalTranscriptLineEntity(
                        lineId = "line-$target",
                        sessionId = target,
                        segmentId = "$target-0",
                        startEpochMs = 1_000,
                        endEpochMs = 2_000,
                        speaker = null,
                        text = "private transcript",
                        confidence = 0.9,
                        provider = "test",
                        model = "test",
                    ),
                ),
            )
            db.memoryItems().insertAll(listOf(memoryItem(target), memoryItem(other)))
            db.processingState().upsert(
                ProcessingStateEntity(target, "COMPLETED", true, null, 2_000),
            )
            db.dailyReports().upsert(
                DailyReportEntity(
                    id = "report-$target",
                    memoryDay = "2026-10-11",
                    zoneId = "America/Chicago",
                    headline = "private report",
                    summary = "private summary",
                    reportJson = "{}",
                    generatedAtEpochMs = 2_000,
                    extractionModel = "test",
                    extractionVersion = 1,
                ),
            )

            db.sessions().deleteSession(target)

            assertEquals(emptyList(), db.segments().forSession(target))
            assertEquals(1, db.segments().forSession(other).size)
            assertEquals(0, db.markers().observeCount(target).first())
            assertEquals(1, db.markers().observeCount(other).first())
            assertEquals(null, db.speech().analysis("$target-0"))
            assertEquals(emptyList(), db.localTranscripts().forSession(target))
            assertEquals(emptyList(), db.memoryItems().forSession(target))
            assertEquals(1, db.memoryItems().forSession(other).size)
            assertEquals(null, db.processingState().forSession(target))
            assertEquals(null, db.dailyReports().forDay("2026-10-11"))
            assertEquals(1, db.sessions().observeSince("2026-10-01").first().size)
        }

    @Test
    fun `local deletion durably queues remote purge without a link or sync setting`() = runTest {
        db.sessions().register(sessionInfo("delete"))
        db.sessions().deleteSession("delete")
        assertEquals(listOf("delete"), db.syncDeletions().pendingSessionIds())
        db.sessions().deleteSession("delete")
        assertEquals(listOf("delete"), db.syncDeletions().pendingSessionIds())
    }

    @Test
    fun `a failed local database deletion rolls back the purge queue too`() = runTest {
        db.sessions().register(sessionInfo("keep"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_delete BEFORE DELETE ON session_info " +
                "BEGIN SELECT RAISE(ABORT, 'test refusal'); END",
        )
        kotlin.test.assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            db.sessions().deleteSession("keep")
        }
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
        assertEquals("2026-10-11", db.sessions().memoryDayForSession("keep"))
    }

    private fun memoryItem(sessionId: String) = MemoryItemEntity(
        id = "memory-$sessionId",
        sessionId = sessionId,
        memoryDay = "2026-10-11",
        kind = "decision",
        text = "private memory",
        confidence = "confirmed",
        quote = "evidence",
        owner = null,
        due = null,
        direction = null,
        counterpart = null,
        extractionModel = "test",
        extractionVersion = 1,
        evidenceEpochMs = 1_000,
        dismissedAtEpochMs = null,
        extractedAtEpochMs = 2_000,
    )

    private fun sessionInfo(id: String) = SessionInfoEntity(
        sessionId = id,
        memoryDay = "2026-10-11",
        zoneId = "America/Chicago",
        startedAtEpochMs = 1,
        customTitle = null,
    )

    private fun marker(sessionId: String) = MarkerEntity(
        markerId = "marker-$sessionId",
        sessionId = sessionId,
        kind = "HIGHLIGHT",
        windowBeforeMs = 0,
        windowAfterMs = 0,
        time = captureTime(),
    )
}
