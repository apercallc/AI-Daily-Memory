package com.apercallc.aidailymemory.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One memory item with its display metadata, ready for the UI. */
data class MemoryDisplayItem(
    val kind: String,
    val text: String,
    val confidence: String,
    val quote: String?,
    val owner: String?,
    val due: String?,
    val sessionId: String,
)

/** One session in the daily memory timeline, with its summary and items. */
data class MemorySession(
    val sessionId: String,
    val title: String,
    val startTime: String,
    val durationText: String,
    val summary: String,
    val items: List<MemoryDisplayItem>,
    val transcriptLines: List<LocalTranscriptLineEntity>,
    val audioDeleted: Boolean,
    val freedBytes: Long,
)

/** A day in the memory timeline. */
data class MemoryDay(
    val dayLabel: String,
    val dateLabel: String,
    val sessions: List<MemorySession>,
)

/** Stateless: renders the daily memory timeline. */
@Composable
fun MemoryScreen(
    state: MemoryLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        when (state) {
            MemoryLoadState.Loading -> item {
                Text(
                    text = stringResource(R.string.sessions_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MemoryLoadState.Failed -> item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = stringResource(R.string.memory_load_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.memory_retry))
                    }
                }
            }
            is MemoryLoadState.Content -> if (state.days.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.days, key = { it.dateLabel }) { day ->
                    MemoryDaySection(day)
                }
            }
        }
    }
}

@Composable
private fun MemoryDaySection(day: MemoryDay) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = day.dayLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        day.sessions.forEach { session ->
            MemorySessionCard(session)
        }
    }
}

@Composable
private fun MemorySessionCard(session: MemorySession) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Session header: title + time + duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${session.startTime} · ${session.durationText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Summary
            if (session.summary.isNotBlank()) {
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Memory item chips
            val kindCounts = session.items.groupingBy { it.kind }.eachCount()
            if (kindCounts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    kindCounts.forEach { (kind, count) ->
                        val labelRes = when (kind) {
                            "decision" -> R.string.memory_decisions
                            "action" -> R.string.memory_actions
                            "commitment" -> R.string.memory_commitments
                            "idea" -> R.string.memory_ideas
                            "question" -> R.string.memory_questions
                            else -> null
                        }
                        labelRes?.let {
                            MemoryChip(text = "$count ${stringResource(it)}")
                        }
                    }
                }
            }

            // Memory items by kind
            val grouped = session.items.groupBy { it.kind }
            listOf("decision", "action", "commitment", "idea", "question").forEach { kind ->
                grouped[kind]?.let { items ->
                    MemoryItemSection(kind, items)
                }
            }

            // Expandable transcript
            if (session.transcriptLines.isNotEmpty()) {
                ExpandableTranscript(session.transcriptLines, session.audioDeleted, session.freedBytes)
            }
        }
    }
}

@Composable
private fun MemoryItemSection(kind: String, items: List<MemoryDisplayItem>) {
    val spacing = AiDailyMemoryTheme.spacing
    val titleRes = when (kind) {
        "decision" -> R.string.memory_decisions
        "action" -> R.string.memory_actions
        "commitment" -> R.string.memory_commitments
        "idea" -> R.string.memory_ideas
        "question" -> R.string.memory_questions
        else -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Column {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
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
        }
    }
}

@Composable
private fun ExpandableTranscript(
    lines: List<LocalTranscriptLineEntity>,
    audioDeleted: Boolean,
    freedBytes: Long,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var expanded by rememberSaveable { mutableStateOf(false) }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = spacing.xs)
                .semantics { contentDescription = if (expanded) "Hide transcript" else "View transcript" },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (expanded) R.string.memory_hide_transcript else R.string.memory_view_transcript),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (audioDeleted) {
                    Text(
                        text = stringResource(R.string.memory_transcript_deleted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lines.forEach { line ->
                    val time = try {
                        Instant.ofEpochMilli(line.startEpochMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalTime()
                            .format(timeFormatter)
                    } catch (_: DateTimeException) {
                        ""
                    }
                    val speaker = line.speaker?.let { "$it: " } ?: ""
                    Text(
                        text = "[$time] $speaker${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryChip(text: String) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
        )
    }
}