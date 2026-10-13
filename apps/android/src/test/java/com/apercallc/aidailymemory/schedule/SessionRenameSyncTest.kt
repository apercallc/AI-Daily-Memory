package com.apercallc.aidailymemory.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A rename on the phone has to reach the account, and the report is only sent when it changes. */
@RunWith(RobolectricTestRunner::class)
class SessionRenameSyncTest {
    private lateinit var db: CaptureDatabase
    private val renamed = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun registry() = SessionRegistry(db.sessions(), { DailyRhythm() }, onRenamed = { renamed += it })

    private suspend fun session(id: String) =
        db.sessions().register(SessionInfoEntity(id, "2026-10-12", "UTC", 1_000L, null))

    @Test
    fun `a saved rename or reset tells the hook, a refused one does not`() = runTest {
        session("s1")

        assertEquals(RenameResult.SAVED, registry().rename("s1", "Standup"))
        assertEquals(RenameResult.RESET, registry().rename("s1", "  "))
        assertEquals(RenameResult.UNKNOWN_SESSION, registry().rename("nope", "x"))
        assertEquals(RenameResult.TOO_LONG, registry().rename("s1", "x".repeat(200)))

        assertEquals(listOf("s1", "s1"), renamed)
    }

    @Test
    fun `a hook that fails does not undo the rename`() = runTest {
        session("s1")
        val registry = SessionRegistry(db.sessions(), { DailyRhythm() }, onRenamed = { error("sync scheduler down") })

        assertEquals(RenameResult.SAVED, registry.rename("s1", "Standup"))

        assertEquals("Standup", db.sessions().infoForDay("2026-10-12").single().customTitle)
    }

    @Test
    fun `touching a day makes its report newer than the sync cursor`() = runTest {
        db.dailyReports().upsert(
            DailyReportEntity("r", "2026-10-12", "UTC", "h", "s", "{}", 1_000L, "m", 1),
        )

        assertEquals(1, db.dailyReports().touch("2026-10-12", 9_000L))
        assertEquals(0, db.dailyReports().touch("2026-01-01", 9_000L))

        assertEquals(listOf("2026-10-12"), db.dailyReports().since(5_000L).map { it.memoryDay })
    }
}
