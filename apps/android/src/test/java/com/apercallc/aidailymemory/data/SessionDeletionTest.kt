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

            db.sessions().deleteSession(target)

            assertEquals(emptyList(), db.segments().forSession(target))
            assertEquals(1, db.segments().forSession(other).size)
            assertEquals(0, db.markers().observeCount(target).first())
            assertEquals(1, db.markers().observeCount(other).first())
            assertEquals(null, db.speech().analysis("$target-0"))
            assertEquals(1, db.sessions().observeSince("2026-10-01").first().size)
        }

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
