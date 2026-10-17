package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.recording.processing.DailyReport
import com.apercallc.aidailymemory.recording.processing.DailyReportStatus
import com.apercallc.aidailymemory.recording.processing.ReportCoverage
import com.apercallc.aidailymemory.recording.processing.ReportItem
import kotlinx.serialization.json.Json

/**
 * Builds a [DailyReport] from extracted memory items and transcript lines (ADR-0020).
 * The report is the unit the phone stores and optionally syncs to a user-configured API endpoint.
 */
object ReportBuilder {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Items beyond this many per kind are counted ("(+N more)"), not spelled out in the summary. */
    private const val MAX_ITEMS_PER_KIND = 8

    /** Hard cap on the whole summary string, after per-kind bounding (TODO.md "bound processing context"). */
    private const val MAX_SUMMARY_CHARS = 1_000

    fun build(
        memoryDay: String,
        items: List<MemoryItemEntity>,
        transcriptLines: List<LocalTranscriptLineEntity>,
        generatedAtEpochMs: Long,
        extractionModel: String,
        /** False while some of this memory day's sessions have not finished processing yet
         *  (TODO.md "show partial status while audio is pending"). */
        isComplete: Boolean = true,
    ): DailyReport {
        val headline = items.firstOrNull()?.let { summarizeHeadline(items) } ?: "Transcript only"
        val summary = buildSummary(items)
        val reportItems = items.map { it.toReportItem() }
        val sessionCount = (items.map { it.sessionId } + transcriptLines.map { it.sessionId }).toSet().size
        val coverage = ReportCoverage(
            recordedMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            speechMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            transcribedMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            sessions = sessionCount,
            gaps = emptyList(),
        )
        return DailyReport(
            memoryDay = memoryDay,
            zoneId = java.time.ZoneId.systemDefault().id,
            headline = headline,
            summary = summary,
            items = reportItems,
            coverage = coverage,
            generatedAtEpochMs = generatedAtEpochMs,
            extractionModel = extractionModel,
            extractionVersion = 1,
            status = if (isComplete) DailyReportStatus.COMPLETE else DailyReportStatus.PARTIAL,
        )
    }

    fun serialize(report: DailyReport): String {
        return json.encodeToString(report)
    }

    fun deserialize(jsonStr: String): DailyReport {
        return json.decodeFromString(jsonStr)
    }

    private fun summarizeHeadline(items: List<MemoryItemEntity>): String {
        val decisions = items.count { it.kind == "decision" }
        val actions = items.count { it.kind == "action" }
        val commitments = items.count { it.kind == "commitment" }
        val ideas = items.count { it.kind == "idea" }
        val parts = mutableListOf<String>()
        if (decisions > 0) parts.add("$decisions decision${if (decisions > 1) "s" else ""}")
        if (actions > 0) parts.add("$actions action item${if (actions > 1) "s" else ""}")
        if (commitments > 0) parts.add("$commitments commitment${if (commitments > 1) "s" else ""}")
        if (ideas > 0) parts.add("$ideas idea${if (ideas > 1) "s" else ""}")
        return if (parts.isEmpty()) "Memory Day" else parts.joinToString(", ")
    }

    private fun buildSummary(items: List<MemoryItemEntity>): String {
        if (items.isEmpty()) return ""
        val grouped = items.groupBy { it.kind }
        val parts = mutableListOf<String>()
        grouped["decision"]?.let { decisions ->
            parts.add("Decided: " + joinBounded(decisions.map { it.text }))
        }
        grouped["action"]?.let { actions ->
            parts.add("To do: " + joinBounded(actions.map { it.text }))
        }
        grouped["commitment"]?.let { commitments ->
            parts.add("Committed: " + joinBounded(commitments.map { it.text }))
        }
        grouped["idea"]?.let { ideas ->
            parts.add("Ideas: " + joinBounded(ideas.map { it.text }))
        }
        // A day with a very long session can produce far more items than a headline summary
        // should ever hold (TODO.md "bound processing context"): cap the whole string too, on
        // top of the per-kind cap above, rather than regenerating a summary of the summary.
        return parts.joinToString(". ").let {
            if (it.length <= MAX_SUMMARY_CHARS) it else it.take(MAX_SUMMARY_CHARS).trimEnd() + "…"
        }
    }

    /** Joins up to [MAX_ITEMS_PER_KIND] texts; any remainder is counted, never silently dropped. */
    private fun joinBounded(texts: List<String>): String {
        val shown = texts.take(MAX_ITEMS_PER_KIND).joinToString("; ")
        val remaining = texts.size - MAX_ITEMS_PER_KIND
        return if (remaining > 0) "$shown (+$remaining more)" else shown
    }

    private fun MemoryItemEntity.toReportItem(): ReportItem = ReportItem(
        kind = kind,
        text = text,
        confidence = confidence,
        quote = quote,
        owner = owner,
        due = due,
        direction = direction,
        counterpart = counterpart,
        sessionId = sessionId,
        evidenceEpochMs = evidenceEpochMs,
    )
}