package com.apercallc.aidailymemory.ui.settings

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.schedule.QuietWindow
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.recording.storage.MemoryRetention
import com.apercallc.aidailymemory.update.UpdateState
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
    memoryRetention: MemoryRetention = MemoryRetention.Default,
    onMemoryRetentionChange: (MemoryRetention) -> Unit = {},
    account: AccountUi? = null,
    accountCallbacks: AccountCallbacks = AccountCallbacks(),
    themeMode: ThemeMode = ThemeMode.Default,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    update: UpdateState = UpdateState.Idle,
    onUpdateCheck: () -> Unit = {},
    onUpdateDownload: () -> Unit = {},
    onUpdateInstall: () -> Unit = {},
    processing: ProcessingUi = ProcessingUi(),
    processingActions: ProcessingActions = ProcessingActions(),
    sync: SyncUi = SyncUi(),
    syncActions: SyncActions = SyncActions(),
    processingExpandRequest: Int = 0,
    accountFocusRequest: Int = 0,
    onProcessingExpandConsumed: () -> Unit = {},
    onAccountFocusConsumed: () -> Unit = {},
) {
    val spacing = AiDailyMemoryTheme.spacing
    val processingBringIntoView = remember { BringIntoViewRequester() }
    val accountBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(processingExpandRequest) {
        if (processingExpandRequest > 0) {
            // Let the card expand before asking the scroll container to reveal it.
            withFrameNanos { }
            processingBringIntoView.bringIntoView()
            onProcessingExpandConsumed()
        }
    }
    LaunchedEffect(accountFocusRequest) {
        if (accountFocusRequest > 0) {
            accountBringIntoView.bringIntoView()
            onAccountFocusConsumed()
        }
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        if (showUnreadableNotice) Notice(R.string.schedule_unreadable)
        nextChange?.let { NextChangeLine(it) }

        GroupLabel(R.string.settings_group_schedule)
        GroupCard { SleepSection(rhythm, onRhythmChange) }
        GroupCard { QuietSection(rhythm, onRhythmChange) }

        GroupLabel(R.string.settings_group_audio)
        GroupCard { AudioSection(retention, bytesPerHour, onRetentionChange) }

        GroupLabel(R.string.settings_group_memory)
        GroupCard { MemorySection(memoryRetention, onMemoryRetentionChange) }

        GroupLabel(R.string.settings_group_appearance)
        GroupCard { AppearanceSection(themeMode, onThemeModeChange) }

        // One server, and only one: the account first, then what it does for the phone.
        GroupLabel(R.string.settings_group_account)
        account?.let {
            GroupCard(modifier = Modifier.bringIntoViewRequester(accountBringIntoView)) {
                AccountSection(account = it, callbacks = accountCallbacks)
            }
        }
        ExpandableCard(
            title = R.string.processing_section,
            modifier = Modifier.bringIntoViewRequester(processingBringIntoView),
            summary = when {
                !processing.enabled -> R.string.processing_status_off
                ConfigGap.TRANSCRIPTION_MODEL in processing.configGaps -> R.string.processing_status_model_missing
                processing.configGaps.isNotEmpty() -> R.string.processing_status_incomplete
                processing.transcriptionOwnKey -> R.string.processing_status_own
                else -> R.string.processing_status_phone
            },
            initiallyExpanded = processing.enabled && processing.configGaps.isNotEmpty(),
            expandRequest = processingExpandRequest,
        ) {
            ProcessingSettingsSection(
                processing = processing,
                actions = processingActions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ExpandableCard(
            title = R.string.sync_section,
            summary = when {
                !sync.enabled -> R.string.sync_status_off
                sync.connected -> R.string.sync_status_on
                else -> R.string.sync_status_waiting
            },
            label = R.string.advanced_label,
            initiallyExpanded = sync.enabled && sync.lastSyncFailed,
        ) {
            SyncSettingsSection(
                sync = sync,
                actions = syncActions,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GroupLabel(R.string.settings_group_app)
        GroupCard {
            UpdateSection(
                state = update,
                onCheck = onUpdateCheck,
                onDownload = onUpdateDownload,
                onInstall = onUpdateInstall,
            )
        }
    }
}

/** Light, dark, or follow the phone (the default). */
@Composable
private fun AppearanceSection(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Text(
        text = stringResource(R.string.theme_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        ).forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minTouchTarget)
                    .selectable(
                        selected = mode == value,
                        role = Role.RadioButton,
                        onClick = { if (mode != value) onChange(value) },
                    ),
            ) {
                RadioButton(selected = mode == value, onClick = null)
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Small overline above a run of cards; headings let TalkBack users jump between groups. */
@Composable
private fun GroupLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = AiDailyMemoryTheme.spacing.sm)
            .semantics { heading() },
    )
}

/** A rounded surface that holds one settings topic. */
@Composable
private fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            content = content,
        )
    }
}

