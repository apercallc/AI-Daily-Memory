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

    /** Every item for a session, dismissed or not — reprocessing (TODO.md Phase 7) needs the
     *  dismissed ones too, to carry their dismissal forward onto whichever new item replaces
     *  them, instead of silently un-dismissing on every rerun. */
    @Query("SELECT * FROM memory_item WHERE session_id = :sessionId ORDER BY kind, extracted_at_epoch_ms")
    suspend fun allForSession(sessionId: String): List<MemoryItemEntity>

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

    /** Every still-open commitment or action, across every day (TODO.md Phase 7 "project
     *  memory"): the daily timeline already shows these per day; this feeds the durable,
     *  cross-day "People & Commitments" grouping instead. */
    @Query(
        "SELECT * FROM memory_item WHERE kind IN ('commitment', 'action') " +
            "AND dismissed_at_epoch_ms IS NULL ORDER BY extracted_at_epoch_ms DESC",
    )
    fun observeOpenCommitments(): Flow<List<MemoryItemEntity>>

    /** Deletes items for a retention sweep (TODO.md Phase 7 "retention controls"); corrections
     *  pointing at them must be deleted first by the caller, or they would orphan. */
    @Query("DELETE FROM memory_item WHERE memory_day < :cutoffDay")
    suspend fun deleteOlderThan(cutoffDay: String): Int
}

@Dao
interface MemoryCorrectionDao {
    @Insert
    suspend fun insert(correction: MemoryItemCorrectionEntity)

    /** Repoints a correction at the new item replacing the one it used to correct, so a
     *  reprocessing run (TODO.md Phase 7) never silently orphans it. */
    @Query("UPDATE memory_item_correction SET item_id = :newItemId WHERE item_id = :oldItemId")
    suspend fun reassignItemId(oldItemId: String, newItemId: String)

    @Query(
        "SELECT * FROM memory_item_correction WHERE item_id = :itemId " +
            "ORDER BY corrected_at_epoch_ms DESC LIMIT 1",
    )
    suspend fun latestForItem(itemId: String): MemoryItemCorrectionEntity?

    /** One row per corrected item: its latest correction, for batch display lookups. */
    @Query(
        "SELECT c.* FROM memory_item_correction c WHERE c.corrected_at_epoch_ms = (" +
            "SELECT MAX(c2.corrected_at_epoch_ms) FROM memory_item_correction c2 " +
            "WHERE c2.item_id = c.item_id) AND c.item_id IN (:itemIds)",
    )
    suspend fun forItems(itemIds: List<String>): List<MemoryItemCorrectionEntity>

    /** Deletes corrections for a retention sweep; must run before deleting the items they
     *  point at (TODO.md Phase 7 "retention controls"), or the delete would orphan them. */
    @Query(
        "DELETE FROM memory_item_correction WHERE item_id IN " +
            "(SELECT id FROM memory_item WHERE memory_day < :cutoffDay)",
    )
    suspend fun deleteForDaysOlderThan(cutoffDay: String): Int
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

    @Query("SELECT memory_day FROM daily_report WHERE generated_at_epoch_ms > :sinceEpochMs ORDER BY memory_day")
    fun observePendingDays(sinceEpochMs: Long): Flow<List<String>>

    /** Marks a day's report as changed so the next sync sends it again (a rename, say). */
    @Query("UPDATE daily_report SET generated_at_epoch_ms = :epochMs WHERE memory_day = :memoryDay")
    suspend fun touch(memoryDay: String, epochMs: Long): Int

    /** Deletes reports for a retention sweep (TODO.md Phase 7 "retention controls"). */
    @Query("DELETE FROM daily_report WHERE memory_day < :cutoffDay")
    suspend fun deleteOlderThan(cutoffDay: String): Int
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

    @Query("DELETE FROM local_transcript_line WHERE segment_id = :segmentId")
    suspend fun deleteForSegment(segmentId: String)

    @Transaction
    suspend fun replaceForSegment(segmentId: String, lines: List<LocalTranscriptLineEntity>) {
        deleteForSegment(segmentId)
        insertAll(lines)
    }

    @Query("SELECT COUNT(*) FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    /**
     * Display-ready rows for a session's locally produced transcript, in the capture
     * timezone: the segment's own if it is still known, else the session's registered zone
     * (older rows, or a deleted segment). Already plaintext (ADR-0020); unlike [TranscriptDao]
     * there is nothing to decrypt.
     */
    @Query(
        """
        SELECT l.line_id AS lineId, l.start_epoch_ms AS startEpochMs, l.end_epoch_ms AS endEpochMs,
            l.speaker AS speaker, l.text AS text,
            COALESCE(s.start_timezone, i.zone_id) AS zoneId
        FROM local_transcript_line l
        LEFT JOIN audio_segment s ON s.segment_id = l.segment_id
        LEFT JOIN session_info i ON i.session_id = l.session_id
        WHERE l.session_id = :sessionId
        ORDER BY l.start_epoch_ms, l.line_id
        """,
    )
    fun observeDisplayLinesForSession(sessionId: String): Flow<List<LocalTranscriptDisplayLine>>

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

    @Query("SELECT * FROM processing_state WHERE session_id = :sessionId")
    fun observeForSession(sessionId: String): Flow<ProcessingStateEntity?>

    @Query("SELECT * FROM processing_state WHERE state = :state")
    suspend fun inState(state: String): List<ProcessingStateEntity>

    @Query("SELECT * FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBING', 'TRANSCRIBED', 'EXTRACTING') ORDER BY updated_at_epoch_ms LIMIT :limit")
    suspend fun pending(limit: Int): List<ProcessingStateEntity>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state IN ('PENDING', 'TRANSCRIBING', 'TRANSCRIBED', 'EXTRACTING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processing_state WHERE state = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query(
        "SELECT last_error FROM processing_state WHERE state = 'FAILED' " +
            "AND last_error IS NOT NULL ORDER BY updated_at_epoch_ms DESC, session_id DESC LIMIT 1",
    )
    fun observeMostRecentFailedError(): Flow<String?>

    @Query(
        "UPDATE processing_state SET state = 'PENDING', last_error = NULL, retry_attempts = 0, " +
            "updated_at_epoch_ms = :nowEpochMs WHERE state = 'FAILED'",
    )
    suspend fun retryFailed(nowEpochMs: Long): Int
}

@Dao
interface ProviderUsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRow(sample: ProviderUsageEntity)

    @Query("DELETE FROM provider_usage WHERE recorded_at_epoch_ms < :beforeEpochMs")
    suspend fun deleteOlderThan(beforeEpochMs: Long)

    @Transaction
    suspend fun record(sample: ProviderUsageEntity, nowEpochMs: Long) {
        deleteOlderThan(nowEpochMs - RETENTION_MS)
        insertRow(sample)
    }

    @Query("SELECT * FROM provider_usage WHERE recorded_at_epoch_ms >= :sinceEpochMs ORDER BY recorded_at_epoch_ms")
    fun observeSince(sinceEpochMs: Long): Flow<List<ProviderUsageEntity>>

    @Query("DELETE FROM provider_usage WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    companion object {
        const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
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
