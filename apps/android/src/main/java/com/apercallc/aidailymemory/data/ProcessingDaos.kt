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

    /** Marks a day's report as changed so the next sync sends it again (a rename, say). */
    @Query("UPDATE daily_report SET generated_at_epoch_ms = :epochMs WHERE memory_day = :memoryDay")
    suspend fun touch(memoryDay: String, epochMs: Long): Int
}

@Dao
interface LocalTranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lines: List<LocalTranscriptLineEntity>)

    @Query("SELECT * FROM local_transcript_line WHERE session_id = :sessionId ORDER BY start_epoch_ms")
    fun observeForSession(sessionId: String): Flow<List<LocalTranscriptLineEntity>>

    @Query("SELECT * FROM local_transcript_line WHERE session_id = :sessionId ORDER BY start_epoch_ms")
    suspend fun forSession(sessionId: String): List<LocalTranscriptLineEntity>

    @Query("SELECT * FROM local_transcript_line WHERE session_id IN (:sessionIds) ORDER BY start_epoch_ms")
    suspend fun forSessions(sessionIds: List<String>): List<LocalTranscriptLineEntity>

    @Query("DELETE FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

}

@Dao
interface ProcessingStateDao {
    /** Queues eligible completed sessions once, after every sealed segment has been analyzed. */
    @Query(
        """
        INSERT OR IGNORE INTO processing_state
            (session_id, state, audio_deleted, last_error, updated_at_epoch_ms)
        SELECT DISTINCT s.session_id, 'PENDING', 0, NULL, :nowEpochMs
        FROM audio_segment s
        WHERE s.state = 'SEALED'
            AND EXISTS (
                SELECT 1 FROM segment_speech ready
                WHERE ready.segment_id = s.segment_id
                    AND ready.analyzer = :analyzer
                    AND ready.outcome = 'OK'
                    AND ready.speech_ms > 0
            )
            AND EXISTS (
                SELECT 1 FROM recording_transition completed
                WHERE completed.session_id = s.session_id
                    AND completed.status = 'COMPLETED'
                    AND completed.revision = (
                        SELECT MAX(latest.revision) FROM recording_transition latest
                        WHERE latest.session_id = s.session_id
                    )
            )
            AND NOT EXISTS (
                SELECT 1 FROM audio_segment unanalyzed
                WHERE unanalyzed.session_id = s.session_id
                    AND unanalyzed.state = 'SEALED'
                    AND NOT EXISTS (
                        SELECT 1 FROM segment_speech analyzed
                        WHERE analyzed.segment_id = unanalyzed.segment_id
                            AND analyzed.analyzer = :analyzer
                    )
            )
        """,
    )
    suspend fun enqueueReadySessions(analyzer: String, nowEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ProcessingStateEntity)

    @Query("SELECT * FROM processing_state WHERE session_id = :sessionId")
    suspend fun forSession(sessionId: String): ProcessingStateEntity?

    @Query("SELECT * FROM processing_state WHERE state = :state")
    suspend fun inState(state: String): List<ProcessingStateEntity>

    @Query("SELECT * FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBING', 'TRANSCRIBED', 'EXTRACTING') ORDER BY updated_at_epoch_ms LIMIT :limit")
    suspend fun pending(limit: Int): List<ProcessingStateEntity>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBING', 'TRANSCRIBED', 'EXTRACTING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("UPDATE processing_state SET state = 'PENDING', last_error = NULL, updated_at_epoch_ms = :nowEpochMs WHERE state = 'FAILED'")
    suspend fun retryFailed(nowEpochMs: Long): Int
}

/**
 * Pending server-side deletions for the sync target (ADR-0020). Enqueued on local session
 * deletion, drained only after a successful sync POST.
 */
@Dao
interface SyncDeletionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: SyncDeletionQueueEntity)

    @Query("SELECT session_id FROM sync_deletion_queue ORDER BY queued_at_epoch_ms")
    suspend fun pendingSessionIds(): List<String>

    @Query("DELETE FROM sync_deletion_queue WHERE session_id IN (:sessionIds)")
    suspend fun clear(sessionIds: List<String>)

    @Query("SELECT COUNT(*) FROM sync_deletion_queue")
    fun observeCount(): Flow<Int>
}