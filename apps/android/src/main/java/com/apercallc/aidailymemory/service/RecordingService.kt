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
import com.apercallc.aidailymemory.capture.EffectHandler
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.effects.EngineSignals
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Microphone foreground service (ADR-0010). It owns no state: commands arrive as intents
 * (from the app, the notification, and later the watch), go through the controller, and the
 * service only executes the effects the controller hands back.
 *
 * Lifecycle rules:
 * - `startForeground` is called first in every command, well inside the platform timeout.
 * - Pause keeps the service (mic closed); Stop ends it.
 * - START_STICKY is used only so a killed process comes back to *report* the interruption;
 *   a sticky restart never reopens the microphone.
 */
class RecordingService : Service(), EffectHandler {
    private val container by lazy { (application as AiDailyMemoryApplication).container }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            // Surfaced to the operator log; state is unchanged because the journal write
            // failed or the effect never ran.
            Log.e(TAG, "service_command_failed", e)
        },
    )
    private var isForeground = false
    private var foregroundProblem: CaptureProblem? = null
    private var recoveryJob: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container.controller.attach(this)
        recoveryJob = container.recoveryScheduler.start(scope)
        scope.launch {
            container.statusModels.filterNotNull().distinctUntilChanged().collect { model ->
                if (isForeground) container.notifications.update(model)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Sticky restart after the process was killed: the controller's restore already
            // reported the interruption. Do not touch the microphone from the background.
            stopSelf(startId)
            return START_STICKY
        }
        lastStartId = startId
        enterForeground()
        val action = intent.action
        scope.launch {
            handle(action)
            stopIfIdle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        container.controller.detach(this)
        recoveryJob?.cancel()
        // The service is going away: seal whatever is being written on the app scope, which
        // outlives this service.
        container.appScope.launch { container.engine.close() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handle(action: String?) {
        val controller = container.controller
        when (action) {
            ACTION_START -> {
                val sessionId = SessionId(container.ids.next().toString())
                controller.dispatch(RecordingCommand.Start(sessionId))
            }
            ACTION_PAUSE -> controller.dispatch(RecordingCommand.Pause)
            ACTION_RESUME -> controller.dispatch(RecordingCommand.Resume)
            ACTION_STOP -> controller.dispatch(RecordingCommand.Stop)
            ACTION_HIGHLIGHT -> container.placeMarker(MarkerKind.HIGHLIGHT)
            ACTION_QUICK_NOTE -> container.placeMarker(MarkerKind.QUICK_NOTE)
            else -> Log.w(TAG, "unknown_service_action")
        }
    }

    override suspend fun execute(
        effect: CaptureEffect,
        state: RecordingState,
    ): List<RecordingEvent> = when (effect) {
        CaptureEffect.EnsureForeground -> {
            if (!isForeground) enterForeground()
            if (state.status != RecordingStatus.INTERRUPTED) container.notifications.cancelAlert()
            foregroundProblem
                ?.takeUnless { isForeground }
                ?.let { problem -> failure(state, problem) }
                .orEmpty()
        }
        is CaptureEffect.OpenMicrophone ->
            if (container.permissions.microphoneGranted) {
                container.engine.open(effect.sessionId)
                emptyList()
            } else {
                failure(state, CaptureProblem.PERMISSION_DENIED)
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

    private fun failure(state: RecordingState, problem: CaptureProblem): List<RecordingEvent> {
        val sessionId = state.sessionId ?: return emptyList()
        return listOf(EngineSignals.signalFor(state, EngineReport.Failed(sessionId, problem)))
    }

    private fun enterForeground() {
        val model = container.currentStatusModel()
        try {
            ServiceCompat.startForeground(
                this,
                CaptureNotifications.ID_STATUS,
                container.notifications.status(model),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            isForeground = true
            foregroundProblem = null
        } catch (e: SecurityException) {
            // Microphone permission missing (API 34+ checks it for this service type).
            Log.w(TAG, "foreground_denied_permission", e)
            foregroundProblem = CaptureProblem.PERMISSION_DENIED
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "foreground_start_not_allowed", e)
                foregroundProblem = CaptureProblem.FOREGROUND_START_NOT_ALLOWED
            } else {
                throw e
            }
        }
    }

    private fun stopIfIdle() {
        val status = container.controller.state.value.status
        val idle = status == RecordingStatus.IDLE || status == RecordingStatus.COMPLETED
        if (idle || !isForeground) leaveForeground()
    }

    private fun leaveForeground() {
        if (isForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        // Only stops if no newer command arrived meanwhile (a quick Stop → Start).
        stopSelf(lastStartId)
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.apercallc.aidailymemory.action.START"
        const val ACTION_PAUSE = "com.apercallc.aidailymemory.action.PAUSE"
        const val ACTION_RESUME = "com.apercallc.aidailymemory.action.RESUME"
        const val ACTION_STOP = "com.apercallc.aidailymemory.action.STOP"
        const val ACTION_HIGHLIGHT = "com.apercallc.aidailymemory.action.HIGHLIGHT"
        const val ACTION_QUICK_NOTE = "com.apercallc.aidailymemory.action.QUICK_NOTE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        /** Sends a command from a visible activity (foreground start is allowed there). */
        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, intent(context, action))
        }
    }
}
