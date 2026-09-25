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
import com.apercallc.aidailymemory.capture.TaggedReport
import com.apercallc.aidailymemory.platform.PartialWakeLock
import com.apercallc.aidailymemory.schedule.AndroidRhythmAlarms
import com.apercallc.aidailymemory.schedule.RhythmRunner
import com.apercallc.aidailymemory.schedule.SessionRegistry
import com.apercallc.aidailymemory.settings.SettingsStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.platform.StorageMonitor
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.effects.EngineSignals
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.CaptureHealthEvaluator
import com.apercallc.aidailymemory.recording.health.HealthPolicy
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
            logFailure(TAG, "app_scope_failure", e)
        },
    )

    val database: CaptureDatabase by lazy { CaptureDatabase.open(app) }
    val clock = DeviceClock(app.contentResolver)
    val ids = UuidV7Generator()
    val permissions = Permissions(app)
    val settings = SettingsStore(
        app.getSharedPreferences(SettingsStore.FILE_NAME, Context.MODE_PRIVATE),
    )
    val notifications = CaptureNotifications(app)
    private val audioManager = app.getSystemService(AudioManager::class.java)

    /** Not backed up and not user-visible (ADR-0007); see backup/data-extraction rules. */
    private val segmentDirectory = File(app.noBackupFilesDir, "segments")

    // AAC-LC 32 kbit/s + ADTS + AEAD overhead is about 15 MiB per hour.
    private val storagePolicy = StoragePolicy.Provisional.copy(
        estimatedBytesPerHour = 15L * 1024 * 1024,
    )
    val storage = StorageMonitor(segmentDirectory, storagePolicy)

    /** Phone storage one recorded hour takes, for the audio-retention estimates. */
    val storageBytesPerHour: Long get() = storagePolicy.estimatedBytesPerHour

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
                // A session that stays open without a service is shown, never silent.
                if (state.status == RecordingStatus.PAUSED ||
                    state.status == RecordingStatus.INTERRUPTED
                ) {
                    notifications.alert(state)
                }
                emptyList()
            }
            is CaptureEffect.OpenMicrophone -> {
                val problem = if (permissions.microphoneGranted) {
                    CaptureProblem.FOREGROUND_START_NOT_ALLOWED
                } else {
                    CaptureProblem.PERMISSION_DENIED
                }
                val failed = EngineReport.Failed(effect.sessionId, problem)
                listOf(EngineSignals.signalFor(state, failed))
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
            // One consumer, so reports reach the controller in the order they happened.
            onReport = { report -> engineReports.trySend(report) },
        )
    }

    private val engineReports = Channel<TaggedReport>(Channel.UNLIMITED).also { channel ->
        appScope.launch {
            for (tagged in channel) {
                // Queued before a quick Pause → Resume: it describes a microphone open that no
                // longer exists and must not confirm or fail the current one.
                if (tagged.generation != engine.generation) {
                    Log.i(TAG, "stale_engine_report_dropped")
                    continue
                }
                // One failed report (a journal write) must not end reporting for the life of
                // the process: later reports would never reach the controller, leaving the
                // screen claiming a state the microphone is not in.
                try {
                    controller.report(tagged.report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(TAG, "engine_report_failed", e)
                }
            }
        }
    }

    /** True while [com.apercallc.aidailymemory.service.RecordingService] exists. Main thread. */
    @Volatile var serviceRunning = false

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
            keepAwake = PartialWakeLock(app, "AiDailyMemory:recovery"),
        )
    }

    private val markers: MarkerRecorder by lazy {
        MarkerRecorder(controller, database.markers(), MarkerPolicy.Default, clock, ids)
    }

    /** Applies the sleep schedule and quiet times to recording (ADR-0012). */
    val rhythm: RhythmRunner by lazy {
        RhythmRunner(
            settings = settings,
            controller = controller,
            alarms = AndroidRhythmAlarms(app),
            onWakeReminder = notifications::morningReminder,
        )
    }

    val sessions: SessionRegistry by lazy {
        SessionRegistry(database.sessions(), rhythm = { settings.rhythm.value })
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
        appScope.launch { sessions.observe(controller.state) }
        // Once a day has started, "good morning, start your day" is stale.
        appScope.launch {
            controller.state.collect { state ->
                val started = state.status != RecordingStatus.IDLE &&
                    state.status != RecordingStatus.COMPLETED
                if (started) notifications.cancelMorningReminder()
            }
        }
        // Catch up on the schedule (the process may have been dead across a change) and
        // re-arm its alarm, which a reboot or app update clears.
        appScope.launch { rhythm.catchUp() }
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
