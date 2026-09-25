package com.apercallc.aidailymemory.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local capture database. Lives in credential-encrypted storage (the default), so it is only
 * readable after the first unlock, which matches "no capture before first unlock" (ADR-0007).
 *
 * Every schema change bumps [version], adds a migration, and commits the exported schema.
 * Destructive fallback is never enabled: it would silently delete unacknowledged audio records.
 */
@Database(
    entities = [TransitionEntity::class, SegmentEntity::class, MarkerEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun transitions(): TransitionDao
    abstract fun segments(): SegmentDao
    abstract fun markers(): MarkerDao

    companion object {
        const val NAME = "capture.db"

        fun open(context: Context): CaptureDatabase =
            Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, NAME)
                .build()
    }
}