/**
 * A topic that most people never need to open: collapsed it shows only its title and whether
 * it is on, so the screen stays short.
 */
@Composable
private fun ExpandableCard(
    @StringRes title: Int,
    @StringRes summary: Int,
    modifier: Modifier = Modifier,
    @StringRes label: Int? = null,
    initiallyExpanded: Boolean = false,
    expandRequest: Int = 0,
    content: @Composable () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(expandRequest) {
        if (expandRequest > 0) expanded = true
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            val toggleLabel = stringResource(if (expanded) R.string.settings_collapse else R.string.settings_expand)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minTouchTarget)
                    .clickable(onClickLabel = toggleLabel, role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    label?.let {
                        Text(
                            text = stringResource(it).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = toggleLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) content()
        }
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
    Card(
        shape = RoundedCornerShape(AiDailyMemoryTheme.spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AiDailyMemoryTheme.spacing.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(AiDailyMemoryTheme.spacing.lg),
        )
    }
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
            title = label,
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
    @StringRes title: Int,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    // The clock dial has a fixed size and clips at large font scales; typing always fits.
    val largeText = LocalDensity.current.fontScale > LARGE_FONT_SCALE
    var typing by rememberSaveable { mutableStateOf(largeText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AiDailyMemoryTheme.spacing.sm)) {
                if (typing) TimeInput(state = state) else TimePicker(state = state)
                TextButton(
                    onClick = { typing = !typing },
                    modifier = Modifier.heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
                ) {
                    Text(stringResource(if (typing) R.string.time_use_dial else R.string.time_type_in))
                }
            }
        },
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
    if (plan == null && rhythm.sleep.isNotEmpty()) {
        // Windows this screen can't show are still active: say so rather than show "off".
        Notice(R.string.sleep_custom)
        OutlinedButton(
            onClick = { setPlan(SleepPlan.Default) },
            modifier = Modifier.heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
        ) { Text(stringResource(R.string.sleep_custom_reset)) }
        return
    }
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
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
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
                    .clickable(
                        onClickLabel = stringResource(R.string.quiet_edit_label, quiet.label),
                        role = Role.Button,
                    ) { editing = quiet.id },
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
            val toggleLabel = stringResource(R.string.quiet_toggle_label, quiet.label)
            Switch(
                modifier = Modifier.semantics { contentDescription = toggleLabel },
                checked = quiet.enabled,
                onCheckedChange = { on ->
                    val updated = rhythm.quiet.map { if (it.id == quiet.id) it.copy(enabled = on) else it }
                    onChange(rhythm.copy(quiet = updated))
                },
            )
            val removeLabel = stringResource(R.string.quiet_remove_label, quiet.label)
            TextButton(
                onClick = { removing = quiet.id },
                modifier = Modifier
                    .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
                    .semantics { contentDescription = removeLabel },
            ) {
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
                // Edited items keep their place; new ones go last.
                val updated = if (rhythm.quiet.any { it.id == saved.id }) {
                    rhythm.quiet.map { if (it.id == saved.id) saved else it }
                } else {
                    rhythm.quiet + saved
                }
                onChange(rhythm.copy(quiet = updated))
            },
        )
    }

    rhythm.quiet.firstOrNull { it.id == removing }?.let { quiet ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.quiet_remove_title, quiet.label)) },
            text = { Text(stringResource(R.string.quiet_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onChange(rhythm.copy(quiet = rhythm.quiet - quiet))
                }) { Text(stringResource(R.string.quiet_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) }
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
    Explainer(R.string.audio_not_yet_deleted)
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

@Composable
private fun MemorySection(
    memoryRetention: MemoryRetention,
    onChange: (MemoryRetention) -> Unit,
) {
    SectionTitle(R.string.memory_section)
    Explainer(R.string.memory_retention_explainer)
    Column(modifier = Modifier.selectableGroup()) {
        MemoryRetention.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
                    .selectable(
                        selected = option == memoryRetention,
                        role = Role.RadioButton,
                        onClick = { onChange(option) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == memoryRetention, onClick = null)
                Text(
                    stringResource(memoryRetentionTitle(option)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = AiDailyMemoryTheme.spacing.md),
                )
            }
        }
    }
}

@StringRes
private fun memoryRetentionTitle(option: MemoryRetention): Int = when (option) {
    MemoryRetention.KEEP_90_DAYS -> R.string.memory_retention_90
    MemoryRetention.KEEP_1_YEAR -> R.string.memory_retention_1_year
    MemoryRetention.KEEP_UNTIL_DELETED -> R.string.memory_retention_forever
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
private const val LARGE_FONT_SCALE = 1.3f
private const val ASSUMED_HOURS_PER_DAY = 10.0
private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)
