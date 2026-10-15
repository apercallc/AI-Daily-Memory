package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.data.SyncDeletionQueueEntity
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.recording.server.HostedServer
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
    private val urls = mutableListOf<String>()
    private var link: ServerLink? = ServerLink(HostedServer.BASE_URL, "adm_" + "T".repeat(43))
    private var notBefore = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val preferences = context.getSharedPreferences("sync-manager-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        settings = ProcessingSettings(
            preferences,
        ) { com.apercallc.aidailymemory.settings.testAead() }.apply {
            setSyncEnabled(true)
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
        link = { link },
        notBeforeEpochMs = { notBefore },
        nowEpochMs = { 42_000L },
        poster = { url, _, body ->
            failWith?.invoke()
            urls.add(url)
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
        assertEquals(42_000L, settings.lastSyncSucceededAtEpochMs.value)
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
    fun `reports go to the hosted server with the account token`() = runTest {
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))
        var token: String? = null
        val sent = SyncManager(
            database = db,
            settings = settings,
            deviceId = "test-device",
            link = { link },
            poster = { url, bearer, _ ->
                urls.add(url)
                token = bearer
            },
        )

        assertTrue(sent.syncPending())

        assertEquals(listOf("https://aidailymem.apercallc.com/v1/sync"), urls)
        assertEquals("adm_" + "T".repeat(43), token)
    }

    @Test
    fun `offline there is nothing to send to and that is not a failure`() = runTest {
        link = null
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))

        assertTrue(manager().syncPending())

        assertFalse(settings.lastSyncFailed.value)
        assertEquals(emptyList<String>(), bodies)
        assertEquals(null, settings.lastSyncEpochMs)
    }

    @Test
    fun `reports written before the account was connected stay on the phone`() = runTest {
        notBefore = 6_000
        db.dailyReports().upsert(report("2026-10-11", generatedAt = 5_000))
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 9_000))

        assertTrue(manager().syncPending())

        val root = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(1, root["reports"]!!.jsonArray.size)
        assertEquals(9_000L, settings.lastSyncEpochMs)
    }

    @Test
    fun `turning sync off sends nothing`() = runTest {
        settings.setSyncEnabled(false)
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))

        assertTrue(manager().syncPending())
        assertEquals(emptyList<String>(), bodies)
    }

    @Test
    fun `disabled sync still sends queued deletions without reports or transcripts`() = runTest {
        settings.setSyncEnabled(false)
        settings.setSyncIncludeTranscripts(true)
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))
        db.syncDeletions().enqueue(queueDeletion("gone-1"))

        assertTrue(manager().syncPending())

        assertEquals(listOf("gone-1"), sentDeletedSessions())
        val root = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(emptyList(), root["reports"]!!.jsonArray.toList())
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
        assertEquals(null, settings.lastSyncEpochMs)
    }

    @Test
    fun `disabled sync retains failed deletions and retries after reconnect`() = runTest {
        settings.setSyncEnabled(false)
        db.syncDeletions().enqueue(queueDeletion("gone-1"))
        assertFalse(manager(failWith = { throw java.io.IOException("offline") }).syncPending())
        assertEquals(listOf("gone-1"), db.syncDeletions().pendingSessionIds())
        link = null
        assertTrue(manager().syncPending())
        assertEquals(listOf("gone-1"), db.syncDeletions().pendingSessionIds())
        link = ServerLink(HostedServer.BASE_URL, "adm_" + "T".repeat(43))
        assertTrue(manager().syncPending())
        assertEquals(listOf("gone-1"), sentDeletedSessions())
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
    }

    @Test
    fun `nothing to send makes no network call`() = runTest {
        assertTrue(manager().syncPending())
        assertEquals(emptyList<String>(), bodies)
    }

    @Test
    fun `sessions with words or items are listed with the persons own name and start time`() = runTest {
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))
        for ((id, title) in listOf("named" to "Standup", "plain" to null, "empty" to "Not yet")) {
            db.sessions().register(SessionInfoEntity(id, "2026-10-12", "UTC", 1_000L, title))
        }
        db.localTranscripts().insertAll(
            listOf("named", "plain").map { line(it) },
        )

        assertTrue(manager().syncPending())

        val sessions = Json.parseToJsonElement(bodies.single()).jsonObject["reports"]!!.jsonArray
            .single().jsonObject["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("named", "plain"), sessions.map { it["sessionId"]!!.jsonPrimitive.content })
        assertEquals("Standup", sessions[0]["title"]!!.jsonPrimitive.content)
        assertFalse(sessions[1].containsKey("title"))
        assertEquals(1_000L, sessions[0]["startedAtEpochMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `a day with no sessions sends no sessions field`() = runTest {
        db.dailyReports().upsert(report("2026-10-12", generatedAt = 5_000))

        assertTrue(manager().syncPending())

        val day = Json.parseToJsonElement(bodies.single()).jsonObject["reports"]!!.jsonArray.single().jsonObject
        assertFalse(day.containsKey("sessions"))
    }

    private fun line(session: String) = LocalTranscriptLineEntity(
        lineId = "line-$session",
        sessionId = session,
        segmentId = "seg-$session",
        startEpochMs = 1_000L,
        endEpochMs = 2_000L,
        speaker = null,
        text = "words",
        confidence = null,
        provider = "test",
        model = "m",
    )
}
