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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.ui.EmptyState
import com.apercallc.aidailymemory.ui.LoadingState
import com.apercallc.aidailymemory.ui.durationText
import com.apercallc.aidailymemory.ui.sessions.sessionTitleText
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
    val id: String,
    val kind: String,
    val text: String,
    val confidence: String,
    val quote: String?,
    val owner: String?,
    val due: String?,
    val sessionId: String,
    /** True when [text] is a human correction, not the original extracted wording. */
    val isCorrected: Boolean = false,
)

/** One session in the daily memory timeline, with its summary and items. */
data class MemorySession(
    val sessionId: String,
    val title: SessionTitle,
    val startTime: String,
    val capturedMs: Long,
    val summary: String,
    val items: List<MemoryDisplayItem>,
    val transcriptLines: List<LocalTranscriptLineEntity>,
    val audioDeleted: Boolean,
    val freedBytes: Long,
    /**
     * True when this session is grouped with the session immediately before it in time into one
     * event (TODO.md "Event grouping"). False (including for a day's chronologically first
     * session, which has no predecessor) means it starts its own event.
     */
    val joinedWithOlderNeighbor: Boolean = false,
)

/** How a day is named. Localized at render time, so the view model never holds UI strings. */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel

    /** An already-formatted date (or the raw key when it could not be parsed). */
    data class Date(val text: String) : DayLabel
}

/** A day in the memory timeline. */
data class MemoryDay(
    val dayLabel: DayLabel,
    val dateLabel: String,
    val sessions: List<MemorySession>,
)

/** Stateless: renders the daily memory timeline. */
@Composable
fun MemoryScreen(
    state: MemoryLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Empty state's way forward: Settings, where memory processing is turned on. */
    onSetUpProcessing: (() -> Unit)? = null,
    onDismiss: (String) -> Unit = {},
    onCorrect: (String, String) -> Unit = { _, _ -> },
    /** Opens the full report screen for one day (its raw memory-day key), to view and share. */
    onOpenReport: (String) -> Unit = {},
    /** Corrects one session's grouping with the session right before it (TODO.md "Event grouping"). */
    onToggleEventBoundary: (sessionId: String, currentlyJoined: Boolean) -> Unit = { _, _ -> },
    /** Durable cross-day view of open commitments/actions (TODO.md "project memory"). */
    projectMemory: List<ProjectMemorySection> = emptyList(),
) {
    val spacing = AiDailyMemoryTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (projectMemory.isNotEmpty()) {
            item(key = "project-memory") {
                ProjectMemorySectionView(projectMemory, onDismiss, onCorrect)
            }
        }
        when (state) {
            MemoryLoadState.Loading -> item { LoadingState(stringResource(R.string.sessions_loading)) }
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
                    EmptyState(
                        icon = R.drawable.ic_nav_memory,
                        title = stringResource(R.string.memory_empty_title),
                        body = stringResource(R.string.memory_empty),
                        action = onSetUpProcessing?.let { stringResource(R.string.memory_empty_action) to it },
                    )
                }
            } else {
                items(state.days, key = { it.dateLabel }) { day ->
                    MemoryDaySection(day, onDismiss, onCorrect, onOpenReport, onToggleEventBoundary)
                }
            }
        }
    }
}

@Composable
private fun ProjectMemorySectionView(
    sections: List<ProjectMemorySection>,
    onDismiss: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var expanded by rememberSaveable { mutableStateOf(false) }
    val totalCount = sections.sumOf { it.items.size }
    val toggleDescription = stringResource(
        if (expanded) R.string.memory_project_memory_hide else R.string.memory_project_memory_show,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minTouchTarget)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = toggleDescription },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.memory_project_memory_title, totalCount, totalCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    sections.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            Text(
                                text = section.subject ?: stringResource(R.string.memory_project_memory_no_subject),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            section.items.forEach { item -> MemoryItemRow(item, onDismiss, onCorrect) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryDaySection(
    day: MemoryDay,
    onDismiss: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onOpenReport: (String) -> Unit,
    onToggleEventBoundary: (sessionId: String, currentlyJoined: Boolean) -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (val label = day.dayLabel) {
                    DayLabel.Today -> stringResource(R.string.day_today)
                    DayLabel.Yesterday -> stringResource(R.string.day_yesterday)
                    is DayLabel.Date -> label.text
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = { onOpenReport(day.dateLabel) }) {
                Text(stringResource(R.string.memory_view_report))
            }
        }
        day.sessions.forEachIndexed { index, session ->
            // A boundary sits between this card and the next-older one (TODO.md "Event
            // grouping"); it reflects the newer (previous-in-display) session's relation to
            // its chronological predecessor, which is this older session.
            if (index > 0) {
                val newer = day.sessions[index - 1]
                EventBoundaryDivider(
                    joined = newer.joinedWithOlderNeighbor,
                    onToggle = { onToggleEventBoundary(newer.sessionId, newer.joinedWithOlderNeighbor) },
                )
            }
            MemorySessionCard(session, onDismiss, onCorrect)
        }
    }
}

@Composable
private fun EventBoundaryDivider(joined: Boolean, onToggle: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(
                text = stringResource(
                    if (joined) R.string.memory_split_event else R.string.memory_merge_event,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MemorySessionCard(
    session: MemorySession,
    onDismiss: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                        text = sessionTitleText(session.title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${session.startTime} · ${durationText(session.capturedMs)}",
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
                    MemoryItemSection(kind, items, onDismiss, onCorrect)
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
private fun MemoryItemSection(
    kind: String,
    items: List<MemoryDisplayItem>,
    onDismiss: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
) {
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
            MemoryItemRow(item, onDismiss, onCorrect)
        }
    }
}

@Composable
private fun MemoryItemRow(
    item: MemoryDisplayItem,
    onDismiss: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var correcting by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = "›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (item.isCorrected) {
                    Text(
                        text = stringResource(R.string.memory_corrected_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextButton(onClick = { correcting = true }) {
                        Text(stringResource(R.string.memory_correct_item))
                    }
                    TextButton(onClick = { onDismiss(item.id) }) {
                        Text(stringResource(R.string.memory_dismiss_item))
                    }
                }
            }
        }
    }
    if (correcting) {
        CorrectItemDialog(
            initialText = item.text,
            onSave = { newText ->
                onCorrect(item.id, newText)
                correcting = false
            },
            onDismissRequest = { correcting = false },
        )
    }
}

@Composable
private fun CorrectItemDialog(initialText: String, onSave: (String) -> Unit, onDismissRequest: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.memory_correct_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.testTag("correct_item_text_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.memory_correct_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.memory_correct_dialog_cancel))
            }
        },
    )
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
    val transcriptToggleDescription = stringResource(
        if (expanded) R.string.memory_hide_transcript else R.string.memory_view_transcript,
    )

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = spacing.xs)
                .semantics {
                    contentDescription = transcriptToggleDescription
                },
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