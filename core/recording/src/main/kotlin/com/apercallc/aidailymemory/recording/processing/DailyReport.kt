package com.apercallc.aidailymemory.recording.processing

/**
 * The daily report contract — the unit of memory that the phone stores and optionally syncs to
 * a second brain (ADR-0020). This is the on-device equivalent of the backend's
 * `DailyReport` contract (`contracts/daily-report.v1.schema.json`).
 *
 * A report covers one memory day (ADR-0012) and contains:
 * - A headline summary of the day.
 * - Structured memory items (decisions, actions, commitments, ideas, questions), each with
 *   evidence quotes pointing at transcript spans.
 * - Coverage information (how much of the day was recorded, processed, and gaps).
 *
 * The report is built on-device after extraction completes for a session. It is the primary
 * thing the user sees in the daily timeline and the primary thing that syncs out.
 */
data class DailyReport(
    val memoryDay: String,
    val zoneId: String,
    val headline: String,
    val summary: String,
    val items: List<ReportItem>,
    val coverage: ReportCoverage,
    val generatedAtEpochMs: Long,
    val extractionModel: String,
    val extractionVersion: Int,
)

/**
 * One memory item in a daily report, with its evidence quote for verification.
 * The user can expand the summary to see the full transcript; this quote is the inline preview.
 */
data class ReportItem(
    val kind: String,
    val text: String,
    val confidence: String,
    val quote: String?,
    val owner: String?,
    val due: String?,
    val direction: String?,
    val counterpart: String?,
    val sessionId: String,
    /** Epoch ms of the transcript span this item's evidence points at, for timeline display. */
    val evidenceEpochMs: Long?,
)

/**
 * How much of the day was captured and processed. Honest coverage (ADR-0015): the report
 * distinguishes not recording, recording with no speech, speech not yet processed, and gaps.
 */
data class ReportCoverage(
    val recordedMs: Long,
    val speechMs: Long,
    val transcribedMs: Long,
    val sessions: Int,
    val gaps: List<CoverageGap>,
)

/**
 * A named gap in the day's coverage (paused, quiet time, asleep, or speech over plan limit).
 */
data class CoverageGap(
    val kind: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val label: String,
)