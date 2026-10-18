package com.apercallc.aidailymemory.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.processing.DailyReport
import com.apercallc.aidailymemory.recording.processing.DailyReportStatus
import com.apercallc.aidailymemory.recording.processing.ReportItem
import com.apercallc.aidailymemory.ui.EmptyState
import com.apercallc.aidailymemory.ui.LoadingState
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** Load state for one day's report screen (TODO.md "Add an in-app report screen"). */
sealed interface ReportLoadState {
    data object Loading : ReportLoadState
    data object NotGenerated : ReportLoadState
    data class Content(val report: DailyReport) : ReportLoadState
    /** A DB-read exception on this path. Not the same as [NotGenerated]: the report may exist. */
    data object Failed : ReportLoadState
}

/**
 * Stateless: shows one memory day's report exactly as it will be shared — headline, summary,
 * coverage, then items grouped by kind in the same order [com.apercallc.aidailymemory.recording.processing.renderMarkdown]
 * uses, so the screen and the shared Markdown never disagree.
 */
@Composable
fun ReportScreen(state: ReportLoadState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = AiDailyMemoryTheme.spacing
    when (state) {
        ReportLoadState.Loading -> LoadingState(stringResource(R.string.report_loading), modifier)
        ReportLoadState.NotGenerated -> EmptyState(
            icon = R.drawable.ic_empty_report,
            title = stringResource(R.string.report_not_generated_title),
            body = stringResource(R.string.report_not_generated_body),
            modifier = modifier,
        )
        ReportLoadState.Failed -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.report_load_error),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.report_retry))
            }
        }
        is ReportLoadState.Content -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Text(
                    text = state.report.headline,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.report.status == DailyReportStatus.PARTIAL) {
                item {
                    Text(
                        text = stringResource(R.string.report_partial_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.report.summary.isNotBlank()) {
                item {
                    Text(text = state.report.summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Text(
                    text = coverageText(state.report),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf("decision", "action", "commitment", "idea", "question").forEach { kind ->
                val kindItems = state.report.items.filter { it.kind == kind }
                if (kindItems.isNotEmpty()) {
                    item { ReportKindHeading(kind) }
                    items(kindItems, key = { "$kind-${it.text}-${it.sessionId}" }) { reportItem ->
                        ReportItemRow(reportItem)
                    }
                }
            }
        }
    }
}

@Composable
private fun coverageText(report: DailyReport): String {
    val minutes = (report.coverage.recordedMs + 30_000) / 60_000
    val sessionWord = if (report.coverage.sessions == 1) {
        stringResource(R.string.report_coverage_session)
    } else {
        stringResource(R.string.report_coverage_sessions)
    }
    return stringResource(R.string.report_coverage, minutes, report.coverage.sessions, sessionWord)
}

@Composable
private fun ReportKindHeading(kind: String) {
    val titleRes = when (kind) {
        "decision" -> R.string.memory_decisions
        "action" -> R.string.memory_actions
        "commitment" -> R.string.memory_commitments
        "idea" -> R.string.memory_ideas
        "question" -> R.string.memory_questions
        else -> return
    }
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ReportItemRow(item: ReportItem) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                item.owner?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item.due?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item.counterpart?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item.quote?.let { quote ->
                Text(
                    text = stringResource(R.string.memory_evidence_quote, quote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}
