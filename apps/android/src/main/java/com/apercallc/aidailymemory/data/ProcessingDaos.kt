package com.apercallc.aidailymemory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MemoryItemEntity>)

    @Query("DELETE FROM memory_item WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("UPDATE memory_item SET dismissed_at_epoch_ms = :epochMs WHERE id = :id")
    suspend fun dismiss(id: String, epochMs: Long)

    @Transaction
    suspend fun replaceForSession(sessionId: String, items: List<MemoryItemEntity>) {
        deleteForSession(sessionId)
        insertAll(items)
    }

    @Query("SELECT * FROM memory_item WHERE memory_day = :memoryDay AND dismissed_at_epoch_ms IS NULL ORDER BY extracted_at_epoch_ms")
    fun observeForDay(memoryDay: String): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_item WHERE session_id = :sessionId AND dismissed_at_epoch_ms IS NULL ORDER BY kind, extracted_at_epoch_ms")
    fun observeForSession(sessionId: String): Flow<List<MemoryItemEntity>>

    @Query("SELECT COUNT(*) FROM memory_item WHERE memory_day = :memoryDay AND kind = :kind AND dismissed_at_epoch_ms IS NULL")
    suspend fun countForDay(memoryDay: String, kind: String): Int

    @Query("SELECT * FROM memory_item WHERE session_id = :sessionId AND dismissed_at_epoch_ms IS NULL ORDER BY kind, extracted_at_epoch_ms")
    suspend fun forSession(sessionId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_item WHERE memory_day = :memoryDay AND dismissed_at_epoch_ms IS NULL ORDER BY kind, extracted_at_epoch_ms")
    suspend fun forDay(memoryDay: String): List<MemoryItemEntity>
}

@Dao
interface DailyReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: DailyReportEntity)

    @Query("SELECT * FROM daily_report WHERE memory_day = :memoryDay")
    suspend fun forDay(memoryDay: String): DailyReportEntity?

    @Query("SELECT * FROM daily_report WHERE memory_day = :memoryDay")
    fun observeForDay(memoryDay: String): Flow<DailyReportEntity?>

    @Query("SELECT * FROM daily_report ORDER BY memory_day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyReportEntity>>

    @Query("SELECT * FROM daily_report WHERE generated_at_epoch_ms > :sinceEpochMs ORDER BY memory_day")
    suspend fun since(sinceEpochMs: Long): List<DailyReportEntity>
}

@Dao
interface LocalTranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lines: List<LocalTranscriptLineEntity>)

    @Query("SELECT * FROM local_transcript_line WHERE session_id = :sessionId ORDER BY start_epoch_ms")
    fun observeForSession(sessionId: String): Flow<List<LocalTranscriptLineEntity>>

    @Query("SELECT * FROM local_transcript_line WHERE session_id = :sessionId ORDER BY start_epoch_ms")
    suspend fun forSession(sessionId: String): List<LocalTranscriptLineEntity>

    @Query("DELETE FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}

@Dao
interface ProcessingStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ProcessingStateEntity)

    @Query("SELECT * FROM processing_state WHERE session_id = :sessionId")
    suspend fun forSession(sessionId: String): ProcessingStateEntity?

    @Query("SELECT * FROM processing_state WHERE state = :state")
    suspend fun inState(state: String): List<ProcessingStateEntity>

    @Query("SELECT * FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBED') ORDER BY updated_at_epoch_ms LIMIT :limit")
    suspend fun pending(limit: Int): List<ProcessingStateEntity>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBING', 'TRANSCRIBED', 'EXTRACTING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state = 'FAILED'")
    fun observeFailedCount(): Flow<Int>
}