package com.apercallc.aidailymemory.ui.settings

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.schedule.QuietWindow
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.ui.TimeText
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.UUID

/** Stateless: renders the rhythm and retention, reports edits as whole new values. */
@Composable
fun RhythmSettingsScreen(
    rhythm: DailyRhythm,
    retention: AudioRetention,
    nextChange: NextChange?,
    bytesPerHour: Long,
    showUnreadableNotice: Boolean,
    onRhythmChange: (DailyRhythm) -> Unit,
    onRetentionChange: (AudioRetention) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        if (showUnreadableNotice) Notice(R.string.schedule_unreadable)
        nextChange?.let { NextChangeLine(it) }

        SleepSection(rhythm, onRhythmChange)
        HorizontalDivider()
        QuietSection(rhythm, onRhythmChange)
        HorizontalDivider()
        AudioSection(retention, bytesPerHour, onRetentionChange)
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Explainer(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Notice(@StringRes text: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text = stringResource(text),
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(AiDailyMemoryTheme.spacing.lg),
        )
    }
}

@Composable
private fun NextChangeLine(next: NextChange) {
    val time = TimeText.rememberFormatter()(next.at)
    val text = when (next.action) {
        RhythmAction.PAUSE -> next.quietLabel?.let { stringResource(R.string.next_quiet, it, time) }
            ?: stringResource(R.string.next_pause_bedtime, time)
        RhythmAction.RESUME -> stringResource(R.string.next_resume, time)
        RhythmAction.END_DAY -> stringResource(R.string.next_end_day, time)
        RhythmAction.WAKE_REMINDER -> stringResource(R.string.next_reminder, time)
    }
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

/** A full-width row with a switch; the whole row is the touch target (48dp+). */
@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes body: Int?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AiDailyMemoryTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            body?.let { Explainer(it) }
        }
        // The row handles the toggle, so the switch itself isn't a second target.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TimeRow(@StringRes label: Int, time: LocalTime, onChange: (LocalTime) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val formatted = TimeText.rememberLocalFormatter()(time)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier.heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
        ) { Text(formatted) }
    }
    if (picking) {
        TimePickerDialog(
            initial = time,
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onChange(it)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SleepSection(rhythm: DailyRhythm, onChange: (DailyRhythm) -> Unit) {
    val plan = SleepPlan.fromWindows(rhythm.sleep)
    fun setPlan(newPlan: SleepPlan?) = onChange(rhythm.copy(sleep = newPlan?.toWindows().orEmpty()))

    SectionTitle(R.string.sleep_section)
    Explainer(R.string.sleep_explainer)
    SwitchRow(R.string.sleep_enabled, null, plan != null) { on ->
        setPlan(if (on) SleepPlan.Default else null)
    }
    if (plan == null) return

    TimeRow(R.string.sleep_bedtime, plan.weeknights.bedtime) {
        setPlan(plan.copy(weeknights = plan.weeknights.copy(bedtime = it)))
    }
    TimeRow(R.string.sleep_wake, plan.weeknights.wake) {
        setPlan(plan.copy(weeknights = plan.weeknights.copy(wake = it)))
    }
    SwitchRow(R.string.sleep_weekends, null, plan.weekendNights != null) { on ->
        setPlan(plan.copy(weekendNights = if (on) plan.weeknights else null))
    }
    plan.weekendNights?.let { weekend ->
        TimeRow(R.string.sleep_weekend_bedtime, weekend.bedtime) {
            setPlan(plan.copy(weekendNights = weekend.copy(bedtime = it)))
        }
        TimeRow(R.string.sleep_weekend_wake, weekend.wake) {
            setPlan(plan.copy(weekendNights = weekend.copy(wake = it)))
        }
    }
    SwitchRow(R.string.end_day, R.string.end_day_body, rhythm.endDayAtBedtime) {
        onChange(rhythm.copy(endDayAtBedtime = it))
    }
    if (rhythm.endDayAtBedtime) {
        SwitchRow(R.string.reminder_setting, R.string.reminder_setting_body, rhythm.morningReminder) {
            onChange(rhythm.copy(morningReminder = it))
        }
    }
}

@Composable
private fun QuietSection(rhythm: DailyRhythm, onChange: (DailyRhythm) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val times = TimeText.rememberLocalFormatter()

    SectionTitle(R.string.quiet_section)
    Explainer(R.string.quiet_explainer)
    rhythm.quiet.forEach { quiet ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
                    .selectable(selected = false, role = Role.Button) { editing = quiet.id },
            ) {
                Text(quiet.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        R.string.quiet_summary,
                        times(quiet.window.start),
                        times(quiet.window.end),
                        daysText(quiet.window.days),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = quiet.enabled,
                onCheckedChange = { on ->
                    val updated = rhythm.quiet.map { if (it.id == quiet.id) it.copy(enabled = on) else it }
                    onChange(rhythm.copy(quiet = updated))
                },
            )
            TextButton(onClick = { onChange(rhythm.copy(quiet = rhythm.quiet - quiet)) }) {
                Text(stringResource(R.string.quiet_remove))
            }
        }
    }
    OutlinedButton(
        onClick = { editing = NEW_QUIET },
        modifier = Modifier.fillMaxWidth().heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
    ) { Text(stringResource(R.string.quiet_add)) }

    editing?.let { id ->
        val existing = rhythm.quiet.firstOrNull { it.id == id }
        QuietWindowDialog(
            initial = existing,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                val others = rhythm.quiet.filterNot { it.id == saved.id }
                onChange(rhythm.copy(quiet = others + saved))
            },
        )
    }
}

