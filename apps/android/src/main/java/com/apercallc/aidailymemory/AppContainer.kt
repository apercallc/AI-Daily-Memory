package com.apercallc.aidailymemory

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.apercallc.aidailymemory.capture.AudioCaptureEngine
import com.apercallc.aidailymemory.capture.CaptureHealthTracker
import com.apercallc.aidailymemory.capture.CaptureOverview
import com.apercallc.aidailymemory.capture.EffectHandler
import com.apercallc.aidailymemory.capture.MarkerRecorder
import com.apercallc.aidailymemory.capture.RecordingController
import com.apercallc.aidailymemory.capture.RecoveryScheduler
import com.apercallc.aidailymemory.capture.RoomTransitionJournal
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.platform.StorageMonitor
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.effects.EngineSignals
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.CaptureHealthEvaluator
import com.apercallc.aidailymemory.recording.health.HealthPolicy
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.marker.MarkerPolicy
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.recovery.InterruptionRecoveryPolicy
import com.apercallc.aidailymemory.recording.recovery.RecoveryCondition
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.storage.StoragePolicy
import com.apercallc.aidailymemory.service.CaptureNotifications
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring, one instance per process. Deliberately plain: a DI framework is
 * not justified by this graph yet (AGENTS.md "no speculative abstractions").
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "app_scope_failure", e)
        },
    )

    val database: CaptureDatabase by lazy { CaptureDatabase.open(app) }
    val clock = DeviceClock(app.contentResolver)
    val ids = UuidV7Generator()
    val permissions = Permissions(app)
    val notifications = CaptureNotifications(app)
    private val audioManager = app.getSystemService(AudioManager::class.java)

    /** Not backed up and not user-visible (ADR-0007); see backup/data-extraction rules. */
    private val segmentDirectory = File(app.noBackupFilesDir, "segments")

    // AAC-LC 32 kbit/s + ADTS + AEAD overhead is about 15 MiB per hour.
    private val storagePolicy = StoragePolicy.Provisional.copy(
        estimatedBytesPerHour = 15L * 1024 * 1024,
    )
    val storage = StorageMonitor(segmentDirectory, storagePolicy)

    private val cipher by lazy { SegmentCipher.open(app) }

    val segments: SegmentStore by lazy {
        SegmentStore(
            directory = segmentDirectory,
            dao = database.segments(),
            // Retried on every segment: a transient Keystore failure must not stick.
            cipher = { cipher },
            lifecycle = SegmentLifecycle(recoveryWindowMs = RECOVERY_WINDOW_MS),
            ids = ids,
            clock = clock,
        )
    }

    /**
     * Effects while no service runs: never touches the microphone. A session that needs the
     * user gets a "tap to resume" notification instead of a silent gap (ADR-0010).
     */
    private val detachedEffects = EffectHandler { effect, state ->
        when (effect) {
            CaptureEffect.EnsureForeground -> {
                notifications.alert(state)
                emptyList()
            }
            is CaptureEffect.OpenMicrophone -> {
                val sessionId = effect.sessionId
                listOf(
                    EngineSignals.signalFor(
                        state,
                        EngineReport.Failed(sessionId, CaptureProblem.FOREGROUND_START_NOT_ALLOWED),
                    ),
                )
            }
            is CaptureEffect.CloseMicrophone -> emptyList()
            is CaptureEffect.FinishSession -> listOf(CaptureSignal.Stopped(effect.sessionId))
            CaptureEffect.StopForeground -> {
                notifications.cancelAlert()
                emptyList()
            }
        }
    }

    val controller: RecordingController by lazy {
        RecordingController(
            journal = RoomTransitionJournal(database.transitions()),
            now = clock::now,
            currentBoot = clock::bootStamp,
            bootOf = DeviceClock::bootStampOf,
            elapsedRealtimeMs = clock::elapsedRealtimeMs,
            scope = appScope,
            detached = detachedEffects,
            beforeRecovery = {
                val abandoned = segments.abandonUnfinished()
                if (abandoned > 0) Log.w(TAG, "abandoned_unfinished_segments")
            },
        )
    }

    val engine: AudioCaptureEngine by lazy {
        AudioCaptureEngine(
            audioManager = audioManager,
            store = segments,
            storage = { storage.evaluate(database.segments().pendingBytes()) },
            elapsedRealtimeMs = clock::elapsedRealtimeMs,
            scope = appScope,
            onReport = { report -> appScope.launch { controller.report(report) } },
        )
    }

    val health: CaptureHealthTracker by lazy {
        CaptureHealthTracker(
            controller = controller,
            engineHealth = engine.health,
            segments = database.segments(),
            storage = storage,
            evaluator = CaptureHealthEvaluator(HealthPolicy.Provisional),
            elapsedRealtimeMs = clock::elapsedRealtimeMs,
            scope = appScope,
        )
    }

    val recoveryScheduler: RecoveryScheduler by lazy {
        RecoveryScheduler(
            controller = controller,
            policy = InterruptionRecoveryPolicy.Default,
            conditionHolds = { condition ->
                when (condition) {
                    RecoveryCondition.CALL_ENDED -> audioManager.mode == AudioManager.MODE_NORMAL
                    RecoveryCondition.STORAGE_AVAILABLE ->
                        !storage.evaluate(0).mustStopCapture
                }
            },
            onNeedsUser = notifications::alert,
        )
    }

    private val markers: MarkerRecorder by lazy {
        MarkerRecorder(controller, database.markers(), MarkerPolicy.Default, clock, ids)
    }

    /** One presenter output for the app screen and the notification. */
    val statusModels: Flow<CaptureStatusModel?> by lazy {
        combine(controller.state, health.overview) { state, overview ->
            CaptureStatusPresenter.present(state, overview?.health ?: CaptureHealth.Unknown)
        }
    }

    val overview: Flow<CaptureOverview?> get() = health.overview

    fun currentStatusModel(): CaptureStatusModel = CaptureStatusPresenter.present(
        controller.state.value,
        health.overview.value?.health ?: CaptureHealth.Unknown,
    )

    suspend fun placeMarker(kind: MarkerKind): MarkerDecision =
        markers.place(kind, capturing = currentStatusModel().canMark)

    /** Restores the journal and makes any open session visible without a running service. */
    fun restoreOnProcessStart() {
        controller.restore()
        appScope.launch {
            controller.awaitRestored()
            val state: RecordingState = controller.state.value
            val open = state.status == RecordingStatus.PAUSED ||
                state.status == RecordingStatus.INTERRUPTED
            if (open) notifications.alert(state)
        }
    }

    private companion object {
        const val TAG = "AppContainer"

        /** Acknowledged audio stays on the phone this long before retention may delete it. */
        const val RECOVERY_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
