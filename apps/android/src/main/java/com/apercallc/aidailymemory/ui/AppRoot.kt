package com.apercallc.aidailymemory.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.ui.sessions.SessionsScreen
import com.apercallc.aidailymemory.ui.sessions.SessionsViewModel
import com.apercallc.aidailymemory.ui.settings.RhythmSettingsScreen
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AppScreen { CAPTURE, SESSIONS, SETTINGS }

/**
 * Top-level screen switch. Three screens don't justify a navigation library; state survives
 * rotation and process recreation via rememberSaveable, and Back returns to capture.
 */
@Composable
fun AppRoot(
    capture: CaptureViewModel,
    sessions: () -> SessionsViewModel,
    settings: () -> SettingsViewModel,
    permissions: Permissions,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAPTURE) }
    BackHandler(enabled = screen != AppScreen.CAPTURE) { screen = AppScreen.CAPTURE }
    val back = { screen = AppScreen.CAPTURE }

    when (screen) {
        AppScreen.CAPTURE -> AiDailyMemoryApp(
            viewModel = capture,
            permissions = permissions,
            onOpenSessions = { screen = AppScreen.SESSIONS },
            onOpenSettings = { screen = AppScreen.SETTINGS },
        )
        AppScreen.SESSIONS -> SessionsRoute(sessions(), onBack = back)
        AppScreen.SETTINGS -> SettingsRoute(settings(), onBack = back)
    }
}

@Composable
private fun SessionsRoute(viewModel: SessionsViewModel, onBack: () -> Unit) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }
    SecondaryScreen(R.string.sessions_title, onBack, snackbar) { modifier ->
        SessionsScreen(
            days = days,
            today = LocalDate.now(),
            onRename = viewModel::rename,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val rhythm by viewModel.rhythm.collectAsStateWithLifecycle()
    val retention by viewModel.retention.collectAsStateWithLifecycle()
    val next = remember(rhythm) {
        SettingsViewModel.nextChange(rhythm, Instant.now(), ZoneId.systemDefault())
    }
    SecondaryScreen(R.string.nav_settings, onBack, snackbar = null) { modifier ->
        RhythmSettingsScreen(
            rhythm = rhythm,
            retention = retention,
            nextChange = next,
            bytesPerHour = viewModel.bytesPerHour,
            showUnreadableNotice = viewModel.hadUnreadableRhythm,
            onRhythmChange = viewModel::updateRhythm,
            onRetentionChange = viewModel::updateRetention,
            modifier = modifier,
        )
    }
}

@Composable
private fun SecondaryScreen(
    @StringRes title: Int,
    onBack: () -> Unit,
    snackbar: SnackbarHostState?,
    content: @Composable (Modifier) -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .safeDrawingPadding()
                .padding(horizontal = spacing.xxl, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.nav_back)) }
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            content(Modifier.fillMaxSize())
        }
    }
}
