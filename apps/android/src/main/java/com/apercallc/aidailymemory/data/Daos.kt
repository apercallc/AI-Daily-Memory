package com.apercallc.aidailymemory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransitionDao {
    /** Fails on a duplicate revision: two writers for one journal is a bug, never an upsert. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(entity: TransitionEntity)

    @Query("SELECT * FROM recording_transition ORDER BY revision DESC LIMIT 1")
    suspend fun latest(): TransitionEntity?

    @Query("SELECT * FROM recording_transition WHERE session_id = :sessionId ORDER BY revision")
    suspend fun forSession(sessionId: String): List<TransitionEntity>
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SegmentEntity)

    @Update
    suspend fun update(entity: SegmentEntity)

    @Query("SELECT * FROM audio_segment WHERE segment_id = :segmentId")
    suspend fun get(segmentId: String): SegmentEntity?

    @Query("SELECT * FROM audio_segment WHERE session_id = :sessionId ORDER BY sequence")
    fun observeSession(sessionId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM audio_segment WHERE session_id = :sessionId ORDER BY sequence")
    suspend fun forSession(sessionId: String): List<SegmentEntity>

    @Query("SELECT * FROM audio_segment WHERE state = :state")
    suspend fun inState(state: String): List<SegmentEntity>

    /** Next free sequence for a session; sequences are dense and start at 0. */
    @Query("SELECT COALESCE(MAX(sequence) + 1, 0) FROM audio_segment WHERE session_id = :sessionId")
    suspend fun nextSequence(sessionId: String): Int

    @Query(
        "SELECT COALESCE(SUM(byte_size), 0) FROM audio_segment " +
            "WHERE state IN ('SEALED', 'UPLOADED')",
    )
    suspend fun pendingBytes(): Long

    /** Bytes the device still holds that the server has not acknowledged. */
    @Query(
        "SELECT COALESCE(SUM(byte_size), 0) FROM audio_segment " +
            "WHERE state IN ('SEALED', 'UPLOADED')",
    )
    fun observePendingBytes(): Flow<Long>
}

@Dao
interface SessionDao {
    /** Registers a session once; later calls keep the first memory day and any rename. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun register(entity: SessionInfoEntity)

    /**
     * A delayed controller observer must not recreate metadata after user deletion. Check
     * the journal and insert in the same transaction as deletion's competing transaction.
     */
    @Transaction
    suspend fun registerIfJournaled(entity: SessionInfoEntity) {
        if (hasJournalEvidence(entity.sessionId)) register(entity)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM recording_transition WHERE session_id = :sessionId)")
    suspend fun hasJournalEvidence(sessionId: String): Boolean

    /** @return rows updated (0 if the session isn't registered). */
    @Query("UPDATE session_info SET custom_title = :title WHERE session_id = :sessionId")
    suspend fun setTitle(sessionId: String, title: String?): Int

