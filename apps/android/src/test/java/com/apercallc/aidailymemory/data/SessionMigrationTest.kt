package com.apercallc.aidailymemory.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.apercallc.aidailymemory.schedule.RenameResult
import com.apercallc.aidailymemory.schedule.SessionRegistry
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SessionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CaptureDatabase::class.java,
    )

    @Test
    fun `migrating to v2 registers existing sessions from their first transition`() {
        helper.createDatabase(DB, 1).use { db ->
            fun transition(revision: Long, session: String, at: Long, date: String) = db.execSQL(
                "INSERT INTO recording_transition (revision, status, session_id, problem, cause, " +
                    "occurred_at_epoch_ms, timezone, utc_offset_minutes, local_date, " +
                    "elapsed_realtime_ms, boot_id) VALUES " +
                    "($revision, 'X', '$session', NULL, 'c', $at, 'Europe/Paris', 120, '$date', 0, 'b')",
            )
            transition(1, "a", 1_000, "2026-09-21")
            transition(2, "a", 9_000, "2026-09-22")
            transition(3, "b", 5_000, "2026-09-22")
            db.execSQL(
                "INSERT INTO recording_transition (revision, status, session_id, problem, cause, " +
                    "occurred_at_epoch_ms, timezone, utc_offset_minutes, local_date, " +
                    "elapsed_realtime_ms, boot_id) VALUES " +
                    "(4, 'IDLE', NULL, NULL, 'c', 6000, 'UTC', 0, '2026-09-22', 0, 'b')",
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true, CaptureDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT session_id, memory_day, started_at_epoch_ms FROM session_info ORDER BY session_id")
                .use { cursor ->
                    val rows = buildList {
                        while (cursor.moveToNext()) {
                            add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                        }
                    }
                    assertEquals(
                        listOf(Triple("a", "2026-09-21", 1_000L), Triple("b", "2026-09-22", 5_000L)),
                        rows,
                    )
                }
        }
    }

    @Test
    fun `migrating to v3 adds speech tables and keeps existing rows`() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO session_info (session_id, memory_day, zone_id, started_at_epoch_ms, " +
                    "custom_title) VALUES ('a', '2026-09-22', 'UTC', 1000, 'Kept')",
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true, CaptureDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT custom_title FROM session_info").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Kept", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM segment_speech").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `registry fixes the memory day at start, and renames are validated`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val zone = ZoneId.of("America/Chicago")
        // 00:30 on Sept 23 with a 23:00–07:00 sleep belongs to Sept 22.
        val start = Instant.parse("2026-09-23T05:30:00Z")
        val rhythm = DailyRhythm(sleep = listOf(DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))))
        val registry = SessionRegistry(db.sessions(), { rhythm }, { start }, { zone })

        registry.register("s1")
        registry.register("s1") // idempotent

        val row = db.sessions().observeSince("2026-09-01").first().single()
        assertEquals("2026-09-22", row.memoryDay)
        assertEquals(0, row.capturedMs)
        assertNull(row.customTitle)

        assertEquals(RenameResult.SAVED, registry.rename("s1", "  Late   talk "))
        assertEquals("Late talk", db.sessions().observeSince("2026-09-01").first().single().customTitle)
        assertEquals(RenameResult.TOO_LONG, registry.rename("s1", "x".repeat(61)))
        assertEquals(RenameResult.INVALID_CHARACTERS, registry.rename("s1", "a\nb"))
        assertEquals(RenameResult.RESET, registry.rename("s1", " "))
        assertNull(db.sessions().observeSince("2026-09-01").first().single().customTitle)
        assertEquals(RenameResult.UNKNOWN_SESSION, registry.rename("nope", "Title"))
        db.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
