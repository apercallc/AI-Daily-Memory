package com.apercallc.aidailymemory.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
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
fun TranscriptScreen(lines: List<TranscriptText>?, modifier: Modifier = Modifier) {
    val spacing = AiDailyMemoryTheme.spacing
    val times = TimeText.rememberFormatter()
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        when {
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
                val spoken = listOfNotNull(time, speaker, line.text).joinToString(", ")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    // One announcement per line: time, speaker, words.
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = TIME_COLUMN),
                    )
                    Column {
                        speaker?.let {
                            Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(text = line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private val TIME_COLUMN = 56.dp

private fun zoneOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: DateTimeException) {
    ZoneId.systemDefault()
}
