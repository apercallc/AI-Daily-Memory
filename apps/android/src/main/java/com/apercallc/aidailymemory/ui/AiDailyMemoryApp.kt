package com.apercallc.aidailymemory.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.presentation.IndicatorVisibility
import com.apercallc.aidailymemory.recording.presentation.IndicatorVisibilityPolicy
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.recording.presentation.StatusTone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.apercallc.aidailymemory.ondevice.ModelState
import com.apercallc.aidailymemory.recording.permission.BatteryGuidance
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin
import com.apercallc.aidailymemory.recording.permission.CapturePreconditionInputs
import com.apercallc.aidailymemory.recording.permission.DeviceVendor
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
    onOpenSettings: () -> Unit = {},
    summariesConfigured: Boolean = false,
    onOpenProcessingSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val speechModelPrompt by viewModel.speechModelPrompt.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<StartDialog?>(null) }
    var batteryRestricted by remember { mutableStateOf(false) }
    var indicator by remember { mutableStateOf(IndicatorVisibility.VISIBLE) }
    val scope = rememberCoroutineScope()
    fun notify(@StringRes message: Int) {
        scope.launch { snackbar.showSnackbar(resources.getString(message)) }
    }
    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            logFailure("AiDailyMemoryApp", "settings_screen_unavailable", e)
            notify(R.string.settings_screen_unavailable)
        } catch (e: SecurityException) {
            logFailure("AiDailyMemoryApp", "settings_screen_denied", e)
            notify(R.string.settings_screen_unavailable)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val power = context.getSystemService(PowerManager::class.java)
        batteryRestricted = !power.isIgnoringBatteryOptimizations(context.packageName)
        indicator = (context.applicationContext as AiDailyMemoryApplication).container.notifications
            .indicatorVisibility()
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
        if (granted) tryStart() else notify(R.string.permission_denied_feedback)
    }

    CaptureScreen(
        model = state.model,
        overview = state.overview,
        origin = state.origin,
        batteryRestricted = batteryRestricted,
        speechModel = speechModelPrompt,
        onDownloadSpeechModel = viewModel::downloadSpeechModel,
        summariesConfigured = summariesConfigured,
        onOpenProcessingSettings = onOpenProcessingSettings,
        indicator = indicator,
        onNotificationSettings = {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
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
        onAppBatterySettings = {
            open(appBatterySettingsIntent(context.packageName))
        },
        vendor = remember { BatteryGuidance.vendorOf(Build.MANUFACTURER) },
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
                    StartDialog.OpenSettings -> open(
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
    onAppBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    vendor: DeviceVendor = DeviceVendor.OTHER,
    indicator: IndicatorVisibility = IndicatorVisibility.VISIBLE,
    onNotificationSettings: () -> Unit = {},
    origin: RecordingOrigin? = null,
    speechModel: ModelState? = null,
    onDownloadSpeechModel: () -> Unit = {},
    summariesConfigured: Boolean = false,
    onOpenProcessingSettings: () -> Unit = {},
) {
    val spacing = AiDailyMemoryTheme.spacing
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                // The Scaffold's padding already covers the bars it knows about; consume it so
                // safeDrawingPadding doesn't add the same insets a second time.
                .consumeWindowInsets(padding)
                .safeDrawingPadding()
                // Scrolls instead of clipping at 200% font scale.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xxl, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (IndicatorVisibilityPolicy.showBanner(indicator, model)) {
                IndicatorWarningCard(indicator, onNotificationSettings)
            }
            // Above the status card on purpose: it is the one setting that silently ends a day
            // of recording, so it must not hide below the fold.
            if (batteryRestricted) BatteryCard(vendor, onAppBatterySettings)
            speechModel?.let { SpeechModelCard(it, onDownloadSpeechModel) }
            CaptureStatusCard(model = model, overview = overview)
            // Only while a session is open: it says whose button started the recording.
            if (origin != null && model.canStop) {
                Text(
                    text = stringResource(
                        if (origin == RecordingOrigin.WATCH) R.string.origin_watch else R.string.origin_phone,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                MarkerButton(
                    modifier = Modifier.weight(1f),
                    label = R.string.action_highlight,
                    hint = R.string.action_highlight_hint,
                    enabled = model.canMark,
                    onClick = { onMark(MarkerKind.HIGHLIGHT) },
                )
                MarkerButton(
                    modifier = Modifier.weight(1f),
                    label = R.string.action_quick_note,
                    hint = R.string.action_quick_note_hint,
                    enabled = model.canMark,
                    onClick = { onMark(MarkerKind.QUICK_NOTE) },
                )
            }

            if (model.canStop) {
                // Fully-styled danger button: matches the watch's Stop control for the same
                // destructive action, and the errorContainer/onErrorContainer danger tokens
                // used elsewhere in this app (e.g. the error banner card below).
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    onClick = onStop,
                ) { Text(stringResource(R.string.action_end_session)) }
            }

            if (!summariesConfigured && !setupDismissed && !model.canStop) {
                MemorySetupCard(
                    onSetup = onOpenProcessingSettings,
                    onDismiss = { setupDismissed = true },
                )
            }
        }
    }
}

