package com.apercallc.aidailymemory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
