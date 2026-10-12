package com.apercallc.aidailymemory.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.core.os.UserManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.update.UpdateState
import com.apercallc.aidailymemory.update.UpdateViewModel
import com.apercallc.aidailymemory.capture.CaptureOverview
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.permission.CapturePreconditionInputs
import com.apercallc.aidailymemory.recording.permission.CapturePreconditions
import com.apercallc.aidailymemory.recording.permission.PermissionFlow
import com.apercallc.aidailymemory.recording.permission.PermissionStep
import com.apercallc.aidailymemory.recording.permission.StartBlocker
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.Headline
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel
import com.apercallc.aidailymemory.service.RecordingService
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** What the permission dialog is currently asking for. */
private sealed interface StartDialog {
    data class Educate(val permission: String, @StringRes val title: Int, @StringRes val body: Int) :
        StartDialog
    data object OpenSettings : StartDialog
    data class Blocked(val blocker: StartBlocker) : StartDialog
}

/**
 * Stateful entry point: wires the ViewModel, the permission flow, and platform settings to the
 * stateless [CaptureScreen].
 */
@Composable
fun AiDailyMemoryApp(
    viewModel: CaptureViewModel,
    updates: UpdateViewModel,
    permissions: Permissions,
    onOpenSessions: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<StartDialog?>(null) }
    var batteryRestricted by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val power = context.getSystemService(PowerManager::class.java)
        batteryRestricted = !power.isIgnoringBatteryOptimizations(context.packageName)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(resources.getString(message)) }
    }

    // Update available: say so once per release, with a one-tap hop to the update section.
    var announcedUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(updates) {
        updates.state.collect { state ->
            if (state is UpdateState.Available && announcedUpdate != state.release.versionName) {
                announcedUpdate = state.release.versionName
                val result = snackbar.showSnackbar(
                    resources.getString(R.string.update_snackbar, state.release.versionName),
                    actionLabel = resources.getString(R.string.update_install),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) onOpenSettings()
            }
        }
    }

    fun tryStart() {
        val host = activity ?: return
        dialog = nextStartStep(host, permissions, viewModel.storageSnapshot)
        if (dialog == null) viewModel.send(RecordingService.ACTION_START)
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Continue to the next requirement (e.g. notifications after the microphone). A denial
        // stops here; the next Start tap explains again or points to Settings.
        if (granted) tryStart()
    }

    CaptureScreen(
        model = state.model,
        overview = state.overview,
        batteryRestricted = batteryRestricted,
        snackbar = snackbar,
        onPrimaryAction = { action ->
            when (action) {
                PrimaryAction.START -> tryStart()
                PrimaryAction.PAUSE -> viewModel.send(RecordingService.ACTION_PAUSE)
                PrimaryAction.RESUME -> viewModel.send(RecordingService.ACTION_RESUME)
                PrimaryAction.NONE -> Unit
            }
        },
        onRetry = { viewModel.send(RecordingService.ACTION_RESUME) },
        onStop = { viewModel.send(RecordingService.ACTION_STOP) },
        onMark = viewModel::mark,
        onBatterySettings = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        onOpenSessions = onOpenSessions,
        onOpenMemory = onOpenMemory,
        onOpenSettings = onOpenSettings,
    )

    state.stopDecisionSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.stop_decision_title)) },
            text = { Text(stringResource(R.string.stop_decision_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.keepStoppedSession(sessionId) }) {
                    Text(stringResource(R.string.stop_decision_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.deleteStoppedSession(sessionId) }) {
                    Text(stringResource(R.string.session_delete_action))
                }
            },
        )
    }

    dialog?.let { current ->
        StartDialogContent(
            dialog = current,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                when (current) {
                    is StartDialog.Educate -> {
                        permissions.markRequested(current.permission)
                        requestPermission.launch(current.permission)
                    }
                    StartDialog.OpenSettings -> context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                    is StartDialog.Blocked -> Unit
                }
            },
        )
    }
}

