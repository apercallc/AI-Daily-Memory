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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
