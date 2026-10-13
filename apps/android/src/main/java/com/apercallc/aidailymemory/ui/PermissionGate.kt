package com.apercallc.aidailymemory.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.recording.permission.PermissionFlow
import com.apercallc.aidailymemory.recording.permission.PermissionStep
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** What the permission screens need: current answers and the two actions. */
class PermissionState(
    val micGranted: Boolean,
    val notificationsGranted: Boolean,
    /** Android won't show its prompt again; only app settings can grant it now. */
    val needsSettings: Boolean,
    val allow: () -> Unit,
    val openSettings: () -> Unit,
) {
    val allGranted: Boolean get() = micGranted && notificationsGranted
}

/**
 * Tracks the microphone and notification permissions and re-reads them whenever the app comes
 * back to the front or an answer arrives. With [autoRequest] it also shows the system prompt for
 * the first missing permission, once each, as soon as it is true.
 */
@Composable
fun rememberPermissionState(permissions: Permissions, autoRequest: Boolean): PermissionState {
    val activity = LocalActivity.current
    val context = LocalContext.current
    // Bumped on every return to the app and every answer, so the checks below run again.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val micGranted = remember(refresh) { permissions.microphoneGranted }
    val notificationsGranted = remember(refresh) { permissions.notificationsGranted }
    val permission = when {
        !micGranted -> Manifest.permission.RECORD_AUDIO
        !notificationsGranted -> checkNotNull(Permissions.NOTIFICATIONS)
        else -> null
    }
    val step = remember(refresh, micGranted, notificationsGranted) {
        when {
            permission == null -> PermissionStep.GRANTED
            activity == null -> PermissionStep.EDUCATE_THEN_REQUEST
            !micGranted -> PermissionFlow.next(permissions.microphone(activity))
            else -> PermissionFlow.next(permissions.notifications(activity))
        }
    }
    val request = {
        if (permission != null) {
            permissions.markRequested(permission)
            launcher.launch(permission)
        }
    }

    // Ask once per permission; after that the person taps the button.
    var askedFor by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(autoRequest, permission, step) {
        if (autoRequest && permission != null && askedFor != permission && step == PermissionStep.EDUCATE_THEN_REQUEST) {
            askedFor = permission
            request()
        }
    }
    return PermissionState(
        micGranted = micGranted,
        notificationsGranted = notificationsGranted,
        needsSettings = step == PermissionStep.OPEN_SETTINGS,
        allow = request,
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}

/**
 * The blocker for everyone who is past the first-run guide: asks for the microphone and
 * notification permissions as soon as the app opens and keeps this screen up until both are
 * allowed. Without them nothing can record, and a recording must always show its notification.
 * A screen of its own, not a dialog, so it can't be dismissed.
 *
 * Never shown while a session is open ([sessionOpen]): the recording indicator and its controls
 * must stay reachable. Returns true when it drew the blocker, so the caller shows nothing else.
 */
@Composable
fun showPermissionGate(permissions: Permissions, sessionOpen: Boolean): Boolean {
    val state = rememberPermissionState(permissions, autoRequest = !sessionOpen)
    if (sessionOpen || state.allGranted) return false
    PermissionGateScreen(
        micGranted = state.micGranted,
        notificationsGranted = state.notificationsGranted,
        needsSettings = state.needsSettings,
        onAllow = state.allow,
        onOpenSettings = state.openSettings,
    )
    return true
}

/** Stateless: what is allowed, what is still needed, and the one button that moves forward. */
@Composable
fun PermissionGateScreen(
    micGranted: Boolean,
    notificationsGranted: Boolean,
    needsSettings: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = spacing.xxl, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.permission_gate_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.permission_gate_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                PermissionNeed(R.string.permission_mic_title, R.string.permission_mic_body, micGranted)
                PermissionNeed(R.string.permission_notifications_title, R.string.permission_notifications_body, notificationsGranted)
                if (needsSettings) {
                    Text(
                        text = stringResource(R.string.permission_settings_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(
                onClick = if (needsSettings) onOpenSettings else onAllow,
                modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
            ) {
                Text(stringResource(if (needsSettings) R.string.action_open_settings else R.string.permission_gate_allow))
            }
        }
    }
}

@Composable
internal fun PermissionNeed(title: Int, body: Int, granted: Boolean) {
    val spacing = AiDailyMemoryTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalAlignment = Alignment.Top) {
        Text(
            text = stringResource(if (granted) R.string.permission_gate_allowed else R.string.permission_gate_needed),
            style = MaterialTheme.typography.labelLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = spacing.xs),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
