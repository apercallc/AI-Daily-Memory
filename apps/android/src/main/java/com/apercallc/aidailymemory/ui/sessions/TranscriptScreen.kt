package com.apercallc.aidailymemory.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.data.ProcessingStateEntity
import com.apercallc.aidailymemory.data.TranscriptText
import com.apercallc.aidailymemory.ui.EmptyState
import com.apercallc.aidailymemory.ui.LoadingState
import com.apercallc.aidailymemory.ui.TimeText
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * A session's transcript, line by line, at the time each line was said in the zone it was
 * recorded in. Stateless. [lines] is null while loading. Speaker labels are shown as the
 * provider gave them; attributing "me" comes with ADR-0016.
 */
@Composable
fun TranscriptScreen(
    lines: List<TranscriptText>?,
    modifier: Modifier = Modifier,
    processingState: ProcessingStateEntity? = null,
    /** True once analysis is complete and found no speech: a terminal outcome, not a wait. */
    noSpeechDetected: Boolean = false,
) {
    val spacing = AiDailyMemoryTheme.spacing
    val times = TimeText.rememberFormatter()
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        processingState?.takeIf { it.state != "COMPLETED" && !noSpeechDetected }?.let { state ->
            item(key = "processing_status") { ProcessingStatusCard(state) }
        }
        when {
            noSpeechDetected || (lines.isNullOrEmpty() && processingState?.state == "COMPLETED") -> item {
                EmptyState(
                    icon = R.drawable.ic_nav_sessions,
                    title = stringResource(R.string.transcript_no_words_title),
                    body = stringResource(R.string.transcript_no_words_body),
                )
            }
            lines == null -> item { LoadingState(stringResource(R.string.transcript_loading)) }
            lines.isEmpty() -> item {
                EmptyState(
                    icon = R.drawable.ic_nav_sessions,
                    title = stringResource(R.string.transcript_empty_title),
                    body = stringResource(R.string.transcript_empty),
                )
            }
            else -> items(lines, key = { it.key }) { line ->
                val time = times(Instant.ofEpochMilli(line.startEpochMs).atZone(zoneOf(line.zoneId)))
                val speaker = line.speaker?.let { stringResource(R.string.transcript_speaker, it) }
                val bodyText = if (line.unreadable) stringResource(R.string.transcript_unreadable) else line.text
                val spoken = listOfNotNull(time, speaker, bodyText).joinToString(", ")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    // One announcement per line: time, speaker, words.
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = spacing.timeColumnWidth),
                    )
                    Column {
                        speaker?.let {
                            Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (line.unreadable) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingStatusCard(state: ProcessingStateEntity) {
    val spacing = AiDailyMemoryTheme.spacing
    val estimate = ProcessingProgressEstimator.estimate(
        completedSegments = state.completedSegments,
        totalSegments = state.totalSegments,
        processingTimeMs = state.processingTimeMs,
    )
    val label = when (state.state) {
        "PENDING" -> if (state.lastError != null) {
            stringResource(R.string.processing_session_retry_pending)
        } else {
            stringResource(R.string.processing_session_waiting)
        }
        "TRANSCRIBING" -> {
            if (estimate?.remainingMs != null) {
                stringResource(
                    R.string.processing_session_progress,
                    estimate.percentComplete,
                    remainingTimeLabel(estimate.remainingMs),
                )
            } else {
                pluralStringResource(
                    R.plurals.processing_session_progress_no_eta,
                    state.totalSegments,
                    estimate?.percentComplete ?: 0,
                    state.completedSegments,
                    state.totalSegments,
                )
            }
        }
        "TRANSCRIBED", "EXTRACTING" -> stringResource(R.string.processing_session_finishing)
        "FAILED" -> stringResource(R.string.processing_session_failed)
        else -> return
    }

    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (state.state == "TRANSCRIBING" && estimate != null) {
                LinearProgressIndicator(
                    progress = { estimate.percentComplete / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun remainingTimeLabel(remainingMs: Long): String {
    val minutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = minutes / 60L
    return if (hours > 0L) {
        stringResource(R.string.processing_session_eta_hours, hours.toInt(), (minutes % 60L).toInt())
    } else {
        stringResource(R.string.processing_session_eta_minutes, minutes.toInt())
    }
}

private fun zoneOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: DateTimeException) {
    ZoneId.systemDefault()
}
