package com.apercallc.aidailymemory.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One extracted memory item stored on the phone (ADR-0020). The on-device equivalent of the
 * backend's `memory_items` table (ADR-0019), but stored locally in Room/SQLite.
 *
 * Evidence quotes are inline (≤300 chars) so the summary view can show them without joining
 * to the full transcript. The full transcript is available via [TranscriptDao] for the
 * expand-to-verify view.
 *
 * Items are replaced on every extraction run for a session (idempotent by replacement, same
 * as the backend). A rerun clears earlier dismissals — known limitation (ADR-0019).
 */
@Entity(
    tableName = "memory_item",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["memory_day"]),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "memory_day") val memoryDay: String,
    val kind: String,
    val text: String,
    val confidence: String,
    val quote: String?,
    val owner: String?,
    val due: String?,
    val direction: String?,
    val counterpart: String?,
    /** "provider:model", e.g. "whisper:base.en" or "deepgram:nova-2". */
    @ColumnInfo(name = "extraction_model") val extractionModel: String,
    @ColumnInfo(name = "extraction_version") val extractionVersion: Int,
    /** Epoch ms of the primary evidence span, for timeline display. Null if not available. */
    @ColumnInfo(name = "evidence_epoch_ms") val evidenceEpochMs: Long?,
    @ColumnInfo(name = "dismissed_at_epoch_ms") val dismissedAtEpochMs: Long?,
    @ColumnInfo(name = "extracted_at_epoch_ms") val extractedAtEpochMs: Long,
)

/**
 * A daily report stored on the phone (ADR-0020). One row per user per memory day (single-user
 * device, so just one row per memory day). Stored as JSONB-equivalent (JSON string in SQLite).
 *
 * This is the primary artifact the user sees in the daily timeline and the primary thing that
 * syncs through the user's API endpoint.
 */
@Entity(
    tableName = "daily_report",
    indices = [Index(value = ["memory_day"], unique = true)],
)
data class DailyReportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "memory_day") val memoryDay: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val headline: String,
    val summary: String,
    /** The full report JSON, including items and coverage, for sync and reconstruction. */
    @ColumnInfo(name = "report_json") val reportJson: String,
    @ColumnInfo(name = "generated_at_epoch_ms") val generatedAtEpochMs: Long,
    @ColumnInfo(name = "extraction_model") val extractionModel: String,
    @ColumnInfo(name = "extraction_version") val extractionVersion: Int,
)

/**
 * A transcript line for the expand-to-verify view (ADR-0020). Unlike [TranscriptSpanEntity],
 * which stores sealed bytes, this is the plaintext line produced by on-device processing —
 * already decrypted and ready for display. It is the evidence behind every summary and
 * memory item.
 *
 * For transcripts received from the old server-side path (ADR-0014), [TranscriptSpanEntity]
 * remains; those are sealed and decrypted at display time. This table is for the new
 * on-device path where the phone produces the transcript itself.
 */
@Entity(
    tableName = "local_transcript_line",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "start_epoch_ms"]),
    ],
)
data class LocalTranscriptLineEntity(
    @PrimaryKey @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "start_epoch_ms") val startEpochMs: Long,
    @ColumnInfo(name = "end_epoch_ms") val endEpochMs: Long,
    val speaker: String?,
    val text: String,
    val confidence: Double?,
    val provider: String,
    val model: String,
)

/**
 * A session deleted locally but not yet purged from the sync target (ADR-0020). Entries are
 * added when a session with possibly-synced derived memory is deleted on the phone, sent as
 * [com.apercallc.aidailymemory.recording.processing.SyncPayload.deletedSessions] on the next
 * sync, and removed only after the sync target accepted the payload.
 *
 * Deletions made while sync is disabled are not retroactively queued (known limitation).
 */
@Entity(tableName = "sync_deletion_queue")
data class SyncDeletionQueueEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "queued_at_epoch_ms") val queuedAtEpochMs: Long,
)

/**
 * Processing state for a session (ADR-0020). Tracks whether the session's clips have been
 * transcribed, extracted, and reported on-device. One row per session.
 */
@Entity(
    tableName = "processing_state",
    indices = [Index(value = ["state"])],
)
data class ProcessingStateEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    /** PENDING, TRANSCRIBING, TRANSCRIBED, EXTRACTING, COMPLETED, FAILED. */
    val state: String,
    @ColumnInfo(name = "audio_deleted") val audioDeleted: Boolean,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "completed_segments", defaultValue = "0") val completedSegments: Int = 0,
    @ColumnInfo(name = "total_segments", defaultValue = "0") val totalSegments: Int = 0,
    @ColumnInfo(name = "processing_time_ms", defaultValue = "0") val processingTimeMs: Long = 0,
)

/** Content-free record of successful provider usage, retained only with its session. */
@Entity(
    tableName = "provider_usage",
    indices = [Index(value = ["recorded_at_epoch_ms"]), Index(value = ["session_id"])],
    foreignKeys = [
        ForeignKey(
            entity = SessionInfoEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProviderUsageEntity(
    @PrimaryKey @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** TRANSCRIPTION or SUMMARY */
    val stage: String,
    /** Strict provider allowlist only; null means custom or unknown. */
    @ColumnInfo(name = "provider_id") val providerId: String?,
    val model: String,
    @ColumnInfo(name = "request_audio_ms") val requestAudioMs: Long,
    @ColumnInfo(name = "session_audio_ms") val sessionAudioMs: Long,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long?,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
)
