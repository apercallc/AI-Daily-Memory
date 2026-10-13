package com.apercallc.aidailymemory.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.data.SyncDeletionQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SessionDataDeletionTest {
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
    fun `active session is never deleted`() = runTest {
        db.sessions().register(sessionInfo("active"))
        var audioDeletionRequested = false
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { audioDeletionRequested = true; UserAudioDeletionResult.Deleted(0) },
            hasServerSideData = { false },
            isActive = { true },
        )

        assertEquals(SessionDataDeletionResult.SessionActive, deletion.delete("active"))
        assertEquals(false, audioDeletionRequested)
        assertEquals(1, db.sessions().observeSince("2026-10-01").first().size)
    }

    @Test
    fun `remote-copy refusal preserves session database records`() = runTest {
        db.sessions().register(sessionInfo("uploaded"))
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { UserAudioDeletionResult.RemoteCopyExists },
            hasServerSideData = { false },
            isActive = { false },
        )

        assertEquals(SessionDataDeletionResult.RemoteCopyExists, deletion.delete("uploaded"))
        assertEquals(1, db.sessions().observeSince("2026-10-01").first().size)
    }

    @Test
    fun `successful local deletion removes only the requested session`() = runTest {
        db.sessions().register(sessionInfo("delete"))
        db.sessions().register(sessionInfo("keep"))
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { UserAudioDeletionResult.Deleted(1) },
            hasServerSideData = { false },
            isActive = { false },
        )

        assertEquals(SessionDataDeletionResult.Deleted, deletion.delete("delete"))
        assertEquals(listOf("keep"), db.sessions().observeSince("2026-10-01").first().map { it.sessionId })
    }

    @Test
    fun `a successful local deletion queues the server-side purge`() = runTest {
        db.sessions().register(sessionInfo("delete"))
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { UserAudioDeletionResult.Deleted(1) },
            hasServerSideData = { false },
            isActive = { false },
            onDeleted = { sessionId ->
                db.syncDeletions().enqueue(SyncDeletionQueueEntity(sessionId, 1_000))
            },
        )

        assertEquals(SessionDataDeletionResult.Deleted, deletion.delete("delete"))
        assertEquals(listOf("delete"), db.syncDeletions().pendingSessionIds())
    }

    @Test
    fun `a refused deletion never queues a server-side purge`() = runTest {
        db.sessions().register(sessionInfo("uploaded"))
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { UserAudioDeletionResult.Deleted(1) },
            hasServerSideData = { true },
            isActive = { false },
            onDeleted = { sessionId ->
                db.syncDeletions().enqueue(SyncDeletionQueueEntity(sessionId, 1_000))
            },
        )

        assertEquals(SessionDataDeletionResult.RemoteCopyExists, deletion.delete("uploaded"))
        assertEquals(emptyList(), db.syncDeletions().pendingSessionIds())
    }

    @Test
    fun `session with server-side upload data is preserved before any audio is removed`() = runTest {
        db.sessions().register(sessionInfo("uploaded"))
        var audioDeletionRequested = false
        val deletion = SessionDataDeletion(
            sessions = db.sessions(),
            deleteAudio = { audioDeletionRequested = true; UserAudioDeletionResult.Deleted(1) },
            hasServerSideData = { true },
            isActive = { false },
        )

        assertEquals(SessionDataDeletionResult.RemoteCopyExists, deletion.delete("uploaded"))
        assertEquals(false, audioDeletionRequested)
        assertEquals(1, db.sessions().observeSince("2026-10-01").first().size)
    }

    private fun sessionInfo(id: String) = SessionInfoEntity(
        sessionId = id,
        memoryDay = "2026-10-11",
        zoneId = "America/Chicago",
        startedAtEpochMs = 1,
        customTitle = null,
    )
}
