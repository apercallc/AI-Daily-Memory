package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TranscriptSyncSelectionTest {
    private lateinit var database: CaptureDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `transcript selection includes same-day sessions with no memory items`() = runTest {
        database.sessions().register(SessionInfoEntity("with-transcript", "2026-10-12", "UTC", 1L, null))
        database.sessions().register(SessionInfoEntity("other-day", "2026-10-11", "UTC", 2L, null))
        database.localTranscripts().insertAll(
            listOf(
                line("line-1", "with-transcript", 100L),
                line("line-2", "other-day", 200L),
            ),
        )

        val sessionIds = database.sessions().sessionIdsForDay("2026-10-12")
        val syncedLines = database.localTranscripts().forSessions(sessionIds)

        assertEquals(listOf("with-transcript"), sessionIds)
        assertEquals(listOf("line-1"), syncedLines.map { it.lineId })
    }

    private fun line(id: String, sessionId: String, start: Long) = LocalTranscriptLineEntity(
        lineId = id,
        sessionId = sessionId,
        segmentId = "segment-$sessionId",
        startEpochMs = start,
        endEpochMs = start + 1,
        speaker = null,
        text = "Transcript without derived memory",
        confidence = null,
        provider = "test",
        model = "test",
    )
}
