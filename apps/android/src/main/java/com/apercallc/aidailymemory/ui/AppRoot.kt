package com.apercallc.aidailymemory.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.ui.sessions.SessionsScreen
import com.apercallc.aidailymemory.ui.sessions.SessionsViewModel
import com.apercallc.aidailymemory.ui.sessions.TranscriptScreen
import com.apercallc.aidailymemory.ui.memory.MemoryScreen
import com.apercallc.aidailymemory.ui.memory.MemoryViewModel
import com.apercallc.aidailymemory.ui.settings.NextChange
import com.apercallc.aidailymemory.ui.settings.AccountActions
import com.apercallc.aidailymemory.ui.settings.RhythmSettingsScreen
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import com.apercallc.aidailymemory.update.UpdateState
import com.apercallc.aidailymemory.update.UpdateViewModel
import java.time.Instant
import java.time.ZoneId

enum class AppScreen { CAPTURE, SESSIONS, MEMORY, SETTINGS, TRANSCRIPT }

private const val NEXT_CHANGE_REFRESH_MS = 30_000L

/**
 * Top-level screen switch. Three screens don't justify a navigation library; state survives
 * rotation and process recreation via rememberSaveable, and Back returns to capture.
 */
@Composable
fun AppRoot(
    capture: CaptureViewModel,
    sessions: () -> SessionsViewModel,
    memory: () -> MemoryViewModel,
    settings: () -> SettingsViewModel,
    updates: () -> UpdateViewModel,
    permissions: Permissions,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAPTURE) }
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    var openTitle by rememberSaveable { mutableStateOf("") }
    val back = {
        screen = if (screen == AppScreen.TRANSCRIPT) AppScreen.SESSIONS else AppScreen.CAPTURE
    }
    BackHandler(enabled = screen != AppScreen.CAPTURE) { back() }

    // Auto-check for updates once per process; the settings card can always re-check.
    LaunchedEffect(Unit) { updates().checkIfIdle() }

    when (screen) {
        AppScreen.CAPTURE -> AiDailyMemoryApp(
            viewModel = capture,
            updates = updates(),
            permissions = permissions,
            onOpenSessions = { screen = AppScreen.SESSIONS },
            onOpenMemory = { screen = AppScreen.MEMORY },
            onOpenSettings = { screen = AppScreen.SETTINGS },
        )
        AppScreen.SESSIONS -> SessionsRoute(sessions(), onBack = back) { id, title ->
            openSession = id
            openTitle = title
            screen = AppScreen.TRANSCRIPT
        }
        AppScreen.MEMORY -> MemoryRoute(memory(), onBack = back)
        AppScreen.SETTINGS -> SettingsRoute(settings(), updates(), onBack = back)
        AppScreen.TRANSCRIPT -> {
            val id = openSession
            if (id != null) {
                TranscriptRoute(sessions(), id, openTitle, onBack = back)
            } else {
                // Nothing to show (state lost): fall back to the list, outside composition.
                LaunchedEffect(Unit) { screen = AppScreen.SESSIONS }
            }
        }
    }
}

@Composable
private fun SessionsRoute(
    viewModel: SessionsViewModel,
    onBack: () -> Unit,
    onOpen: (sessionId: String, title: String) -> Unit,
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }
    SecondaryScreen(stringResource(R.string.sessions_title), onBack, snackbar) { modifier ->
        SessionsScreen(
            days = days,
            today = today,
            onRename = viewModel::rename,
            modifier = modifier,
            onOpen = onOpen,
            onDelete = viewModel::delete,
        )
    }
}

@Composable
private fun MemoryRoute(viewModel: MemoryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecondaryScreen(stringResource(R.string.memory_title), onBack, snackbar = null) { modifier ->
        MemoryScreen(state = state, onRetry = viewModel::retry, modifier = modifier)
    }
}

@Composable
private fun TranscriptRoute(viewModel: SessionsViewModel, sessionId: String, title: String, onBack: () -> Unit) {
    val lines by remember(sessionId) { viewModel.transcript(sessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    SecondaryScreen(title.ifEmpty { stringResource(R.string.transcript_title) }, onBack, snackbar = null) { modifier ->
        TranscriptScreen(lines = lines, modifier = modifier)
    }
}

@Composable
private fun SettingsRoute(
    viewModel: SettingsViewModel,
    updates: UpdateViewModel,
    onBack: () -> Unit,
) {
    val rhythm by viewModel.rhythm.collectAsStateWithLifecycle()
    val retention by viewModel.retention.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    // Recomputed on a slow tick too, so "Next: …" never shows a time that already passed.
    val next by produceState<NextChange?>(initialValue = null, rhythm) {
        while (true) {
            value = SettingsViewModel.nextChange(rhythm, Instant.now(), ZoneId.systemDefault())
            delay(NEXT_CHANGE_REFRESH_MS)
        }
    }
    SecondaryScreen(stringResource(R.string.nav_settings), onBack, snackbar = null) { modifier ->
        RhythmSettingsScreen(
            rhythm = rhythm,
            retention = retention,
            nextChange = next,
            bytesPerHour = viewModel.bytesPerHour,
            showUnreadableNotice = viewModel.hadUnreadableRhythm,
            onRhythmChange = viewModel::updateRhythm,
            onRetentionChange = viewModel::updateRetention,
            account = account,
            accountActions = remember(viewModel) {
                AccountActions(
                    connect = viewModel::connect,
                    edit = viewModel::clearConnectError,
                    disconnect = viewModel::disconnect,
                    retryUploads = viewModel::retryUploads,
                    changeStorageMode = viewModel::changeStorageMode,
                    retryStorageMode = viewModel::reloadStorageMode,
                )
            },
            update = update,
            onUpdateCheck = updates::check,
            onUpdateDownload = updates::download,
            onUpdateInstall = updates::install,
            processing = processing,
            processingActions = viewModel.processingActions,
            sync = sync,
            syncActions = viewModel.syncActions,
            modifier = modifier,
        )
    }
}

@Composable
private fun SecondaryScreen(
    title: String,
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
                // The Scaffold's padding already covers the bars it knows about; consume it so
                // safeDrawingPadding doesn't add the same insets a second time.
                .consumeWindowInsets(padding)
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
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            content(Modifier.fillMaxSize())
        }
    }
}
