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
        ClipPlanEntity::class,
        TranscriptEntity::class,
        TranscriptSpanEntity::class,
        MemoryItemEntity::class,
        DailyReportEntity::class,
        LocalTranscriptLineEntity::class,
        ProcessingStateEntity::class,
        SyncDeletionQueueEntity::class,
        ProviderUsageEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun transitions(): TransitionDao
    abstract fun segments(): SegmentDao
    abstract fun markers(): MarkerDao
    abstract fun sessions(): SessionDao
    abstract fun speech(): SpeechDao
    abstract fun clips(): ClipDao
    abstract fun transcripts(): TranscriptDao
    abstract fun memoryItems(): MemoryItemDao
    abstract fun dailyReports(): DailyReportDao
    abstract fun localTranscripts(): LocalTranscriptDao
    abstract fun processingState(): ProcessingStateDao
    abstract fun syncDeletions(): SyncDeletionDao
    abstract fun providerUsage(): ProviderUsageDao

    companion object {
        const val NAME = "capture.db"

        /** Every migration, in order. Tests open old databases with exactly this list. */
        fun migrations(): Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
        )

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
    
        /** Adds the clip upload queue and plan outcomes (ADR-0015, Phase 1). New tables only. */
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
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clip_plan` (" +
                        "`segment_id` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                        "`planned_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`segment_id`))",
                )
            }
        }
    
        /** Adds delivered transcripts (ADR-0014). New tables only. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transcript` (" +
                        "`upload_id` TEXT NOT NULL, `segment_id` TEXT NOT NULL, " +
                        "`offset_ms` INTEGER NOT NULL, `provider` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, `language` TEXT, " +
                        "`received_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`upload_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcript_segment_id` ON `transcript` (`segment_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transcript_span` (" +
                        "`upload_id` TEXT NOT NULL, `idx` INTEGER NOT NULL, " +
                        "`start_ms` INTEGER NOT NULL, `end_ms` INTEGER NOT NULL, " +
                        "`sealed` BLOB NOT NULL, `confidence` REAL, " +
                        "PRIMARY KEY(`upload_id`, `idx`))",
                )
            }
        }

        /** Adds on-device processing tables (ADR-0020): memory items, daily reports, local
         *  transcript lines, and processing state. New tables only; nothing existing changes. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_item` (" +
                        "`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                        "`memory_day` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `confidence` TEXT NOT NULL, " +
                        "`quote` TEXT, `owner` TEXT, `due` TEXT, " +
                        "`direction` TEXT, `counterpart` TEXT, " +
                        "`extraction_model` TEXT NOT NULL, `extraction_version` INTEGER NOT NULL, " +
                        "`evidence_epoch_ms` INTEGER, `dismissed_at_epoch_ms` INTEGER, " +
                        "`extracted_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_item_session_id` ON `memory_item` (`session_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_item_memory_day` ON `memory_item` (`memory_day`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_report` (" +
                        "`id` TEXT NOT NULL, `memory_day` TEXT NOT NULL, " +
                        "`zone_id` TEXT NOT NULL, `headline` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, `report_json` TEXT NOT NULL, " +
                        "`generated_at_epoch_ms` INTEGER NOT NULL, " +
                        "`extraction_model` TEXT NOT NULL, `extraction_version` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_report_memory_day` ON `daily_report` (`memory_day`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_transcript_line` (" +
                        "`line_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                        "`segment_id` TEXT NOT NULL, `start_epoch_ms` INTEGER NOT NULL, " +
                        "`end_epoch_ms` INTEGER NOT NULL, `speaker` TEXT, " +
                        "`text` TEXT NOT NULL, `confidence` REAL, " +
                        "`provider` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "PRIMARY KEY(`line_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_transcript_line_session_id` ON `local_transcript_line` (`session_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_transcript_line_session_id_start_epoch_ms` " +
                        "ON `local_transcript_line` (`session_id`, `start_epoch_ms`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `processing_state` (" +
                        "`session_id` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                        "`audio_deleted` INTEGER NOT NULL, `last_error` TEXT, " +
                        "`updated_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`session_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_processing_state_state` ON `processing_state` (`state`)",
                )
            }
        }

        /**
         * Adds the sync deletion queue (ADR-0020 deletion propagation): session ids deleted
         * locally that must be purged from the sync target on the next successful sync.
         * New table only; nothing existing changes.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_deletion_queue` (" +
                        "`session_id` TEXT NOT NULL, `queued_at_epoch_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`session_id`))",
                )
            }
        }

        /** Content-free local provider usage for cost estimates; removed with session data. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_usage` (" +
                        "`request_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                        "`stage` TEXT NOT NULL, `provider_id` TEXT, `model` TEXT NOT NULL, " +
                        "`request_audio_ms` INTEGER NOT NULL, `session_audio_ms` INTEGER NOT NULL, " +
                        "`input_tokens` INTEGER, `output_tokens` INTEGER, " +
                        "`recorded_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`request_id`), " +
                        "FOREIGN KEY(`session_id`) REFERENCES `session_info`(`session_id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_provider_usage_recorded_at_epoch_ms` " +
                        "ON `provider_usage` (`recorded_at_epoch_ms`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_provider_usage_session_id` " +
                        "ON `provider_usage` (`session_id`)",
                )
            }
        }

        /** Stores per-session segment progress so long transcripts survive interruption. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `processing_state` ADD COLUMN `completed_segments` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `processing_state` ADD COLUMN `total_segments` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `processing_state` ADD COLUMN `processing_time_ms` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Bounds retryable-failure retries: a durable per-session counter, reset by manual retry. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `processing_state` ADD COLUMN `retry_attempts` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