    /**
     * Removes audio evidence, transcript, memory items, processing state, and the day-level
     * report after files have been deleted successfully. The day report is removed because it
     * may summarize the deleted session; remaining sessions' individual memories stay intact.
     * File cleanup must happen first so a failure never hides undeleted audio behind missing
     * database rows.
     */
    @Transaction
    suspend fun deleteSession(sessionId: String) {
        val memoryDay = memoryDayForSession(sessionId)
        // Same transaction as local deletion: process death cannot lose the remote purge.
        // Keep this even while disconnected or sync is off; neither revokes a deletion request.
        enqueueSyncDeletion(SyncDeletionQueueEntity(sessionId, System.currentTimeMillis()))
        deleteTranscriptSpans(sessionId)
        deleteTranscripts(sessionId)
        deleteLocalTranscriptLines(sessionId)
        deleteMemoryItems(sessionId)
        deleteProcessingState(sessionId)
        deleteProviderUsage(sessionId)
        if (memoryDay != null) deleteDailyReport(memoryDay)
        deleteClips(sessionId)
        deleteClipPlans(sessionId)
        deleteSpeechRegions(sessionId)
        deleteSpeechAnalysis(sessionId)
        deleteSegments(sessionId)
        deleteMarkers(sessionId)
        deleteTransitions(sessionId)
        deleteSessionInfo(sessionId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueSyncDeletion(entry: SyncDeletionQueueEntity)

    @Query("SELECT session_id FROM session_info WHERE memory_day = :memoryDay")
    suspend fun sessionIdsForDay(memoryDay: String): List<String>

    @Query("SELECT * FROM session_info WHERE memory_day = :memoryDay ORDER BY started_at_epoch_ms")
    suspend fun infoForDay(memoryDay: String): List<SessionInfoEntity>

    @Query("SELECT memory_day FROM session_info WHERE session_id = :sessionId")
    suspend fun memoryDayForSession(sessionId: String): String?

    @Query("DELETE FROM local_transcript_line WHERE session_id = :sessionId")
    suspend fun deleteLocalTranscriptLines(sessionId: String)

    @Query("DELETE FROM memory_item WHERE session_id = :sessionId")
    suspend fun deleteMemoryItems(sessionId: String)

    @Query("DELETE FROM processing_state WHERE session_id = :sessionId")
    suspend fun deleteProcessingState(sessionId: String)

    @Query("DELETE FROM provider_usage WHERE session_id = :sessionId")
    suspend fun deleteProviderUsage(sessionId: String)

    @Query("DELETE FROM daily_report WHERE memory_day = :memoryDay")
    suspend fun deleteDailyReport(memoryDay: String)

    @Query(
        "DELETE FROM transcript_span WHERE upload_id IN (" +
            "SELECT t.upload_id FROM transcript t JOIN audio_segment s " +
            "ON s.segment_id = t.segment_id WHERE s.session_id = :sessionId)",
    )
    suspend fun deleteTranscriptSpans(sessionId: String)

    @Query(
        "DELETE FROM transcript WHERE segment_id IN " +
            "(SELECT segment_id FROM audio_segment WHERE session_id = :sessionId)",
    )
    suspend fun deleteTranscripts(sessionId: String)

    @Query(
        "DELETE FROM clip_upload WHERE segment_id IN " +
            "(SELECT segment_id FROM audio_segment WHERE session_id = :sessionId)",
    )
    suspend fun deleteClips(sessionId: String)

    @Query(
        "DELETE FROM clip_plan WHERE segment_id IN " +
            "(SELECT segment_id FROM audio_segment WHERE session_id = :sessionId)",
    )
    suspend fun deleteClipPlans(sessionId: String)

    @Query(
        "DELETE FROM speech_region WHERE segment_id IN " +
            "(SELECT segment_id FROM audio_segment WHERE session_id = :sessionId)",
    )
    suspend fun deleteSpeechRegions(sessionId: String)

    @Query(
        "DELETE FROM segment_speech WHERE segment_id IN " +
            "(SELECT segment_id FROM audio_segment WHERE session_id = :sessionId)",
    )
    suspend fun deleteSpeechAnalysis(sessionId: String)

    @Query("DELETE FROM audio_segment WHERE session_id = :sessionId")
    suspend fun deleteSegments(sessionId: String)

    @Query("DELETE FROM capture_marker WHERE session_id = :sessionId")
    suspend fun deleteMarkers(sessionId: String)

    @Query("DELETE FROM recording_transition WHERE session_id = :sessionId")
    suspend fun deleteTransitions(sessionId: String)

    @Query("DELETE FROM session_info WHERE session_id = :sessionId")
    suspend fun deleteSessionInfo(sessionId: String)

    @Query(
        """
        SELECT i.session_id AS sessionId, i.memory_day AS memoryDay, i.zone_id AS zoneId,
            i.started_at_epoch_ms AS startedAtEpochMs, i.custom_title AS customTitle,
            (SELECT MAX(t.occurred_at_epoch_ms) FROM recording_transition t
                WHERE t.session_id = i.session_id) AS lastActivityEpochMs,
            (SELECT COALESCE(SUM(s.duration_ms), 0) FROM audio_segment s
                WHERE s.session_id = i.session_id
                AND s.state IN ('SEALED', 'UPLOADED', 'ACKNOWLEDGED', 'DELETED')) AS capturedMs,
            (SELECT COALESCE(SUM(a.total_ms), 0) FROM segment_speech a
                JOIN audio_segment s ON s.segment_id = a.segment_id
                WHERE s.session_id = i.session_id AND a.outcome = 'OK') AS analyzedMs,
            (SELECT COALESCE(SUM(a.speech_ms), 0) FROM segment_speech a
                JOIN audio_segment s ON s.segment_id = a.segment_id
                WHERE s.session_id = i.session_id AND a.outcome = 'OK') AS speechMs
        FROM session_info i
        WHERE i.memory_day >= :sinceDay
        ORDER BY i.started_at_epoch_ms DESC
        """,
    )
    fun observeSince(sinceDay: String): Flow<List<SessionRow>>
}

@Dao
interface MarkerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MarkerEntity)