/** A tonal marker button with a one-line caption saying exactly which audio it flags. */
@Composable
private fun MarkerButton(
    @StringRes label: Int,
    @StringRes hint: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(label)) }
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
        modifier = Modifier.fillMaxWidth().heightIn(min = PRIMARY_BUTTON_HEIGHT),
        colors = colors,
        onClick = { onClick(model.primaryAction) },
    ) {
        Text(
            text = stringResource(
                when (model.primaryAction) {
                    PrimaryAction.PAUSE -> R.string.action_pause
                    PrimaryAction.RESUME -> R.string.action_resume
                    PrimaryAction.START, PrimaryAction.NONE -> R.string.action_start
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun CaptureStatusCard(model: CaptureStatusModel, overview: CaptureOverview?) {
    val status = AiDailyMemoryTheme.statusColors
    val spacing = AiDailyMemoryTheme.spacing
    val live = model.headline == Headline.RECORDING
    val accent = when (model.tone) {
        StatusTone.RECORDING, StatusTone.ERROR, StatusTone.WARNING -> status.container(model.tone)
        else -> null
    }

    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = accent?.let { BorderStroke(spacing.accentBorder, it.copy(alpha = CARD_ACCENT_ALPHA)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // Headline and detail are read as one unit and announced when they change:
            // recording state must never be silent for TalkBack users. The stats below are
            // outside this live region so their minute-by-minute updates are not re-read.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Decorative: the headline text carries the meaning, so color is never
                    // the only signal.
                    StatusDot(
                        color = if (live) status.recordingDot else status.container(model.tone),
                        pulsing = live,
                    )
                    Text(
                        text = stringResource(CaptureStrings.headline(model.headline)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    text = stringResource(CaptureStrings.detail(model)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            overview?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CaptureStats(it)
            }
        }
    }
}

/**
 * The state dot beside the headline. While recording it pulses so a glance at the screen shows
 * the microphone is live; the motion is decoration, the headline still says "Recording".
 */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val dot = AiDailyMemoryTheme.spacing.statusDot
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(dot * 2)) {
        if (pulsing) PulseHalo(color, dot)
        Box(modifier = Modifier.size(dot).background(color = color, shape = CircleShape))
    }
}

@Composable
private fun PulseHalo(color: Color, dot: Dp) {
    val progress by rememberInfiniteTransition(label = "recording-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)),
        label = "recording-pulse-progress",
    )
    Box(
        modifier = Modifier
            .size(dot * (1f + progress))
            .graphicsLayer { alpha = PULSE_START_ALPHA * (1f - progress) }
            .background(color = color, shape = CircleShape),
    )
}

@Composable
private fun CaptureStats(overview: CaptureOverview) {
    val summary = overview.summary
    val spacing = AiDailyMemoryTheme.spacing
    val tiles = buildList {
        if (summary.capturedMs > 0) add(R.string.stat_captured to durationText(summary.capturedMs))
        if (summary.pendingUploadMs > 0) add(R.string.stat_pending to durationText(summary.pendingUploadMs))
        overview.lastSavedAgoMs?.let { ago ->
            add(
                R.string.stat_last_saved to
                    stringResource(R.string.stat_last_saved_value, durationText(ago)),
            )
        }
        add(
            R.string.stat_storage_left to (
                overview.storage.recordingMinutesLeft?.let { durationText(it * MS_PER_MINUTE) }
                    ?: stringResource(R.string.stat_storage_unknown)
                ),
        )
    }
    // Two columns normally; one at large font scales, where a value no longer fits beside its
    // neighbour and would otherwise wrap one character per line.
    val columns = if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        tiles.chunked(columns).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                rowTiles.forEach { (label, value) ->
                    Stat(label, value, Modifier.weight(1f))
                }
                if (rowTiles.size < columns) Box(Modifier.weight((columns - rowTiles.size).toFloat()))
            }
        }
    }
    if (overview.gaps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
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
}

@Composable
private fun Stat(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    // One wrapping Text, not a FlowRow: a FlowRow measures the value against the label's
    // leftover line width and never remeasures when it moves the item to its own line, so
    // at large font scales the value is laid out ~10px wide and visually overflows. One
    // annotated string wraps as a whole and stays a single TalkBack announcement.
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                ),
            ) {
                append(stringResource(label))
            }
            append("\n")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
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
private fun IndicatorWarningCard(indicator: IndicatorVisibility, onSettings: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    val body = when (indicator) {
        IndicatorVisibility.NOTIFICATIONS_OFF -> R.string.indicator_off_body
        IndicatorVisibility.CHANNEL_BLOCKED -> R.string.indicator_blocked_body
        else -> R.string.indicator_lowered_body
    }
    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.indicator_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(body), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                onClick = onSettings,
            ) { Text(stringResource(R.string.indicator_open_settings)) }
        }
    }
}

