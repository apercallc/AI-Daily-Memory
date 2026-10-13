package com.apercallc.aidailymemory.recording.processing

import kotlinx.serialization.Serializable

/**
 * The sync payload the phone pushes to a user-configured API endpoint (ADR-0020).
 * This is the wire format — serialized to JSON — for the nightly sync job.
 *
 * Only structured memory leaves the phone: daily reports and their items with evidence quotes.
 * Never audio. Raw transcript is not included unless [includeTranscripts] is true (off by
 * default; the user opts in in Settings).
 */
@Serializable
data class SyncPayload(
    val device: String,
    val generatedAtEpochMs: Long,
    val reports: List<SyncReport>,
    val includeTranscripts: Boolean,
    /**
     * Sessions deleted locally whose server-side derived memory must be purged (ADR-0020:
     * deletion propagates). Empty by default so the field is omitted from the JSON unless
     * there is something to delete.
     */
    val deletedSessions: List<String> = emptyList(),
)

/**
 * One daily report in the sync payload. Matches [DailyReport] but uses serializable types
 * suitable for the wire.
 */
@Serializable
data class SyncReport(
    val memoryDay: String,
    val zoneId: String,
    val headline: String,
    val summary: String,
    val items: List<SyncItem>,
    val coverage: SyncCoverage,
    val generatedAtEpochMs: Long,
    val extractionModel: String,
    val extractionVersion: Int,
    /** Included only when the user enables full transcript sync. */
    val transcriptLines: List<SyncTranscriptLine>? = null,
    /**
     * The day's sessions that have something to show (words or items), whether or not words are
     * sent. Lets the server keep a note for each and carry the name the person gave it.
     */
    val sessions: List<SyncSession> = emptyList(),
)

/** What the phone knows about one session besides its words (ADR-0027). */
@Serializable
data class SyncSession(
    val sessionId: String,
    /** The person's own name for it; null while it has only the automatic title. */
    val title: String? = null,
    val startedAtEpochMs: Long? = null,
)

@Serializable
data class SyncItem(
    val kind: String,
    val text: String,
    val confidence: String,
    val quote: String? = null,
    val owner: String? = null,
    val due: String? = null,
    val direction: String? = null,
    val counterpart: String? = null,
    val sessionId: String,
    val evidenceEpochMs: Long? = null,
)

@Serializable
data class SyncCoverage(
    val recordedMs: Long,
    val speechMs: Long,
    val transcribedMs: Long,
    val sessions: Int,
)

@Serializable
data class SyncTranscriptLine(
    val sessionId: String,
    val startEpochMs: Long,
    val speaker: String?,
    val text: String,
)