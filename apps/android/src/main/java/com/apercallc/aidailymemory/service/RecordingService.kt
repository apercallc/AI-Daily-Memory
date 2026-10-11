package com.apercallc.aidailymemory.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.capture.EffectHandler
import com.apercallc.aidailymemory.platform.DebugTelemetry
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.effects.EngineSignals
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Microphone foreground service (ADR-0010). It owns no state: commands arrive as intents
 * (from the app, notification, or watch), go through the controller, and the
 * service only executes the effects the controller hands back.
 *
 * Lifecycle rules:
 * - `startForeground` is the first call for every command, well inside the platform timeout.
 * - [send] and [sendWatchCommand] start this service only when the foreground start is allowed: a
 *   `startForegroundService` whose `startForeground` fails would crash the app.
 * - Pause keeps the service (mic closed); Stop ends it once no command is in flight.
 * - START_STICKY is used only so a killed process comes back to *report* the interruption;
 *   a sticky restart never enters the foreground and never reopens the microphone.
 * - All service state is touched on the main thread only.
 */
class RecordingService : Service(), EffectHandler {
    private val container by lazy { (application as AiDailyMemoryApplication).container }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            // Surfaced to the operator log; state is unchanged because the journal write
            // failed or the effect never ran.
            logFailure(TAG, "service_command_failed", e)
        },
    )
    private var isForeground = false
    private var foregroundProblem: CaptureProblem? = null
    private var pendingCommands = 0
    private var lastStartId = 0
    private var attached = false
    private var recoveryJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        container.serviceRunning = true
        scope.launch {
            container.statusModels
                .filterNotNull()
                .distinctUntilChanged()
                // A state change and its health update arrive back to back; notify once.
                .debounce(NOTIFY_DEBOUNCE_MS)
                .collect { model -> if (isForeground) container.notifications.update(model) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) {
            // Sticky restart after the process was killed: the controller's restore already
            // reported the interruption. Never touch the microphone from the background.
            stopIfIdle()
            return START_STICKY
        }
        // Attach only for real commands, so restore effects after a sticky restart stay
        // detached (notification only).
        if (!attached) {
            attached = true
            container.controller.attach(this)
            recoveryJob = container.recoveryScheduler.start(scope)
            scope.launch { watchIndicator() }
            scope.launch { DebugTelemetry(applicationContext).sampleWhileRunning() }
            scope.launch { followRhythm() }
        }
        enterForeground()
        pendingCommands++
        val action = intent.action
        val watchCommand = intent.watchCommand()
        val watchNodeId = intent.getStringExtra(EXTRA_WATCH_NODE_ID)
        scope.launch {
            try {
                if (watchCommand != null && watchNodeId != null) {
                    handleWatchCommand(watchNodeId, watchCommand)
                } else {
                    handle(action)
                }
            } finally {
                pendingCommands--
                stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        container.serviceRunning = false
        container.controller.detach(this)
        recoveryJob?.cancel()
        val state = container.controller.state.value
        // Seal whatever is being written on the app scope, which outlives this service.
        container.appScope.launch {
            // Destroyed while the microphone was meant to be open (the system took the service
            // down but kept the process): without this the screen would keep saying Recording.
            val sessionId = state.sessionId
            if (state.status in MIC_OPEN && sessionId != null) {
                container.controller.report(EngineReport.Failed(sessionId, CaptureProblem.UNKNOWN))
            }
            container.engine.close()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.watchCommand(): WatchCommandEnvelope? {
        val commandId = getStringExtra(EXTRA_WATCH_COMMAND_ID) ?: return null
        val serviceAction = getStringExtra(EXTRA_WATCH_COMMAND) ?: return null
        val command = WatchCommand.entries.firstOrNull { it.serviceAction == serviceAction } ?: return null
        return WatchCommandEnvelope(commandId, command)
    }

    private suspend fun handle(action: String?) {
        val controller = container.controller
        when (action) {
            ACTION_START -> startIfIdle(container)
            ACTION_PAUSE -> controller.dispatch(RecordingCommand.Pause)
            ACTION_RESUME -> controller.dispatch(RecordingCommand.Resume)
            ACTION_STOP -> controller.dispatch(RecordingCommand.Stop)
            // The indicator was swiped away; enterForeground() already re-posted it.
            ACTION_SHOW_STATUS -> Unit
            else -> Log.w(TAG, "unknown_service_action")
        }
    }

    private suspend fun handleWatchCommand(nodeId: String, envelope: WatchCommandEnvelope) {
        val acknowledgement = container.watchCommandBridge.execute(envelope.commandId, envelope.command)
        WatchDataLayerTransport.sendAcknowledgement(
            this,
            nodeId,
            acknowledgement,
            container.currentWatchSnapshot(),
        )
    }

    override suspend fun execute(
        effect: CaptureEffect,
        state: RecordingState,
    ): List<RecordingEvent> = withContext(Dispatchers.Main.immediate) {
        when (effect) {
            CaptureEffect.EnsureForeground -> ensureForeground(state)
            is CaptureEffect.OpenMicrophone -> when {
                !container.permissions.microphoneGranted ->
                    failure(state, CaptureProblem.PERMISSION_DENIED)
                // Enforced here, not only in the UI: the mic never opens without a visible
                // indicator, whichever surface asked (AGENTS.md rule 1).
                !container.notifications.canShowStatus() ->
                    failure(state, CaptureProblem.INDICATOR_HIDDEN)
                !isForeground -> failure(state, foregroundProblem ?: CaptureProblem.UNKNOWN)
                else -> {
                    container.engine.open(effect.sessionId)
                    emptyList()
                }
            }
            is CaptureEffect.CloseMicrophone -> {
                container.engine.close()
                emptyList()
            }
            is CaptureEffect.FinishSession -> {
                container.engine.close()
                listOf(CaptureSignal.Stopped(effect.sessionId))
            }
            CaptureEffect.StopForeground -> {
                leaveForeground()
                emptyList()
            }
        }
    }

    private fun ensureForeground(state: RecordingState): List<RecordingEvent> {
        if (!isForeground) {
            if (pendingCommands == 0) {
                // No user command in flight (an engine report or a restored state): entering
                // the foreground now would be a background start. Tell the user instead.
                container.notifications.alert(state)
                return emptyList()
            }
            enterForeground()
        }
        if (state.status != RecordingStatus.INTERRUPTED) container.notifications.cancelAlert()
        val problem = foregroundProblem.takeUnless { isForeground } ?: return emptyList()
        return failure(state, problem)
    }

    /**
     * Notifications can be turned off while recording. Re-checked on every health update; if
     * the indicator can no longer be seen, capture stops as an interruption the user can fix.
     */
    private suspend fun watchIndicator() {
        container.statusModels.collect {
            val state = container.controller.state.value
            val sessionId = state.sessionId ?: return@collect
            if (state.status in MIC_OPEN && !container.notifications.canShowStatus()) {
                Log.w(TAG, "indicator_hidden_while_capturing")
                container.controller.report(
                    EngineReport.Failed(sessionId, CaptureProblem.INDICATOR_HIDDEN),
                )
            }
        }
    }

    /**
     * While the service runs, check the schedule on a short timer. Audio capture keeps the CPU
     * awake, so a quiet time or bedtime pause lands on time here even though the app-wide
     * alarm is inexact. Checks are cheap and idempotent (RhythmRunner.catchUp).
     */
    private suspend fun followRhythm() {
        val rhythm = container.rhythm
        while (true) {
            try {
                rhythm.catchUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "rhythm_check_failed", e)
            }
            val untilNext = rhythm.msUntilNextAction() ?: RHYTHM_IDLE_CHECK_MS
            delay(untilNext.coerceIn(RHYTHM_MIN_CHECK_MS, RHYTHM_IDLE_CHECK_MS))
        }
    }

    private fun failure(state: RecordingState, problem: CaptureProblem): List<RecordingEvent> {
        val sessionId = state.sessionId ?: return emptyList()
        return listOf(EngineSignals.signalFor(state, EngineReport.Failed(sessionId, problem)))
    }

    private fun enterForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                CaptureNotifications.ID_STATUS,
                container.notifications.status(container.currentStatusModel()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            isForeground = true
            foregroundProblem = null
        } catch (e: SecurityException) {
            // Microphone permission missing (API 34+ checks it for this service type).
            logFailure(TAG, "foreground_denied_permission", e, warning = true)
            foregroundProblem = CaptureProblem.PERMISSION_DENIED
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                logFailure(TAG, "foreground_start_not_allowed", e, warning = true)
                foregroundProblem = CaptureProblem.FOREGROUND_START_NOT_ALLOWED
            } else {
                throw e
            }
        }
    }

    private fun stopIfIdle() {
        if (pendingCommands > 0) return
        val status = container.controller.state.value.status
        val idle = status == RecordingStatus.IDLE || status == RecordingStatus.COMPLETED
        if (idle || !isForeground) leaveForeground()
    }

    private fun leaveForeground() {
        if (isForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        // Never while a command is still running; stopSelf(id) also refuses if a newer
        // command was delivered meanwhile (a quick Stop → Start).
        if (pendingCommands == 0) stopSelf(lastStartId)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFY_DEBOUNCE_MS = 200L

        /** Re-check at least this often, so clock and schedule edits are picked up. */
        private const val RHYTHM_IDLE_CHECK_MS = 60_000L
        private const val RHYTHM_MIN_CHECK_MS = 250L
        const val ACTION_START = "com.apercallc.aidailymemory.action.START"
        const val ACTION_PAUSE = "com.apercallc.aidailymemory.action.PAUSE"
        const val ACTION_RESUME = "com.apercallc.aidailymemory.action.RESUME"
        const val ACTION_STOP = "com.apercallc.aidailymemory.action.STOP"
        const val ACTION_HIGHLIGHT = "com.apercallc.aidailymemory.action.HIGHLIGHT"
        const val ACTION_QUICK_NOTE = "com.apercallc.aidailymemory.action.QUICK_NOTE"
        const val ACTION_SHOW_STATUS = "com.apercallc.aidailymemory.action.SHOW_STATUS"
        const val EXTRA_WATCH_COMMAND_ID = "com.apercallc.aidailymemory.extra.WATCH_COMMAND_ID"
        const val EXTRA_WATCH_COMMAND = "com.apercallc.aidailymemory.extra.WATCH_COMMAND"
        const val EXTRA_WATCH_NODE_ID = "com.apercallc.aidailymemory.extra.WATCH_NODE_ID"

        private val NO_SERVICE_NEEDED = setOf(ACTION_PAUSE, ACTION_STOP)

        private val MIC_OPEN = setOf(
            RecordingStatus.RECORDING,
            RecordingStatus.RESUMING,
            RecordingStatus.RECOVERING,
        )

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        /**
         * The single entry point for commands from the app and its notification.
         *
         * The service is only started when it can enter the foreground. With the microphone
         * permission missing, or a background start refused, the command goes straight to
         * the controller; its effects then run detached, report the problem, and never touch
         * the microphone. Markers need no service at all.
         */
        fun send(context: Context, action: String) {
            val container = (context.applicationContext as AiDailyMemoryApplication).container
            when (action) {
                ACTION_HIGHLIGHT -> return mark(container, MarkerKind.HIGHLIGHT)
                ACTION_QUICK_NOTE -> return mark(container, MarkerKind.QUICK_NOTE)
            }
            if (!container.permissions.microphoneGranted) return dispatchDirect(container, action)
            // Pausing or stopping never needs a service that isn't running: its effects
            // (close the mic, finish the session) are no-ops without one. Starting the service
            // just for them would be a foreground start from the background, which can crash
            // ("did not then call startForeground") when the platform refuses it.
            if (action in NO_SERVICE_NEEDED && !container.serviceRunning) {
                return dispatchDirect(container, action)
            }
            try {
                ContextCompat.startForegroundService(context, intent(context, action))
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    logFailure(TAG, "service_start_not_allowed", e, warning = true)
                    dispatchDirect(container, action)
                } else {
                    throw e
                }
            }
        }

        fun sendWatchCommand(context: Context, nodeId: String, envelope: WatchCommandEnvelope) {
            val container = (context.applicationContext as AiDailyMemoryApplication).container
            val action = when (envelope.command) {
                WatchCommand.Start -> ACTION_START
                WatchCommand.Pause -> ACTION_PAUSE
                WatchCommand.Resume -> ACTION_RESUME
                WatchCommand.Stop -> ACTION_STOP
                WatchCommand.Highlight -> ACTION_HIGHLIGHT
                WatchCommand.QuickNote -> ACTION_QUICK_NOTE
            }
            if (envelope.command.isMarker || !container.permissions.microphoneGranted ||
                (action in NO_SERVICE_NEEDED && !container.serviceRunning)
            ) {
                container.appScope.launch {
                    executeWatchCommandDirect(container, context, nodeId, envelope)
                }
                return
            }
            val watchIntent = intent(context, action)
                .putExtra(EXTRA_WATCH_COMMAND_ID, envelope.commandId)
                .putExtra(EXTRA_WATCH_COMMAND, envelope.command.serviceAction)
                .putExtra(EXTRA_WATCH_NODE_ID, nodeId)
            try {
                ContextCompat.startForegroundService(context, watchIntent)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    logFailure(TAG, "watch_foreground_start_not_allowed", e, warning = true)
                    container.appScope.launch {
                        executeWatchCommandDirect(container, context, nodeId, envelope)
                    }
                } else {
                    throw e
                }
            }
        }

        private suspend fun executeWatchCommandDirect(
            container: AppContainer,
            context: Context,
            nodeId: String,
            envelope: WatchCommandEnvelope,
        ) {
            val acknowledgement = container.watchCommandBridge.execute(envelope.commandId, envelope.command)
            WatchDataLayerTransport.sendAcknowledgement(
                context,
                nodeId,
                acknowledgement,
                container.currentWatchSnapshot(),
            )
        }

        /**
         * A second Start tap while a session is starting or running is a duplicate, not a new
         * session: every Start gets a fresh id, so dispatching it would be rejected and tell
         * the user their tap "isn't available".
         */
        private suspend fun startIfIdle(container: AppContainer) {
            val status = container.controller.state.value.status
            if (status != RecordingStatus.IDLE && status != RecordingStatus.COMPLETED) {
                Log.i(TAG, "start_ignored_session_active")
                return
            }
            val sessionId = SessionId(container.ids.next().toString())
            container.controller.dispatch(RecordingCommand.Start(sessionId))
        }

        private fun mark(container: AppContainer, kind: MarkerKind) {
            container.appScope.launch { container.placeMarker(kind) }
        }

        private fun dispatchDirect(container: AppContainer, action: String) {
            if (action == ACTION_START) {
                container.appScope.launch { startIfIdle(container) }
                return
            }
            val command = when (action) {
                ACTION_PAUSE -> RecordingCommand.Pause
                ACTION_RESUME -> RecordingCommand.Resume
                ACTION_STOP -> RecordingCommand.Stop
                else -> return
            }
            container.appScope.launch { container.controller.dispatch(command) }
        }
    }
}
