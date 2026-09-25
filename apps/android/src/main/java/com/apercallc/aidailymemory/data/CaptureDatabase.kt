package com.apercallc.aidailymemory.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local capture database. Lives in credential-encrypted storage (the default), so it is only
 * readable after the first unlock, which matches "no capture before first unlock" (ADR-0007).
 *
 * Every schema change bumps [version], adds a migration, and commits the exported schema.
 * Destructive fallback is never enabled: it would silently delete unacknowledged audio records.
 */
@Database(
    entities = [
        TransitionEntity::class,
        SegmentEntity::class,
        MarkerEntity::class,
        SessionInfoEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun transitions(): TransitionDao
    abstract fun segments(): SegmentDao
    abstract fun markers(): MarkerDao
    abstract fun sessions(): SessionDao

    companion object {
        const val NAME = "capture.db"

        fun open(context: Context): CaptureDatabase =
            Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds session_info and registers sessions that already exist. Their memory day is the
         * local date of their first transition (the schedule didn't exist before this version).
         * SQLite's MIN() makes the bare columns come from that first row.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_info` (" +
                        "`session_id` TEXT NOT NULL, `memory_day` TEXT NOT NULL, " +
                        "`zone_id` TEXT NOT NULL, `started_at_epoch_ms` INTEGER NOT NULL, " +
                        "`custom_title` TEXT, PRIMARY KEY(`session_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_info_memory_day` " +
                        "ON `session_info` (`memory_day`)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO session_info " +
                        "(session_id, memory_day, zone_id, started_at_epoch_ms, custom_title) " +
                        "SELECT session_id, local_date, timezone, " +
                        "MIN(occurred_at_epoch_ms), NULL " +
                        "FROM recording_transition WHERE session_id IS NOT NULL " +
                        "GROUP BY session_id",
                )
            }
        }
    }
}
