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

/**
 * The person's correction of the automatic event grouping for one session (TODO.md "Event
 * grouping": support event-boundary corrections). [override] is "force_join" (always group with
 * the immediately preceding session, even across the automatic gap/duration rules) or
 * "force_split" (never group with it). No row means "use the automatic grouping". Keyed by
 * session, not by day, so it survives independent of how many sessions a day ends up with.
 */
@Entity(tableName = "session_event_override")
data class SessionEventOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    val override: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

object SessionEventOverrideKind {
    const val FORCE_JOIN = "force_join"
    const val FORCE_SPLIT = "force_split"
}

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
    /** Captured audio analyzed for speech so far (ADR-0015). */
    val analyzedMs: Long = 0,
    /** Detected speech in the analyzed audio. */
    val speechMs: Long = 0,
    /** Audio still on this phone (not yet deleted by retention or the person) — TODO.md
     *  Phase 8 "per-session retained-audio awareness". */
    val retainedAudioMs: Long = 0,
)

/**
 * Result of analyzing one sealed segment for speech (ADR-0015). Written once per analyzer
 * version; [outcome] records failures too, so an unreadable segment isn't retried every night.
 */
@Entity(tableName = "segment_speech")
data class SegmentSpeechEntity(
    @PrimaryKey @ColumnInfo(name = "segment_id") val segmentId: String,
    val analyzer: String,
    val outcome: String,
    @ColumnInfo(name = "total_ms") val totalMs: Long,
    @ColumnInfo(name = "speech_ms") val speechMs: Long,
    @ColumnInfo(name = "padded_ms") val paddedMs: Long,
    @ColumnInfo(name = "region_count") val regionCount: Int,
    @ColumnInfo(name = "analyzed_at_epoch_ms") val analyzedAtEpochMs: Long,
)

/** A span of a segment to transcribe, ms from the segment start: [startMs, endMs). */
@Entity(tableName = "speech_region", primaryKeys = ["segment_id", "start_ms"])
data class SpeechRegionEntity(
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "speech_ms") val speechMs: Long,
    @ColumnInfo(name = "low_confidence") val lowConfidence: Boolean,
)

/**
 * Whether a segment's clips have been planned, and how that went. One row per segment, so
 * segments that can't be planned leave the queue but stay visible (never silently stuck).
 */
@Entity(tableName = "clip_plan")
data class ClipPlanEntity(
    @PrimaryKey @ColumnInfo(name = "segment_id") val segmentId: String,
    /** PLANNED, NO_SPEECH, UNREADABLE (retryable by the user), or EMPTY. */
    val outcome: String,
    @ColumnInfo(name = "planned_at_epoch_ms") val plannedAtEpochMs: Long,
)

/** Totals across analyzed segments, for the Gate B cost model (docs/performance). */
data class SpeechTotals(val analyzedMs: Long, val speechMs: Long, val paddedMs: Long)

/**
 * One planned upload clip (ADR-0015): frames [firstFrame, endFrame) of a segment. [clipId] is
 * a client UUIDv7 and the server-side upload id, so every protocol step is retry-safe.
 */
@Entity(
    tableName = "clip_upload",
    indices = [Index(value = ["segment_id", "first_frame"], unique = true), Index("state")],
)
data class ClipUploadEntity(
    @PrimaryKey @ColumnInfo(name = "clip_id") val clipId: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "first_frame") val firstFrame: Int,
    @ColumnInfo(name = "end_frame") val endFrame: Int,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val sha256: String,
    /** PENDING until the server has verified the bytes (RECEIVED); FAILED is terminal. */
    val state: String,
    val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

/**
 * A transcript delivered to this phone (ADR-0014): local evidence, inserted once and never
 * rewritten (ADR-0004). [uploadId] is the clip's upload id; times are ms from the clip start.
 */
@Entity(tableName = "transcript", indices = [Index("segment_id")])
data class TranscriptEntity(
    @PrimaryKey @ColumnInfo(name = "upload_id") val uploadId: String,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
    val provider: String,
    val model: String,
    val language: String?,
    @ColumnInfo(name = "received_at_epoch_ms") val receivedAtEpochMs: Long,
)

/**
 * One transcript line. The words and speaker label are sealed with a Keystore-backed key
 * (TranscriptCipher) before they reach this database, like the audio they came from
 * (SECURITY.md "Local audio at rest"); only timing stays in the clear.
 */
@Entity(tableName = "transcript_span", primaryKeys = ["upload_id", "idx"])
data class TranscriptSpanEntity(
    @ColumnInfo(name = "upload_id") val uploadId: String,
    val idx: Int,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    /** AEAD of {"text", "speaker"}, bound to upload id + index. */
    val sealed: ByteArray,
    val confidence: Double?,
) {
    override fun equals(other: Any?): Boolean =
        other is TranscriptSpanEntity && other.uploadId == uploadId && other.idx == idx &&
            other.startMs == startMs && other.endMs == endMs && other.sealed.contentEquals(sealed) &&
            other.confidence == confidence

    override fun hashCode(): Int = uploadId.hashCode() * 31 + idx
}

/** One sealed transcript line placed on the wall clock, before it is opened for display. */
data class TranscriptLine(
    val uploadId: String,
    val idx: Int,
    val startEpochMs: Long,
    val zoneId: String,
    val sealed: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TranscriptLine && other.uploadId == uploadId && other.idx == idx &&
            other.startEpochMs == startEpochMs && other.zoneId == zoneId && other.sealed.contentEquals(sealed)

    override fun hashCode(): Int = uploadId.hashCode() * 31 + idx
}
