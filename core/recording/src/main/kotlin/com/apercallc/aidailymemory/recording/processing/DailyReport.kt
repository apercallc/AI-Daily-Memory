package com.apercallc.aidailymemory.recording.processing

import kotlinx.serialization.Serializable

/**
 * The daily report contract, stored locally and optionally synced through the public API (ADR-0020).
 * It mirrors the backend's `DailyReport` contract (`contracts/daily-report.v1.schema.json`).
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
@Serializable
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
    /**
     * [DailyReportStatus.COMPLETE] once every session in [memoryDay] has finished processing
     * (or permanently failed); [DailyReportStatus.PARTIAL] while some of the day's audio is
     * still pending (TODO.md "show partial status while audio is pending"). Defaults to
     * complete so existing callers that don't yet track pending sessions keep building valid
     * reports.
     */
    val status: String = DailyReportStatus.COMPLETE,
)

/** The two honest states a generated report can be in (TODO.md "bound processing context"). */
object DailyReportStatus {
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"
}

/**
 * One memory item in a daily report, with its evidence quote for verification.
 * The user can expand the summary to see the full transcript; this quote is the inline preview.
 */
@Serializable
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
@Serializable
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
@Serializable
data class CoverageGap(
    val kind: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val label: String,
)