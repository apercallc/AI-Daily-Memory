package com.apercallc.aidailymemory.processing

import androidx.room.withTransaction
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportDao
import com.apercallc.aidailymemory.data.MemoryCorrectionDao
import com.apercallc.aidailymemory.data.MemoryItemDao
import com.apercallc.aidailymemory.recording.storage.MemoryRetention
import com.apercallc.aidailymemory.recording.storage.MemoryRetentionCutoff
import java.time.Instant
import java.time.ZoneId

/**
 * Applies a [MemoryRetention] setting by deleting memory items, their corrections, and daily
 * reports past the cutoff, in one transaction (TODO.md Phase 7 "retention controls"). Never
 * touches the transcript evidence those items were extracted from — only the structured,
 * user-facing layer this setting is about.
 */
class MemoryRetentionSweep(
    private val database: CaptureDatabase,
    private val memoryItemDao: MemoryItemDao,
    private val memoryCorrectionDao: MemoryCorrectionDao,
    private val dailyReportDao: DailyReportDao,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Deletes everything past [retention]'s cutoff and returns the number of memory items
     * removed (0 when the setting keeps everything, or nothing is old enough yet). Corrections
     * are deleted before the items they point at, in the same transaction, so a crash mid-sweep
     * never leaves one orphaned.
     */
    suspend fun apply(retention: MemoryRetention): Int {
        val cutoff = MemoryRetentionCutoff.cutoffDay(now(), zone(), retention) ?: return 0
        val cutoffDay = cutoff.toString()
        return database.withTransaction {
            memoryCorrectionDao.deleteForDaysOlderThan(cutoffDay)
            val deletedItems = memoryItemDao.deleteOlderThan(cutoffDay)
            dailyReportDao.deleteOlderThan(cutoffDay)
            deletedItems
        }
    }
}
