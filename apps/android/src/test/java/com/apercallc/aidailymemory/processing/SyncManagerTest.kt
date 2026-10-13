package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.SyncDeletionQueueEntity
import com.apercallc.aidailymemory.settings.ProcessingSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SyncManagerTest {
    private lateinit var db: CaptureDatabase
    private lateinit var settings: ProcessingSettings
    private val bodies = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = ProcessingSettings(
            context.getSharedPreferences("sync-manager-test", Context.MODE_PRIVATE),
        ) { com.apercallc.aidailymemory.settings.testAead() }.apply {
            setSyncEnabled(true)
            syncUrl = "https://sync.example/v1/sync"
        }
    }

    @After
    fun tearDown() = db.close()

    private fun manager(
        failWith: (() -> Nothing)? = null,
    ) = SyncManager(
        database = db,
        settings = settings,
        deviceId = "test-device",
        poster = { _, _, body ->
            failWith?.invoke()
            bodies.add(body)
        },
    )

    private fun queueDeletion(sessionId: String) = SyncDeletionQueueEntity(sessionId, 1_000)

    private fun report(day: String, generatedAt: Long) = DailyReportEntity(
        id = "report-$day",
        memoryDay = day,
        zoneId = "UTC",
        headline = "headline",
        summary = "summary",
        reportJson = "{}",
        generatedAtEpochMs = generatedAt,
        extractionModel = "test",
        extractionVersion = 1,
    )

    private fun sentDeletedSessions(): List<String> {
        val root = Json.parseToJsonElement(bodies.last()).jsonObject
        val field = root["deletedSessions"] ?: return emptyList()
        return field.jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `deletions-only payload is sent, drains the queue, and keeps the report cursor`() = runTest {
        db.syncDeletions().enqueue(queueDeletion("gone-1"))

        assertTrue(manager().syncPending())

        assertEquals(listOf("gone-1"), sentDeletedSessions())
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
        assertEquals(null, settings.lastSyncEpochMs)
        assertFalse(settings.lastSyncFailed.value)
    }

    @Test
    fun `a rejected payload keeps the queue for the next schedule`() = runTest {
        db.syncDeletions().enqueue(queueDeletion("gone-1"))

        assertFalse(
            manager(failWith = { throw java.io.IOException("sync_rejected status=500") }).syncPending(),
        )

        assertEquals(listOf("gone-1"), db.syncDeletions().pendingSessionIds())
        assertTrue(settings.lastSyncFailed.value)
    }

    @Test
    fun `reports and deletions travel in one payload and the cursor advances once`() = runTest {
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))
        db.syncDeletions().enqueue(queueDeletion("gone-1"))

        assertTrue(manager().syncPending())

        val root = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(1, root["reports"]!!.jsonArray.size)
        assertEquals(listOf("gone-1"), sentDeletedSessions())
        assertEquals(5_000L, settings.lastSyncEpochMs)
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
    }

    @Test
    fun `cursor is the newest generated_at sent, not the wall clock`() = runTest {
        db.dailyReports().upsert(report("2026-10-11", generatedAt = 5_000))
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 9_000))

        assertTrue(manager().syncPending())

        assertEquals(9_000L, settings.lastSyncEpochMs)
    }

    @Test
    fun `enabled sync without a url fails visibly`() = runTest {
        settings.syncUrl = null

        assertFalse(manager().syncPending())

        assertTrue(settings.lastSyncFailed.value)
        assertEquals(emptyList<String>(), bodies)
    }

    @Test
    fun `nothing to send makes no network call`() = runTest {
        assertTrue(manager().syncPending())
        assertEquals(emptyList<String>(), bodies)
    }
}
