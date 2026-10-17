package com.apercallc.aidailymemory.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import com.apercallc.aidailymemory.ui.EmptyState
import com.apercallc.aidailymemory.ui.LoadingState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.recording.session.SessionTitles
import com.apercallc.aidailymemory.recording.session.TitleInput
import com.apercallc.aidailymemory.ui.TimeText
import com.apercallc.aidailymemory.ui.durationText
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Stateless sessions list. [days] is null while loading. */
@Composable
fun SessionsScreen(
    days: List<DayGroup>?,
    today: LocalDate,
    onRename: (sessionId: String, input: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a session's transcript, with the title as shown on its card. */
    onOpen: (sessionId: String, title: String) -> Unit = { _, _ -> },
    onDelete: (sessionId: String) -> Unit = {},
    /** Exports a session's still-retained local audio to the Android share sheet. Only shown
     *  when [SessionItem.retainedAudioMs] is positive (TODO.md Phase 8 "per-session
     *  retained-audio awareness"). */
    onExportAudio: (sessionId: String) -> Unit = {},
    /** Empty state's way forward: back to the Today tab. */
    onStartRecording: (() -> Unit)? = null,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        when {
            days == null -> item { LoadingState(stringResource(R.string.sessions_loading)) }
            days.isEmpty() -> item {
                EmptyState(
                    icon = R.drawable.ic_empty_sessions,
                    title = stringResource(R.string.sessions_empty_title),
                    body = stringResource(R.string.sessions_empty_body),
                    action = onStartRecording?.let { stringResource(R.string.sessions_empty_action) to it },
                )
            }
            else -> days.forEach { group ->
                item(key = group.day.toString()) { DayHeader(group, today) }
                items(group.sessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session,
                        onOpen = { title -> onOpen(session.sessionId, title) },
                        onRename = { renaming = session.sessionId },
                        onDelete = { deleting = session.sessionId },
                        onExportAudio = { onExportAudio(session.sessionId) },
                    )
                }
            }
        }
    }

    val session = days?.flatMap { it.sessions }?.firstOrNull { it.sessionId == renaming }
    if (session != null) {
        RenameDialog(
            current = session.customTitle.orEmpty(),
            onDismiss = { renaming = null },
            onSave = { input ->
                renaming = null
                onRename(session.sessionId, input)
            },
        )
    }

    val sessionToDelete = days?.flatMap { it.sessions }?.firstOrNull { it.sessionId == deleting }
    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.session_delete_title)) },
            text = { Text(stringResource(R.string.session_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(sessionToDelete.sessionId)
                }) { Text(stringResource(R.string.session_delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup, today: LocalDate) {
    val label = when (group.day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> group.day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
    Column(modifier = Modifier.padding(top = AiDailyMemoryTheme.spacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.day_captured, durationText(group.capturedMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionItem,
    onOpen: (title: String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExportAudio: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    val times = TimeText.rememberFormatter()
    val range = session.end?.let {
        stringResource(R.string.session_time_range, times(session.start), times(it))
    } ?: stringResource(R.string.session_time_open, times(session.start))
    val openHint = stringResource(R.string.session_open_hint)
    val title = sessionTitleText(session.title)
    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minTouchTarget)
            .clickable(onClickLabel = openHint, role = Role.Button, onClick = { onOpen(title) }),
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = range,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.speechMs?.let { speech ->
                    Text(
                        text = if (speech == 0L) {
                            stringResource(R.string.session_no_speech)
                        } else {
                            stringResource(R.string.session_speech, durationText(speech))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = durationText(session.capturedMs),
                    style = MaterialTheme.typography.labelLarge,
                )
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(spacing.minTouchTarget),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.session_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        if (session.retainedAudioMs > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.session_export_audio)) },
                                onClick = {
                                    menuOpen = false
                                    onExportAudio()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.session_delete_action),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun sessionTitleText(title: SessionTitle): String = when (title) {
    is SessionTitle.Custom -> title.text
    is SessionTitle.Default -> {
        val part = stringResource(
            when (title.part) {
                PartOfDay.EARLY_MORNING -> R.string.part_early_morning
                PartOfDay.MORNING -> R.string.part_morning
                PartOfDay.AFTERNOON -> R.string.part_afternoon
                PartOfDay.EVENING -> R.string.part_evening
                PartOfDay.NIGHT -> R.string.part_night
            },
        )
        title.number?.let { stringResource(R.string.session_title_numbered, part, it) } ?: part
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val error = when (SessionTitles.parse(text)) {
        TitleInput.TooLong -> R.string.rename_too_long
        TitleInput.InvalidCharacters -> R.string.rename_invalid
        is TitleInput.Valid, TitleInput.Clear -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = error == null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.rename_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