/** Nothing is transcribed until the free model is on the phone, so say so on the first screen. */
@Composable
private fun SpeechModelCard(state: ModelState, onDownload: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.speech_model_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.speech_model_card_body))
            when (state) {
                is ModelState.Downloading -> Text(
                    text = stringResource(R.string.processing_model_downloading, state.percent),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                else -> {
                    if (state is ModelState.Failed) {
                        Text(
                            text = stringResource(R.string.speech_model_card_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(
                        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                        onClick = onDownload,
                    ) { Text(stringResource(R.string.speech_model_card_action)) }
                }
            }
        }
    }
}

@Composable
private fun MemorySetupCard(onSetup: () -> Unit, onDismiss: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.memory_setup_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.memory_setup_body))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Button(
                    onClick = onSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.memory_setup_action)) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

@Composable
private fun BatteryCard(
    vendor: DeviceVendor,
    onAppBatterySettings: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    val status = AiDailyMemoryTheme.statusColors
    Card(
        shape = RoundedCornerShape(spacing.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        border = BorderStroke(spacing.accentBorder, status.warning.copy(alpha = CARD_ACCENT_ALPHA)),
    ) {
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
            Text(
                text = stringResource(
                    if (vendor == DeviceVendor.SAMSUNG) R.string.battery_steps_samsung else R.string.battery_steps,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Button(
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                onClick = onAppBatterySettings,
            ) { Text(stringResource(R.string.action_app_battery_settings)) }
        }
    }
}

internal fun appBatterySettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val PULSE_MS = 1_400
private const val PULSE_START_ALPHA = 0.55f
private const val LARGE_FONT_SCALE = 1.3f
private const val CARD_ACCENT_ALPHA = 0.6f
private val PRIMARY_BUTTON_HEIGHT = 60.dp