    @Query(
        "SELECT * FROM capture_marker WHERE session_id = :sessionId AND kind = :kind " +
            "ORDER BY occurred_at_epoch_ms DESC LIMIT 1",
    )
    suspend fun latest(sessionId: String, kind: String): MarkerEntity?

    @Query("SELECT COUNT(*) FROM capture_marker WHERE session_id = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>
}

@Dao
interface SpeechDao {
    /**
     * Sealed segments not yet analyzed by [analyzer], oldest first. Audio already deleted by
     * retention can't be analyzed, so DELETED and QUARANTINED rows are skipped.
     */
    @Query(
        """
        SELECT s.* FROM audio_segment s
        LEFT JOIN segment_speech a ON a.segment_id = s.segment_id AND a.analyzer = :analyzer
        WHERE a.segment_id IS NULL AND s.state IN ('SEALED', 'UPLOADED', 'ACKNOWLEDGED')
        ORDER BY s.start_occurred_at_epoch_ms, s.sequence
        LIMIT :limit
        """,
    )
    suspend fun pending(analyzer: String, limit: Int): List<SegmentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM audio_segment s
        LEFT JOIN segment_speech a ON a.segment_id = s.segment_id AND a.analyzer = :analyzer
        WHERE a.segment_id IS NULL AND s.state IN ('SEALED', 'UPLOADED', 'ACKNOWLEDGED')
        """,
    )
    suspend fun pendingCount(analyzer: String): Int

    /** Replaces any earlier analysis of the segment (a new analyzer version) atomically. */
    @Transaction
    suspend fun save(analysis: SegmentSpeechEntity, regions: List<SpeechRegionEntity>) {
        deleteRegions(analysis.segmentId)
        upsert(analysis)
        insertRegions(regions)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: SegmentSpeechEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRegions(regions: List<SpeechRegionEntity>)

    @Query("DELETE FROM speech_region WHERE segment_id = :segmentId")
    suspend fun deleteRegions(segmentId: String)

    @Query("SELECT * FROM segment_speech WHERE segment_id = :segmentId")
    suspend fun analysis(segmentId: String): SegmentSpeechEntity?

    @Query("SELECT * FROM speech_region WHERE segment_id = :segmentId ORDER BY start_ms")
    suspend fun regions(segmentId: String): List<SpeechRegionEntity>

    @Query(
        """
        SELECT COALESCE(SUM(total_ms), 0) AS analyzedMs, COALESCE(SUM(speech_ms), 0) AS speechMs,
            COALESCE(SUM(padded_ms), 0) AS paddedMs
        FROM segment_speech WHERE outcome = 'OK' AND analyzed_at_epoch_ms >= :sinceEpochMs
        """,
    )
    suspend fun totalsSince(sinceEpochMs: Long): SpeechTotals
}

@Dao
interface ClipDao {
    /**
     * Analyzed segments captured since [sinceEpochMs] that have no plan yet, oldest first.
     * [sinceEpochMs] keeps audio recorded before the account was linked on the phone unless
     * the user chose to include it.
     */
    @Query(
        """
        SELECT s.* FROM audio_segment s
        JOIN segment_speech a ON a.segment_id = s.segment_id AND a.outcome = 'OK'
        WHERE s.state = 'SEALED' AND s.start_occurred_at_epoch_ms >= :sinceEpochMs
            AND NOT EXISTS (SELECT 1 FROM clip_plan p WHERE p.segment_id = s.segment_id)
        ORDER BY s.start_occurred_at_epoch_ms, s.sequence
        LIMIT :limit
        """,
    )
    suspend fun unplanned(sinceEpochMs: Long, limit: Int): List<SegmentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM audio_segment s
        JOIN segment_speech a ON a.segment_id = s.segment_id AND a.outcome = 'OK'
        WHERE s.state = 'SEALED' AND s.start_occurred_at_epoch_ms >= :sinceEpochMs
            AND NOT EXISTS (SELECT 1 FROM clip_plan p WHERE p.segment_id = s.segment_id)
        """,
    )
    suspend fun unplannedCount(sinceEpochMs: Long): Int