/** The first thing standing between the user and a recording, or null if nothing does. */
private fun nextStartStep(
    activity: Activity,
    permissions: Permissions,
    storage: StorageHealth?,
): StartDialog? {
    val microphone = permissions.microphone(activity)
    val notifications = permissions.notifications(activity)
    val blockers = CapturePreconditions.blockers(
        CapturePreconditionInputs(
            userUnlocked = UserManagerCompat.isUserUnlocked(activity),
            microphone = microphone,
            notifications = notifications,
            storage = storage ?: StorageHealth(StorageLevel.UNKNOWN, null, pendingOverBudget = false),
        ),
    )
    return when (val blocker = blockers.firstOrNull()) {
        null -> null
        StartBlocker.MICROPHONE_PERMISSION -> permissionDialog(
            PermissionFlow.next(microphone),
            android.Manifest.permission.RECORD_AUDIO,
            R.string.permission_mic_title,
            R.string.permission_mic_body,
        )
        StartBlocker.NOTIFICATION_PERMISSION -> permissionDialog(
            PermissionFlow.next(notifications),
            checkNotNull(Permissions.NOTIFICATIONS),
            R.string.permission_notifications_title,
            R.string.permission_notifications_body,
        )
        StartBlocker.DEVICE_LOCKED, StartBlocker.STORAGE_FULL -> StartDialog.Blocked(blocker)
    }
}

private fun permissionDialog(
    step: PermissionStep,
    permission: String,
    @StringRes title: Int,
    @StringRes body: Int,
): StartDialog? = when (step) {
    PermissionStep.GRANTED -> null
    PermissionStep.EDUCATE_THEN_REQUEST -> StartDialog.Educate(permission, title, body)
    PermissionStep.OPEN_SETTINGS -> StartDialog.OpenSettings
}

@Composable
private fun StartDialogContent(dialog: StartDialog, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, confirm) = when (dialog) {
        is StartDialog.Educate -> Triple(dialog.title, dialog.body, R.string.action_continue)
        StartDialog.OpenSettings -> Triple(
            R.string.permission_settings_title,
            R.string.permission_settings_body,
            R.string.action_open_settings,
        )
        is StartDialog.Blocked -> Triple(
            R.string.status_start_failed,
            CaptureStrings.blocker(dialog.blocker),
            R.string.action_ok,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = if (dialog is StartDialog.Blocked) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } }
        },
    )
}