@Composable
private fun QuietWindowDialog(
    initial: QuietWindow?,
    onDismiss: () -> Unit,
    onSave: (QuietWindow) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(initial?.window?.start ?: LocalTime.NOON) }
    var end by rememberSaveable { mutableStateOf(initial?.window?.end ?: LocalTime.of(13, 0)) }
    var days by rememberSaveable { mutableStateOf(initial?.window?.days ?: WEEKDAYS) }
    val errors = QuietWindowForm.errors(label, start, end, days)
    val locale = LocalConfiguration.current.locales[0]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quiet_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AiDailyMemoryTheme.spacing.sm)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.quiet_label)) },
                    singleLine = true,
                    isError = QuietWindowForm.Error.LABEL in errors,
                    supportingText = if (QuietWindowForm.Error.LABEL in errors) {
                        { Text(stringResource(R.string.quiet_label_error)) }
                    } else {
                        null
                    },
                )
                TimeRow(R.string.quiet_start, start) { start = it }
                TimeRow(R.string.quiet_end, end) { end = it }
                if (QuietWindowForm.Error.TIMES in errors) {
                    ErrorText(R.string.quiet_times_error)
                }
                Text(stringResource(R.string.quiet_days))
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(AiDailyMemoryTheme.spacing.xs),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
                if (QuietWindowForm.Error.DAYS in errors) {
                    ErrorText(R.string.quiet_days_error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = errors.isEmpty(),
                onClick = {
                    onSave(
                        QuietWindow(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            label = label.trim(),
                            window = DailyWindow(start, end, days),
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ErrorText(@StringRes text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun daysText(days: Set<DayOfWeek>): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (days) {
        DailyWindow.EVERY_DAY -> stringResource(R.string.quiet_every_day)
        WEEKDAYS -> stringResource(R.string.quiet_weekdays)
        else -> DayOfWeek.entries.filter { it in days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

@Composable
private fun AudioSection(
    retention: AudioRetention,
    bytesPerHour: Long,
    onChange: (AudioRetention) -> Unit,
) {
    val context = LocalContext.current
    SectionTitle(R.string.audio_section)
    Explainer(R.string.audio_explainer)
    Column(modifier = Modifier.selectableGroup()) {
        AudioRetention.entries.forEach { option ->
            val estimate = option.estimatedBytes(bytesPerHour, ASSUMED_HOURS_PER_DAY)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
                    .selectable(
                        selected = option == retention,
                        role = Role.RadioButton,
                        onClick = { onChange(option) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == retention, onClick = null)
                Column(modifier = Modifier.padding(start = AiDailyMemoryTheme.spacing.md)) {
                    Text(
                        stringResource(retentionTitle(option)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = estimate?.let {
                            val size = Formatter.formatShortFileSize(context, it)
                            stringResource(R.string.retention_estimate, size)
                        } ?: stringResource(R.string.retention_grows),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@StringRes
private fun retentionTitle(option: AudioRetention): Int = when (option) {
    AudioRetention.TRANSCRIPT_ONLY -> R.string.retention_transcript
    AudioRetention.KEEP_7_DAYS -> R.string.retention_7
    AudioRetention.KEEP_30_DAYS -> R.string.retention_30
    AudioRetention.KEEP_UNTIL_DELETED -> R.string.retention_forever
}

/** Validation for the quiet-time dialog, kept out of Compose so it's unit-testable. */
object QuietWindowForm {
    enum class Error { LABEL, TIMES, DAYS }

    fun errors(label: String, start: LocalTime, end: LocalTime, days: Set<DayOfWeek>): Set<Error> =
        buildSet {
            val trimmed = label.trim()
            if (trimmed.isEmpty() || trimmed.length > QuietWindow.MAX_LABEL) add(Error.LABEL)
            if (start == end) add(Error.TIMES)
            if (days.isEmpty()) add(Error.DAYS)
        }
}

private const val NEW_QUIET = "new"
private const val ASSUMED_HOURS_PER_DAY = 10.0
private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)
