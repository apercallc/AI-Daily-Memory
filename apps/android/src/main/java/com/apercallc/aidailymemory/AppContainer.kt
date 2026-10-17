package com.apercallc.aidailymemory

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.AudioManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.apercallc.aidailymemory.analysis.MediaCodecAacDecoder
import com.apercallc.aidailymemory.analysis.SpeechAnalysisScheduler
import com.apercallc.aidailymemory.analysis.SpeechAnalyzer
import com.apercallc.aidailymemory.net.AccountApi
import com.apercallc.aidailymemory.net.AccountLinker
import com.apercallc.aidailymemory.net.HttpAccountApi
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.LinkStore
import com.apercallc.aidailymemory.upload.UploadScheduler
import com.apercallc.aidailymemory.ondevice.OnDeviceSpeechModel
import com.apercallc.aidailymemory.ondevice.OnDeviceTranscriptionProvider
import com.apercallc.aidailymemory.ondevice.VoskRecognizers
import com.apercallc.aidailymemory.processing.StubTranscriptionProvider
import com.apercallc.aidailymemory.processing.ProcessingPipeline
import com.apercallc.aidailymemory.processing.ProcessingScheduler
import com.apercallc.aidailymemory.processing.StubExtractionProvider
import com.apercallc.aidailymemory.processing.ApiKeyTranscriptionProvider
import com.apercallc.aidailymemory.processing.ApiKeyExtractionProvider
import com.apercallc.aidailymemory.capture.AudioCaptureEngine
import com.apercallc.aidailymemory.capture.CaptureHealthTracker
import com.apercallc.aidailymemory.capture.CaptureOverview
import com.apercallc.aidailymemory.capture.EffectHandler
import com.apercallc.aidailymemory.capture.MarkerRecorder
import com.apercallc.aidailymemory.capture.RecordingController
import com.apercallc.aidailymemory.capture.RecoveryScheduler
import com.apercallc.aidailymemory.capture.RetainedAudioExporter
import com.apercallc.aidailymemory.capture.RoomTransitionJournal
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.capture.SessionDataDeletion
import com.apercallc.aidailymemory.capture.TaggedReport
import com.apercallc.aidailymemory.platform.PartialWakeLock
import com.apercallc.aidailymemory.schedule.AndroidRhythmAlarms
import com.apercallc.aidailymemory.schedule.RhythmRunner
import com.apercallc.aidailymemory.schedule.SessionRegistry
import com.apercallc.aidailymemory.recording.processing.EndpointUrl
import com.apercallc.aidailymemory.settings.ProcessingSettings
import com.apercallc.aidailymemory.settings.SettingsStore
import com.apercallc.aidailymemory.update.UpdateChecker
import com.apercallc.aidailymemory.update.UpdateClient
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.TranscriptCipher
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.platform.Permissions
import com.apercallc.aidailymemory.platform.StorageMonitor
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.processing.SyncManager
import com.apercallc.aidailymemory.processing.SyncScheduler
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
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
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.recovery.InterruptionRecoveryPolicy
import com.apercallc.aidailymemory.recording.recovery.RecoveryCondition
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.storage.StoragePolicy
import com.apercallc.aidailymemory.service.CaptureNotifications
import com.apercallc.aidailymemory.service.SessionOrigins
import com.apercallc.aidailymemory.service.WatchCommandBridge
import com.apercallc.aidailymemory.service.restoreAcknowledgements
import com.apercallc.aidailymemory.service.WatchDataLayerTransport
import com.apercallc.aidailymemory.service.WatchConnectivityMonitor
import com.apercallc.aidailymemory.service.WatchRevisionWatermark
import com.apercallc.aidailymemory.service.WatchSnapshotFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    /** Stable per-app-signing-key device ID for sync payloads (Settings.Secure.ANDROID_ID). */
    @Suppress("HardwareIds")
    val deviceId: String = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown"
    val permissions = Permissions(app)
    val settings = SettingsStore(
        app.getSharedPreferences(SettingsStore.FILE_NAME, Context.MODE_PRIVATE),
    )

    init {
        // A phone updated from a version that had no walkthrough already knows the app.
        // Decided once, on the first start of this install: a restart in the middle of the guide
        // must not read as "updated" and skip the rest of it.
        if (!settings.upgradeChecked) {
            if (!settings.onboardingRecorded && isUpdatedInstall()) settings.setOnboardingDone(true)
            settings.upgradeChecked = true
        }
    }

    @Suppress("DEPRECATION")
    private fun isUpdatedInstall(): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }
        info.firstInstallTime != info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        logFailure(TAG, "install_time_unavailable", e)
        false
    }

    /** The free speech model for on-device transcription (ADR-0026); downloaded on request. */
    val speechModel: OnDeviceSpeechModel by lazy {
        val dir = File(app.noBackupFilesDir, "models").apply { mkdirs() }
        val storage = app.getSystemService(StorageManager::class.java)
        OnDeviceSpeechModel(
            baseDir = dir,
            freeBytes = {
                try {
                    storage.getAllocatableBytes(storage.getUuidForPath(dir))
                } catch (e: IOException) {
                    logFailure(TAG, "free_space_unknown", e, warning = true)
                    0L
                }
            },
        )
    }

    val processingSettings = ProcessingSettings(
        app.getSharedPreferences(ProcessingSettings.FILE_NAME, Context.MODE_PRIVATE),
        aead = { processingAead },
        onDeviceModelReady = { speechModel.isReady },
    )
    private val processingAead by lazy {
        LinkStore.keystoreAead(
            app,
            "processing_keyset",
            "processing_keys",
            "android-keystore://aidm_processing_master_key",
        )
    }
    private val stopDecisionPreferences by lazy {
        app.getSharedPreferences(STOP_DECISION_PREFERENCES, Context.MODE_PRIVATE)
    }

    /**
     * Highest revision ever handed to a watch, persisted so a journal that went backwards
     * (deleting the newest session) is still detectable after the next process start.
     *
     * Loaded on first access (whoever that is): [currentWatchSnapshot] is called from the
     * service's main thread as well as from process-start coroutines, and a reader that ran
     * before a separate `load()` coroutine would see a null watermark and publish a non-reset
     * snapshot the watch discards.
     */
    private val watchRevisions: WatchRevisionWatermark by lazy {
        WatchRevisionWatermark(
            app.getSharedPreferences(WATCH_WATERMARK_PREFERENCES, Context.MODE_PRIVATE),
        ).apply { load() }
    }

    fun handledStopDecisionSessionId(): String? =
        stopDecisionPreferences.getString(STOP_DECISION_SESSION_KEY, null)

    fun markStopDecisionHandled(sessionId: String) {
        stopDecisionPreferences.edit { putString(STOP_DECISION_SESSION_KEY, sessionId) }
    }
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

    private val memoryRetentionSweep by lazy {
        com.apercallc.aidailymemory.processing.MemoryRetentionSweep(
            database = database,
            memoryItemDao = database.memoryItems(),
            memoryCorrectionDao = database.memoryCorrections(),
            dailyReportDao = database.dailyReports(),
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
            watchLink = WatchConnectivityMonitor.observe(app),
            segments = database.segments(),
            storage = storage,
            evaluator = CaptureHealthEvaluator(HealthPolicy.Provisional),
            elapsedRealtimeMs = clock::elapsedRealtimeMs,
            scope = appScope,
            // Local audio is the default (ADR-0026): hours kept on this phone are not a backlog
            // to warn about. A user-selected provider receives audio directly.
            expectUploads = { false },
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

    private val watchAckPreferences by lazy {
        app.getSharedPreferences(WATCH_ACK_PREFERENCES, Context.MODE_PRIVATE)
    }

    val watchCommandBridge: WatchCommandBridge by lazy {
        WatchCommandBridge(
            revision = { controller.state.value.revision },
            dispatch = { command -> controller.dispatch(command) },
            placeMarker = ::placeMarker,
            newSessionId = { SessionId(ids.next().toString()) },
            awaitReady = { controller.awaitRestored() },
            acknowledgements = restoreAcknowledgements(
                watchAckPreferences.getString(WATCH_ACKS_KEY, null),
            ),
            persist = { encoded -> watchAckPreferences.edit { putString(WATCH_ACKS_KEY, encoded) } },
            recordOrigin = sessionOrigins::record,
            forgetOrigin = sessionOrigins::forget,
            onApplied = { command -> watchActivity.tryEmit(command) },
        )
    }

    /** Which device each recent session was started from (the microphone is always the phone's). */
    val sessionOrigins: SessionOrigins by lazy {
        SessionOrigins(app.getSharedPreferences(SessionOrigins.FILE_NAME, Context.MODE_PRIVATE))
    }

    /** Commands the watch just got the phone to act on, for a "from your watch" message. */
    val watchActivity = MutableSharedFlow<WatchCommand>(extraBufferCapacity = 8)

    /** Applies the sleep schedule and quiet times to recording (ADR-0012). */
    val rhythm: RhythmRunner by lazy {
        RhythmRunner(
            settings = settings,
            controller = controller,
            alarms = AndroidRhythmAlarms(app),
            onWakeReminder = notifications::morningReminder,
            onResumedAfterSleep = notifications::resumedAfterSleep,
            onDayEnded = {
                // Bedtime (TODO.md "Generate reports at bedtime"): kick off on-device
                // processing right away rather than waiting for the next opportunistic check.
                if (processingSettings.processingEnabled.value) processingScheduler.schedule()
            },
            onWakeDeliverReport = { deliverLatestReport() },
        )
    }

    /**
     * Wake time after a day ended at bedtime (TODO.md "deliver them at wake time"): shows the
     * most recently generated report, whatever its status, so a phone that never charged
     * overnight still gets a truthful partial notice instead of silence.
     */
    private suspend fun deliverLatestReport() {
        val entity = database.dailyReports().observeRecent(1).first().firstOrNull() ?: return
        val report = try {
            com.apercallc.aidailymemory.processing.ReportBuilder.deserialize(entity.reportJson)
        } catch (e: kotlinx.serialization.SerializationException) {
            logFailure(TAG, "report_delivery_unparseable", e, warning = true)
            return
        }
        notifications.dailyReportReady(
            headline = report.headline,
            partial = report.status == com.apercallc.aidailymemory.recording.processing.DailyReportStatus.PARTIAL,
        )
    }

    val sessions: SessionRegistry by lazy {
        SessionRegistry(
            database.sessions(),
            rhythm = { settings.rhythm.value },
            // The report is only sent when it changes, so a rename marks its day as changed.
            onRenamed = { sessionId ->
                val day = database.sessions().memoryDayForSession(sessionId)
                if (day != null && database.dailyReports().touch(day, System.currentTimeMillis()) > 0 &&
                    processingSettings.syncEnabled.value
                ) {
                    syncScheduler.schedule()
                }
            },
        )
    }

    /** User-requested local deletion; refuses active sessions and audio already copied remotely. */
    val sessionDataDeletion: SessionDataDeletion by lazy {
        SessionDataDeletion(
            sessions = database.sessions(),
            deleteAudio = segments::deleteSessionAudio,
            // Phones that uploaded under an earlier version may still have audio server-side.
            hasServerSideData = database.clips()::hasUploadRowsForSession,
            isActive = { sessionId ->
                val state = controller.state.value
                state.sessionId?.value == sessionId &&
                    state.status != RecordingStatus.IDLE &&
                    state.status != RecordingStatus.COMPLETED
            },
            // The database transaction already queued the purge, even while disconnected.
            onDeleted = { syncScheduler.schedule() },
        )
    }

    /** User-requested on-device export of a session's still-retained audio (TODO.md Phase 8
     *  privacy package); app-private cache, cleared with the app, never uploaded. */
    val retainedAudioExporter: RetainedAudioExporter by lazy {
        RetainedAudioExporter(
            segmentDao = database.segments(),
            segmentStore = segments,
            exportDirectory = File(app.cacheDir, "audio_export"),
        )
    }

    /** Charging-time speech analysis (ADR-0015); shadow mode until local VAD quality gates pass. */
    val speechAnalyzer: SpeechAnalyzer by lazy {
        SpeechAnalyzer(segments, database.speech(), MediaCodecAacDecoder())
    }
    val speechScheduler = SpeechAnalysisScheduler(app)

    /** Account link (encrypted device token). Absent until the user connects. */
    val links: LinkStore by lazy {
        LinkStore(
            app.getSharedPreferences(LinkStore.FILE_NAME, Context.MODE_PRIVATE),
            aead = { linkAead },
        )
    }
    private val linkAead by lazy { LinkStore.keystoreAead(app) }

    private val backend by lazy { HttpAccountApi() }
    val accountApi: AccountApi get() = backend

    /** Only retries a disconnected token's revoke now; audio is never uploaded (ADR-0026). */
    val uploadScheduler = UploadScheduler(app)

    /** Optional notes sync and queued user-requested deletions (even with notes sync off). */
    val syncScheduler = SyncScheduler(app)
    val syncManager by lazy {
        SyncManager(
            database = database,
            settings = processingSettings,
            deviceId = deviceId,
            link = { links.link.value },
            notBeforeEpochMs = { links.uploadSinceEpochMs },
        )
    }

    private fun validUrl(raw: String?): String =
        (EndpointUrl.parse(raw.orEmpty()) as? EndpointUrl.Valid)?.value.orEmpty()

    /** Starts the speech-model download so it outlives the settings screen, then queues the wait. */
    fun downloadSpeechModel() {
        appScope.launch {
            speechModel.download()
            if (speechModel.isReady) {
                processingSettings.notifyConfigChanged()
                if (processingSettings.processingEnabled.value) processingScheduler.schedule()
            }
        }
    }

    /** Creates providers per job so edits in Settings apply without restarting the app. */
    val processingScheduler = ProcessingScheduler(app)
    val processingPipeline: ProcessingPipeline
        get() {
            // Incomplete settings never reach the providers: the pipeline waits on configGaps
            // and the settings screen says what is missing. The stubs are unreachable
            // placeholders that satisfy the types.
            val ready = processingSettings.configGaps().isEmpty()
            val tp = when {
                !ready -> StubTranscriptionProvider()
                processingSettings.transcriptionProvider.value == ProcessingSettings.PROVIDER_USER_API ->
                    ApiKeyTranscriptionProvider(
                        validUrl(processingSettings.transcriptionApiUrl),
                        processingSettings.transcriptionApiKey.orEmpty(),
                        com.apercallc.aidailymemory.recording.processing.ProviderPresets
                            .modelInUrl(processingSettings.transcriptionApiUrl) ?: "user-model",
                    )
                else -> OnDeviceTranscriptionProvider(
                    decoder = MediaCodecAacDecoder(),
                    recognizers = VoskRecognizers { speechModel.modelPath.takeIf { speechModel.isReady } },
                    model = speechModel.spec.id,
                    language = speechModel.spec.language,
                )
            }
            // Summaries need the person's own key; without one the transcript stands alone.
            val ep = if (ready && processingSettings.extractionProvider.value == ProcessingSettings.PROVIDER_USER_API) {
                ApiKeyExtractionProvider(
                    validUrl(processingSettings.extractionApiUrl),
                    processingSettings.extractionApiKey.orEmpty(),
                    processingSettings.extractionModel.orEmpty(),
                )
            } else {
                null
            }
            return ProcessingPipeline(
                segments = segments,
                segmentDao = database.segments(),
                speechDao = database.speech(),
                clipDao = database.clips(),
                memoryItemDao = database.memoryItems(),
                memoryCorrectionDao = database.memoryCorrections(),
                dailyReportDao = database.dailyReports(),
                localTranscriptDao = database.localTranscripts(),
                processingStateDao = database.processingState(),
                providerUsageDao = database.providerUsage(),
                sessionDao = database.sessions(),
                database = database,
                transcriptionProvider = tp,
                extractionProvider = ep,
                settings = processingSettings,
                configGaps = processingSettings::configGaps,
                ids = ids,
            )
        }

    /** Self-update (GitHub Releases): checked once per app open, installed on user tap. */
    val updates: UpdateChecker by lazy { UpdateChecker(UpdateClient()) }

    /** The version the updater compares against; null when the package manager won't say. */
    fun installedVersionName(): String? = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        logFailure("AppContainer", "installed_version_unreadable", e)
        null
    }

    /** Where a downloaded update APK lands (app-private cache; cleared with the app). */
    fun updateApkFile() = File(File(app.cacheDir, "updates"), UpdateChecker.ASSET_NAME)

    /**
     * Hands the downloaded APK to the system installer (user confirms the install). Returns
     * false, never throws, when no installer can handle it or the file cannot be shared.
     */
    fun installApk(apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        app.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    } catch (e: ActivityNotFoundException) {
        logFailure("AppContainer", "install_no_handler", e)
        false
    } catch (e: IllegalArgumentException) {
        logFailure("AppContainer", "install_uri_failed", e)
        false
    } catch (e: SecurityException) {
        logFailure("AppContainer", "install_denied", e)
        false
    }

    /** Connect / disconnect (blocking: call on IO). */
    val accountLinker: AccountLinker by lazy {
        AccountLinker(
            links = links,
            api = backend,
            onLinked = { syncScheduler.schedule() },
            onUnlinked = { syncScheduler.cancel() },
            onRevokePending = uploadScheduler::scheduleRevoke,
            signIn = backend,
        )
    }

    /** Transcript words are sealed at rest under their own Keystore-wrapped key. */
    val transcriptCipher: TranscriptCipher by lazy {
        val aead by lazy {
            LinkStore.keystoreAead(app, "transcript_keyset", "transcript_keys", "android-keystore://aidm_transcript_master_key")
        }
        TranscriptCipher { aead }
    }

    /** New speech analysis may have found speech to transcribe. */
    fun afterSpeechAnalysis() {
        if (processingSettings.processingEnabled.value) processingScheduler.schedule()
    }

    /** The server refused [link]: stop and ask the user to reconnect (if it is still current). */
    fun onLinkRejected(link: ServerLink) {
        if (links.clearIfCurrent(link)) {
            Log.w(TAG, "account_link_rejected")
        }
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

    fun currentWatchSnapshot(): PhoneRecordingSnapshot {
        val state = controller.state.value
        return WatchSnapshotFactory.from(
            state = state,
            captureHealth = health.overview.value?.health?.level?.name ?: "UNKNOWN",
            reset = WatchSnapshotFactory.needsReset(state.revision, watchRevisions.lastPublished),
            origin = sessionOrigins.originOf(state.sessionId),
        )
    }

    fun publishCurrentWatchSnapshot() {
        appScope.launch {
            controller.awaitRestored()
            val snapshot = currentWatchSnapshot()
            WatchDataLayerTransport.publishSnapshot(app, snapshot) {
                watchRevisions.published(snapshot.revision)
            }
        }
    }

    suspend fun placeMarker(kind: MarkerKind): MarkerDecision =
        markers.place(kind, capturing = currentStatusModel().canMark)

    /** Restores the journal and makes any open session visible without a running service. */
    fun restoreOnProcessStart() {
        controller.restore()
        uploadScheduler.cancelLegacyUpload()
        appScope.launch {
            // The watermark loads on first access (see `watchRevisions`): whichever path
            // touches it first — this collector, or `currentWatchSnapshot` from the service —
            // a journal that restored *below* what a watch has already seen (the newest
            // session was deleted) is flagged reset and the watch accepts it (ADR-0008).
            controller.awaitRestored()
            combine(controller.state, health.overview) { state, overview ->
                WatchSnapshotFactory.from(
                    state = state,
                    captureHealth = overview?.health?.level?.name ?: "UNKNOWN",
                    reset = WatchSnapshotFactory.needsReset(state.revision, watchRevisions.lastPublished),
                    origin = sessionOrigins.originOf(state.sessionId),
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    WatchDataLayerTransport.publishSnapshot(app, snapshot) {
                        watchRevisions.published(snapshot.revision)
                    }
                }
        }
        // Keystore work off the main thread; the settings screen shows it once loaded.
        appScope.launch { links.ensureLoaded() }
        appScope.launch { sessions.observe(controller.state) }
        // Retention (ADR-0013) and speech-analysis scheduling (ADR-0015) run at start and
        // whenever a session completes.
        appScope.launch {
            controller.awaitRestored()
            applyRetention()
            speechScheduler.schedule()
            if (processingSettings.processingEnabled.value) processingScheduler.schedule()
            syncScheduler.schedule()
            controller.state.collect {
                if (it.status == RecordingStatus.COMPLETED) {
                    applyRetention()
                    speechScheduler.schedule()
                    if (processingSettings.processingEnabled.value) processingScheduler.schedule()
                    if (processingSettings.syncEnabled.value) syncScheduler.schedule()
                }
            }
        }
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

    private suspend fun applyRetention() {
        try {
            val deleted = segments.applyRetention(System.currentTimeMillis(), settings.retention.value)
            if (deleted > 0) Log.i(TAG, "retention_deleted count=$deleted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept audio simply stays until the next pass; never silent.
            logFailure(TAG, "retention_failed", e)
        }
        try {
            val deletedItems = memoryRetentionSweep.apply(settings.memoryRetention.value)
            if (deletedItems > 0) Log.i(TAG, "memory_retention_deleted count=$deletedItems")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept memory simply stays until the next pass; never silent.
            logFailure(TAG, "memory_retention_failed", e)
        }
    }

    private companion object {
        const val TAG = "AppContainer"
        const val STOP_DECISION_PREFERENCES = "stop_decisions"
        const val STOP_DECISION_SESSION_KEY = "handled_session_id"
        const val WATCH_WATERMARK_PREFERENCES = "watch_revision_watermark"
        const val WATCH_ACK_PREFERENCES = "watch_command_acks"
        const val WATCH_ACKS_KEY = "recent"

        /** Acknowledged audio stays on the phone this long before retention may delete it. */
        const val RECOVERY_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
