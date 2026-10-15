package com.apercallc.aidailymemory.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.data.TranscriptEntity
import com.apercallc.aidailymemory.data.TranscriptSpanEntity
import com.apercallc.aidailymemory.data.captureTime
import com.apercallc.aidailymemory.data.segmentEntity
import com.apercallc.aidailymemory.ui.sessions.SessionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionsViewModelTest {
    private val container = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>().container
    private val models = mutableListOf<SessionsViewModel>()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = runBlocking {
        models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private fun model() = SessionsViewModel(container).also { models += it }

    @Test
    fun `session transcript displays local evidence in its capture timezone after audio deletion`() = runBlocking {
        val db = container.database
        db.sessions().register(SessionInfoEntity("local", "2026-10-15", "UTC", 1_000, null))
        db.segments().insert(
            segmentEntity("local", state = "DELETED").copy(start = captureTime(zone = "Europe/Paris")),
        )
        db.localTranscripts().insertAll(listOf(line("local", "second", 2_000), line("local", "first", 1_000)))
        val rows = withTimeout(5_000) { model().transcript("local").first() }
        assertEquals(listOf("first", "second"), rows.map { it.text })
        assertEquals(listOf("Europe/Paris", "Europe/Paris"), rows.map { it.zoneId })
        assertEquals(listOf("first", "second"), rows.map { it.key })
    }

    @Test
    fun `local evidence missing its segment metadata falls back to the original session zone`() = runBlocking {
        val db = container.database
        db.sessions().register(SessionInfoEntity("local", "2026-10-15", "Asia/Tokyo", 1_000, null))
        db.localTranscripts().insertAll(listOf(line("local", "words", 1_000)))
        val rows = withTimeout(5_000) { model().transcript("local").first() }
        assertEquals("Asia/Tokyo", rows.single().zoneId)
    }

    @Test
    fun `legacy delivered evidence remains visible when no local transcript exists`() = runBlocking {
        val db = container.database
        db.segments().insert(segmentEntity("legacy", state = "DELETED"))
        db.transcripts().store(
            TranscriptEntity("upload", "legacy-0", 0, "test", "test", "en", 1_000),
            listOf(TranscriptSpanEntity("upload", 0, 0, 1_000, ByteArray(16), null)),
        )
        val rows = withTimeout(5_000) { model().transcript("legacy").first() }
        assertEquals(1, rows.size)
        assertEquals("America/New_York", rows.single().zoneId)
    }

    private fun line(session: String, id: String, start: Long) = LocalTranscriptLineEntity(
        lineId = id, sessionId = session, segmentId = "$session-0",
        startEpochMs = start, endEpochMs = start + 500, speaker = null, text = id,
        confidence = null, provider = "test", model = "test",
    )
}