/** Stateless: renders the capture state and reports user intents upward. */
@Composable
fun CaptureScreen(
    model: CaptureStatusModel,
    overview: CaptureOverview?,
    batteryRestricted: Boolean,
    snackbar: SnackbarHostState,
    onPrimaryAction: (PrimaryAction) -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onMark: (MarkerKind) -> Unit,
    onBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSessions: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val spacing = AiDailyMemoryTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .safeDrawingPadding()
                // Scrolls instead of clipping at 200% font scale.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xxl, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CaptureStatusCard(model = model, overview = overview)

            if (model.primaryAction != PrimaryAction.NONE) {
                PrimaryActionButton(model = model, onClick = onPrimaryAction)
            }
            if (model.canRetry) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                    onClick = onRetry,
                ) { Text(stringResource(R.string.action_retry)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
                    enabled = model.canMark,
                    onClick = { onMark(MarkerKind.HIGHLIGHT) },
                ) { Text(stringResource(R.string.action_highlight)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
                    enabled = model.canMark,
                    onClick = { onMark(MarkerKind.QUICK_NOTE) },
                ) { Text(stringResource(R.string.action_quick_note)) }
            }

            if (model.canStop) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                    onClick = onStop,
                ) { Text(stringResource(R.string.action_end_session)) }
            }

            if (batteryRestricted) BatteryCard(onBatterySettings)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
                    onClick = onOpenSessions,
                ) { Text(stringResource(R.string.nav_sessions)) }
                TextButton(
                    modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
                    onClick = onOpenMemory,
                ) { Text(stringResource(R.string.nav_memory)) }
                TextButton(
                    modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
                    onClick = onOpenSettings,
                ) { Text(stringResource(R.string.nav_settings)) }
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(model: CaptureStatusModel, onClick: (PrimaryAction) -> Unit) {
    val status = AiDailyMemoryTheme.statusColors
    val colors = if (model.headline == Headline.RECORDING) {
        // While audio is really being captured, Pause (the privacy control) takes the
        // recording color so it is unmistakable. Never while nothing is being captured.
        ButtonDefaults.buttonColors(
            containerColor = status.recording,
            contentColor = status.onRecording,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
        colors = colors,
        onClick = { onClick(model.primaryAction) },
    ) {
        Text(
            stringResource(
                when (model.primaryAction) {
                    PrimaryAction.PAUSE -> R.string.action_pause
                    PrimaryAction.RESUME -> R.string.action_resume
                    PrimaryAction.START, PrimaryAction.NONE -> R.string.action_start
                },
            ),
        )
    }
}

@Composable
private fun CaptureStatusCard(model: CaptureStatusModel, overview: CaptureOverview?) {
    val status = AiDailyMemoryTheme.statusColors
    val spacing = AiDailyMemoryTheme.spacing

    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Headline and detail are read as one unit and announced when they change:
            // recording state must never be silent for TalkBack users. The stats below are
            // outside this live region so their minute-by-minute updates are not re-read.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Decorative: the headline text carries the meaning, so color is never
                    // the only signal.
                    Box(
                        modifier = Modifier
                            .size(spacing.statusDot)
                            .background(color = status.container(model.tone), shape = CircleShape),
                    )
                    Text(
                        text = stringResource(CaptureStrings.headline(model.headline)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = stringResource(CaptureStrings.detail(model)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            overview?.let { CaptureStats(it) }
        }
    }
}

@Composable
private fun CaptureStats(overview: CaptureOverview) {
    val summary = overview.summary
    if (summary.capturedMs > 0) {
        Stat(R.string.stat_captured, durationText(summary.capturedMs))
    }
    if (summary.pendingUploadMs > 0) {
        Stat(R.string.stat_pending, durationText(summary.pendingUploadMs))
    }
    overview.lastSavedAgoMs?.let { ago ->
        Stat(
            R.string.stat_last_saved,
            stringResource(R.string.stat_last_saved_value, durationText(ago)),
        )
    }
    Stat(
        R.string.stat_storage_left,
        overview.storage.recordingMinutesLeft?.let { durationText(it * MS_PER_MINUTE) }
            ?: stringResource(R.string.stat_storage_unknown),
    )
    if (overview.gaps.isNotEmpty()) {
        Text(
            text = pluralStringResource(
                R.plurals.stat_gaps,
                overview.gaps.size,
                overview.gaps.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AiDailyMemoryTheme.statusColors.warning,
        )
        Text(
            text = stringResource(R.string.stat_gaps_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(@StringRes label: Int, value: String) {
    // One wrapping Text, not a FlowRow: a FlowRow measures the value against the label's
    // leftover line width and never remeasures when it moves the item to its own line, so
    // at large font scales the value is laid out ~10px wide and visually overflows. One
    // annotated string wraps as a whole and stays a single TalkBack announcement.
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(stringResource(label))
            }
            append("  ")
            append(value)
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun durationText(ms: Long): String {
    val minutes = ms / MS_PER_MINUTE
    return when {
        minutes < 1 -> stringResource(R.string.duration_under_minute)
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(
            R.string.duration_hours_minutes,
            minutes / MINUTES_PER_HOUR,
            minutes % MINUTES_PER_HOUR,
        )
    }
}

@Composable
private fun BatteryCard(onBatterySettings: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(shape = RoundedCornerShape(spacing.cardCorner)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.battery_body))
            TextButton(
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                onClick = onBatterySettings,
            ) { Text(stringResource(R.string.action_battery_settings)) }
        }
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
