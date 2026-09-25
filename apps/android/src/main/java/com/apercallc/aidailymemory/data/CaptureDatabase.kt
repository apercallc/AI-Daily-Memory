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
        SegmentSpeechEntity::class,
        SpeechRegionEntity::class,
        ClipUploadEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun transitions(): TransitionDao
    abstract fun segments(): SegmentDao
    abstract fun markers(): MarkerDao
    abstract fun sessions(): SessionDao
    abstract fun speech(): SpeechDao
    abstract fun clips(): ClipDao

    companion object {
        const val NAME = "capture.db"

        /** Every migration, in order. Tests open old databases with exactly this list. */
        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun open(context: Context): CaptureDatabase =
            Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, NAME)
                .addMigrations(*migrations())
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
    
        /** Adds speech analysis (ADR-0015). New tables only; nothing existing changes. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `segment_speech` (" +
                        "`segment_id` TEXT NOT NULL, `analyzer` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `total_ms` INTEGER NOT NULL, " +
                        "`speech_ms` INTEGER NOT NULL, `padded_ms` INTEGER NOT NULL, " +
                        "`region_count` INTEGER NOT NULL, " +
                        "`analyzed_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`segment_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `speech_region` (" +
                        "`segment_id` TEXT NOT NULL, `start_ms` INTEGER NOT NULL, " +
                        "`end_ms` INTEGER NOT NULL, `speech_ms` INTEGER NOT NULL, " +
                        "`low_confidence` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`segment_id`, `start_ms`))",
                )
            }
        }
    
        /** Adds the clip upload queue (ADR-0015, Phase 1). New table only. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clip_upload` (" +
                        "`clip_id` TEXT NOT NULL, `segment_id` TEXT NOT NULL, " +
                        "`first_frame` INTEGER NOT NULL, `end_frame` INTEGER NOT NULL, " +
                        "`offset_ms` INTEGER NOT NULL, `duration_ms` INTEGER NOT NULL, " +
                        "`byte_size` INTEGER NOT NULL, `sha256` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "`last_error` TEXT, PRIMARY KEY(`clip_id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_clip_upload_segment_id_first_frame` " +
                        "ON `clip_upload` (`segment_id`, `first_frame`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_clip_upload_state` ON `clip_upload` (`state`)",
                )
            }
        }
    }
}
