package com.apercallc.aidailymemory.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Migration safety net from the first entity (TODO.md Phase 0). Each new schema version adds
 * a test that creates the previous version with real rows, runs the migration, and checks the
 * rows survived; losing unacknowledged segment records would lose audio.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CaptureDatabase::class.java,
    )

    @Test
    fun `a v1 database migrates to the current schema and keeps its rows`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO recording_transition (revision, status, session_id, problem, cause, " +
                    "occurred_at_epoch_ms, timezone, utc_offset_minutes, local_date, " +
                    "elapsed_realtime_ms, boot_id) VALUES (1, 'STARTING', 's', NULL, " +
                    "'command:Start', 0, 'UTC', 0, '1970-01-01', 0, 'b')",
            )
            close()
        }

        // Opening with the current entities (and the production migrations) validates the
        // migrated schema against them.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CaptureDatabase::class.java,
            TEST_DB,
        ).addMigrations(*CaptureDatabase.migrations()).allowMainThreadQueries().build()
        val row = db.openHelper.readableDatabase
            .query("SELECT status FROM recording_transition WHERE revision = 1")
        row.use {
            it.moveToFirst()
            assertEquals("STARTING", it.getString(0))
        }
        db.close()
    }

    @Test
    fun `a v8 processing row migrates with empty segment progress`() {
        helper.createDatabase(TEST_DB_V8, 8).apply {
            execSQL(
                "INSERT INTO processing_state " +
                    "(session_id, state, audio_deleted, last_error, updated_at_epoch_ms) " +
                    "VALUES ('session-v8', 'PENDING', 0, NULL, 12)",
            )
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CaptureDatabase::class.java,
            TEST_DB_V8,
        ).addMigrations(*CaptureDatabase.migrations()).allowMainThreadQueries().build()
        db.openHelper.readableDatabase.query(
            "SELECT state, completed_segments, total_segments, processing_time_ms " +
                "FROM processing_state WHERE session_id = 'session-v8'",
        ).use {
            it.moveToFirst()
            assertEquals("PENDING", it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
            assertEquals(0, it.getLong(3))
        }
        db.close()
    }

    @Test
    fun `a v9 processing row migrates with a fresh retry budget`() {
        helper.createDatabase(TEST_DB_V9, 9).apply {
            execSQL(
                "INSERT INTO processing_state (session_id, state, audio_deleted, last_error, " +
                    "updated_at_epoch_ms, completed_segments, total_segments, processing_time_ms) " +
                    "VALUES ('session-v9', 'PENDING', 0, NULL, 12, 1, 2, 500)",
            )
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CaptureDatabase::class.java,
            TEST_DB_V9,
        ).addMigrations(*CaptureDatabase.migrations()).allowMainThreadQueries().build()
        db.openHelper.readableDatabase.query(
            "SELECT completed_segments, retry_attempts FROM processing_state WHERE session_id = 'session-v9'",
        ).use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
            assertEquals(0, it.getInt(1))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_V8 = "migration-v8-to-v9-test.db"
        const val TEST_DB_V9 = "migration-v9-to-v10-test.db"
    }
}