    /** Records a segment's plan and its clips together; planning twice keeps the first. */
    @Transaction
    suspend fun plan(plan: ClipPlanEntity, clips: List<ClipUploadEntity>) {
        insertPlan(plan)
        insertClips(clips)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlan(plan: ClipPlanEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClips(clips: List<ClipUploadEntity>)

    @Query("SELECT * FROM clip_plan WHERE segment_id = :segmentId")
    suspend fun planFor(segmentId: String): ClipPlanEntity?

    /**
     * Clips waiting to upload, from audio captured at or after [sinceEpochMs] only: a clip
     * planned under an earlier link (with its own consent) never uploads under a new one.
     */
    @Query(
        """
        SELECT c.* FROM clip_upload c
        JOIN audio_segment s ON s.segment_id = c.segment_id
        WHERE c.state = 'PENDING' AND s.start_occurred_at_epoch_ms >= :sinceEpochMs
        ORDER BY c.segment_id, c.first_frame
        LIMIT :limit
        """,
    )
    suspend fun pending(sinceEpochMs: Long, limit: Int): List<ClipUploadEntity>

    /** Received clips whose transcripts haven't arrived, oldest first: checked with the server. */
    @Query(
        """
        SELECT c.* FROM clip_upload c
        WHERE c.state = 'RECEIVED'
            AND NOT EXISTS (SELECT 1 FROM transcript t WHERE t.upload_id = c.clip_id)
        ORDER BY c.clip_id
        LIMIT :limit
        """,
    )
    suspend fun awaiting(limit: Int): List<ClipUploadEntity>

    /** The server already had this clip under [newId] (it lost our old id): use its id. */
    @Query(
        "UPDATE clip_upload SET clip_id = :newId, state = 'RECEIVED', last_error = NULL " +
            "WHERE clip_id = :oldId",
    )
    suspend fun adopt(oldId: String, newId: String)

    /** Stop trying: shown to the user, who can retry. The audio stays on the phone. */
    @Query("UPDATE clip_upload SET state = 'FAILED', last_error = :reason WHERE clip_id = :clipId")
    suspend fun giveUp(clipId: String, reason: String)

    @Query("SELECT * FROM clip_upload WHERE clip_id = :clipId")
    suspend fun byId(clipId: String): ClipUploadEntity?

    /** The server no longer has this clip's upload: upload it again under a new id. */
    @Query(
        "UPDATE clip_upload SET clip_id = :newId, state = 'PENDING', attempts = attempts + 1, " +
            "last_error = 'server_lost' WHERE clip_id = :oldId AND state = 'RECEIVED'",
    )
    suspend fun requeue(oldId: String, newId: String)

    @Update
    suspend fun update(clip: ClipUploadEntity)

    /** A new upload id for the same clip (the server gave up on the old one). */
    @Query(
        "UPDATE clip_upload SET clip_id = :newId, attempts = attempts + 1, " +
            "last_error = 'server_failed' WHERE clip_id = :oldId",
    )
    suspend fun reissue(oldId: String, newId: String)

    @Query("SELECT * FROM clip_upload WHERE segment_id = :segmentId ORDER BY first_frame")
    suspend fun forSegment(segmentId: String): List<ClipUploadEntity>

    /** Any planned/upload row may be in flight or already represented on the server. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM clip_upload c JOIN audio_segment s " +
            "ON s.segment_id = c.segment_id WHERE s.session_id = :sessionId)",
    )
    suspend fun hasUploadRowsForSession(sessionId: String): Boolean

    /** Planned segments whose every clip the server has verified: ready for UploadCompleted. */
    @Query(
        """
        SELECT s.* FROM audio_segment s
        JOIN clip_plan p ON p.segment_id = s.segment_id AND p.outcome = 'PLANNED'
        WHERE s.state = 'SEALED'
            AND NOT EXISTS (
                SELECT 1 FROM clip_upload c WHERE c.segment_id = s.segment_id AND c.state != 'RECEIVED'
            )
        """,
    )
    suspend fun fullyUploaded(): List<SegmentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM clip_upload c
        JOIN audio_segment s ON s.segment_id = c.segment_id
        WHERE c.state = 'PENDING' AND s.start_occurred_at_epoch_ms >= :sinceEpochMs
        """,
    )
    suspend fun pendingCount(sinceEpochMs: Long): Int

    /**
     * What the user should know about: clips that gave up, segments that couldn't be read, and
     * clips waiting that the current link's consent excludes (planned under an earlier one).
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM clip_upload WHERE state = 'FAILED')
            + (SELECT COUNT(*) FROM clip_plan WHERE outcome = 'UNREADABLE')
            + (SELECT COUNT(*) FROM clip_upload c JOIN audio_segment s ON s.segment_id = c.segment_id
                WHERE c.state = 'PENDING' AND s.start_occurred_at_epoch_ms < :sinceEpochMs)
        """,
    )
    fun observeProblems(sinceEpochMs: Long): Flow<Int>

    /** The user asked to try again: failed clips restart, unreadable segments are re-planned. */
    @Transaction
    suspend fun retryProblems() {
        resetFailedClips()
        forgetUnreadablePlans()
    }

    @Query("UPDATE clip_upload SET state = 'PENDING', attempts = 0, last_error = NULL WHERE state = 'FAILED'")
    suspend fun resetFailedClips()

    @Query("DELETE FROM clip_plan WHERE outcome = 'UNREADABLE'")
    suspend fun forgetUnreadablePlans()
}

@Dao
interface TranscriptDao {
    /** Stores a delivered transcript once; a redelivery of the same upload changes nothing. */
    @Transaction
    suspend fun store(transcript: TranscriptEntity, spans: List<TranscriptSpanEntity>) {
        if (insert(transcript) != -1L) insertSpans(spans)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transcript: TranscriptEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpans(spans: List<TranscriptSpanEntity>)

    @Query("SELECT COUNT(*) FROM transcript WHERE upload_id = :uploadId")
    suspend fun count(uploadId: String): Int

    /**
     * Uploaded segments whose every clip now has its transcript on this phone: their audio has
     * served its purpose, so they can be acknowledged (ADR-0014 invariant).
     */
    @Query(
        """
        SELECT s.* FROM audio_segment s
        JOIN clip_plan p ON p.segment_id = s.segment_id AND p.outcome = 'PLANNED'
        WHERE s.state = 'UPLOADED'
            AND NOT EXISTS (
                SELECT 1 FROM clip_upload c WHERE c.segment_id = s.segment_id
                    AND NOT EXISTS (SELECT 1 FROM transcript t WHERE t.upload_id = c.clip_id)
            )
        """,
    )
    suspend fun segmentsWithAllTranscripts(): List<SegmentEntity>

    /** Clips the server has but whose transcripts haven't arrived yet. */
    @Query(
        """
        SELECT COUNT(*) FROM clip_upload c
        WHERE c.state = 'RECEIVED'
            AND NOT EXISTS (SELECT 1 FROM transcript t WHERE t.upload_id = c.clip_id)
        """,
    )
    suspend fun awaitingCount(): Int

    @Query(
        """
        SELECT x.upload_id AS uploadId, x.idx AS idx,
            (s.start_occurred_at_epoch_ms + t.offset_ms + x.start_ms) AS startEpochMs,
            s.start_timezone AS zoneId, x.sealed AS sealed
        FROM transcript_span x
        JOIN transcript t ON t.upload_id = x.upload_id
        JOIN audio_segment s ON s.segment_id = t.segment_id
        WHERE s.session_id = :sessionId
        ORDER BY startEpochMs, x.upload_id, x.idx
        """,
    )
    fun observeSession(sessionId: String): Flow<List<TranscriptLine>>
}
