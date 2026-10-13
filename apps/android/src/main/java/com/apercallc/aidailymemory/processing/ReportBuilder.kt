package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.recording.processing.CoverageGap
import com.apercallc.aidailymemory.recording.processing.DailyReport
import com.apercallc.aidailymemory.recording.processing.ReportCoverage
import com.apercallc.aidailymemory.recording.processing.ReportItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Builds a [DailyReport] from extracted memory items and transcript lines (ADR-0020).
 * The report is the unit the phone stores and optionally syncs to a user-configured API endpoint.
 */
object ReportBuilder {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun build(
        memoryDay: String,
        sessionId: String,
        items: List<MemoryItemEntity>,
        transcriptLines: List<LocalTranscriptLineEntity>,
        generatedAtEpochMs: Long,
        extractionModel: String,
    ): DailyReport {
        val headline = items.firstOrNull()?.let { summarizeHeadline(items) } ?: "Transcript only"
        val summary = buildSummary(items)
        val reportItems = items.map { it.toReportItem() }
        val coverage = ReportCoverage(
            recordedMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            speechMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            transcribedMs = transcriptLines.sumOf { it.endEpochMs - it.startEpochMs },
            sessions = 1,
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
            parts.add("Decided: " + decisions.joinToString("; ") { it.text })
        }
        grouped["action"]?.let { actions ->
            parts.add("To do: " + actions.joinToString("; ") { it.text })
        }
        grouped["commitment"]?.let { commitments ->
            parts.add("Committed: " + commitments.joinToString("; ") { it.text })
        }
        grouped["idea"]?.let { ideas ->
            parts.add("Ideas: " + ideas.joinToString("; ") { it.text })
        }
        return parts.joinToString(". ")
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