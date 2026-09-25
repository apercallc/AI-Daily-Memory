package com.apercallc.aidailymemory.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.segment.AudioSegment
import com.apercallc.aidailymemory.recording.segment.SegmentDefect
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.segment.SegmentTiming

/**
 * One applied recording transition. Append-only and keyed by revision: it is written before
 * any side effect that depends on it, and never updated (TODO.md Phase 0).
 */
@Entity(tableName = "recording_transition", indices = [Index("session_id")])
data class TransitionEntity(
    @PrimaryKey val revision: Long,
    val status: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    val problem: String?,
    val cause: String,
    @Embedded val time: CaptureTimeColumns,
) {
    fun toRecord() = JournalRecord(revision, status, sessionId, problem, cause)

    companion object {
        fun of(record: JournalRecord, time: CaptureTimeColumns) = TransitionEntity(
            revision = record.revision,
            status = record.status,
            sessionId = record.sessionId,
            problem = record.problem,
            cause = record.cause,
            time = time,
        )
    }
}

/**
 * Local audio segment (ADR-0007). [segmentId] is a client UUIDv7 that stays stable across
 * retries and is the upload idempotency key (ADR-0006).
 */
@Entity(
    tableName = "audio_segment",
    indices = [
        Index(value = ["session_id", "sequence"], unique = true),
        Index("state"),
    ],
)
data class SegmentEntity(
    @PrimaryKey @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val sequence: Int,
    val state: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** SHA-256 of the plaintext audio, lowercase hex. Null until sealed. */
    val sha256: String?,
    @ColumnInfo(name = "acknowledged_at_epoch_ms") val acknowledgedAtEpochMs: Long?,
    val defect: String?,
    /** File name inside the app's no-backup segment directory; never a full path. */
    @ColumnInfo(name = "file_name") val fileName: String,
    val codec: String,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int,
    @ColumnInfo(name = "channel_count") val channelCount: Int,
    /** False after an intentional boundary (start, resume, recovery); see SegmentTiming. */
    @ColumnInfo(name = "continues_previous") val continuesPrevious: Boolean,
    @Embedded(prefix = "start_") val start: CaptureTimeColumns,
) {
    fun toSegment() = AudioSegment(
        sessionId = SessionId(sessionId),
        sequence = sequence,
        state = SegmentState.valueOf(state),
        byteSize = byteSize,
        durationMs = durationMs,
        sha256 = sha256,
        acknowledgedAtEpochMs = acknowledgedAtEpochMs,
        defect = defect?.let(SegmentDefect::valueOf),
    )

    fun toTiming() = SegmentTiming(
        sequence = sequence,
        bootId = start.bootId,
        startElapsedMs = start.elapsedRealtimeMs,
        durationMs = durationMs,
        continuesPrevious = continuesPrevious,
    )

    /** Applies a lifecycle result, keeping identity and capture metadata. */
    fun withSegment(segment: AudioSegment) = copy(
        state = segment.state.name,
        byteSize = segment.byteSize,
        durationMs = segment.durationMs,
        sha256 = segment.sha256?.lowercase(),
        acknowledgedAtEpochMs = segment.acknowledgedAtEpochMs,
        defect = segment.defect?.name,
    )
}

/** Highlight or Quick Note placed during a session (ADR-0011). Points at audio; holds none. */
@Entity(tableName = "capture_marker", indices = [Index("session_id")])
data class MarkerEntity(
    @PrimaryKey @ColumnInfo(name = "marker_id") val markerId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val kind: String,
    @ColumnInfo(name = "window_before_ms") val windowBeforeMs: Long,
    @ColumnInfo(name = "window_after_ms") val windowAfterMs: Long,
    @Embedded val time: CaptureTimeColumns,
) {
    val markerKind: MarkerKind get() = MarkerKind.valueOf(kind)
}

/**
 * Per-session metadata that isn't a transition (ADR-0012). [memoryDay] is fixed when the
 * session starts, so changing the sleep schedule later never moves past sessions between days.
 * [customTitle] is the user's rename; null means "use the automatic title".
 */
@Entity(tableName = "session_info", indices = [Index("memory_day")])
data class SessionInfoEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "memory_day") val memoryDay: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "custom_title") val customTitle: String?,
)

/** One row of the sessions list, with what was actually captured. */
data class SessionRow(
    val sessionId: String,
    val memoryDay: String,
    val zoneId: String,
    val startedAtEpochMs: Long,
    val customTitle: String?,
    val lastActivityEpochMs: Long?,
    /** Sealed audio only: gaps and torn segments don't count as captured. */
    val capturedMs: Long,
)
