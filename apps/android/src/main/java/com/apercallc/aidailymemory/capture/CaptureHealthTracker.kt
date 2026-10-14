package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.platform.StorageMonitor
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.CaptureHealthEvaluator
import com.apercallc.aidailymemory.recording.health.CaptureHealthInputs
import com.apercallc.aidailymemory.recording.health.WatchLink
import com.apercallc.aidailymemory.recording.segment.CaptureGap
import com.apercallc.aidailymemory.recording.segment.CaptureSummary
import com.apercallc.aidailymemory.recording.segment.GapDetector
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the capture-health surface shows, recomputed on change and on a slow tick. */
data class CaptureOverview(
    val health: CaptureHealth,
    val summary: CaptureSummary,
    val storage: StorageHealth,
    /** Unintended holes in this session's audio (lost, torn, or discontinuous segments). */
    val gaps: List<CaptureGap> = emptyList(),
    /** How long ago a segment was last sealed in this boot, or null if none yet. */
    val lastSavedAgoMs: Long? = null,
)

/** Sealed segment rows of one session, reduced to what health needs. */
private data class SessionSegments(val summary: CaptureSummary, val gaps: List<CaptureGap>)
private data class HealthSources(
    val state: RecordingState,
    val statusSinceElapsedMs: Long,
    val engine: EngineHealth,
    val session: SessionSegments,
    val storage: StorageHealth,
)

/**
 * The pending-upload duration the health evaluator should judge, given whether an account is
 * linked. The evaluator's backlog warning presumes uploads are expected; with no account,
 * audio staying on this phone is the design (private device-test milestone), so a long local
 * day must not paint a healthy recording as a warning.
 */
internal fun backlogPendingMs(summary: CaptureSummary, uploadsExpected: Boolean): Long =
    if (uploadsExpected) summary.pendingUploadMs else 0L

/**
 * Feeds [CaptureHealthEvaluator] from live sources. Runs only while someone is watching
 * (UI or notification), and ticks slowly: stall detection needs seconds, not frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureHealthTracker(
    controller: RecordingController,
    engineHealth: StateFlow<EngineHealth>,
    watchLink: Flow<WatchLink>,
    segments: SegmentDao,
    storage: StorageMonitor,
    evaluator: CaptureHealthEvaluator,
    elapsedRealtimeMs: () -> Long,
    scope: CoroutineScope,
    tickMs: Long = 5_000,
    /** Whether uploads are expected at all (an account is linked). */
    private val expectUploads: () -> Boolean = { true },
) {
    private val gapDetector = GapDetector()

    /**
     * Stall detection needs a clock only while RECORDING. Paused or idle, health changes only
     * with state or storage writes, so the tracker does not wake the CPU on a timer then.
     */
    private val ticks: Flow<Long> = controller.state
        .map { it.status == RecordingStatus.RECORDING }
        .distinctUntilChanged()
        .flatMapLatest { recording ->
            if (recording) {
                flow {
                    while (true) {
                        emit(elapsedRealtimeMs())
                        delay(tickMs)
                    }
                }
            } else {
                flowOf(elapsedRealtimeMs())
            }
        }

    private val sessionSegments: Flow<SessionSegments> = controller.state
        .map { it.sessionId?.value }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(SessionSegments(CaptureSummary.Empty, emptyList()))
            } else {
                segments.observeSession(sessionId).map { rows ->
                    // Quarantined segments are gaps too: they carry no usable audio.
                    val usable = rows.filter { it.defect == null && it.state != SegmentState.WRITING.name }
                    SessionSegments(
                        summary = CaptureSummary.of(rows.map { it.toSegment() }),
                        gaps = gapDetector.detect(usable.map { it.toTiming() }),
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)

    private val storageHealth: Flow<StorageHealth> =
        combine(segments.observePendingBytes(), ticks) { pending, _ -> storage.evaluate(pending) }
            .flowOn(Dispatchers.IO)

    private val healthSources: Flow<HealthSources> = combine(
        controller.state,
        controller.statusSinceElapsedMs,
        engineHealth,
        sessionSegments,
        storageHealth,
    ) { state, since, engine, session, storageNow ->
        HealthSources(state, since, engine, session, storageNow)
    }

    val overview: StateFlow<CaptureOverview?> = combine(healthSources, watchLink) { sources, watch ->
        val now = elapsedRealtimeMs()
        val summary = sources.session.summary
        val health = evaluator.evaluate(
            CaptureHealthInputs(
                state = sources.state,
                nowElapsedMs = now,
                statusSinceElapsedMs = sources.statusSinceElapsedMs,
                lastSealedSegmentEndElapsedMs = sources.engine.lastSealedElapsedMs,
                storage = sources.storage,
                microphoneSilent = sources.engine.microphoneSilent,
                pendingUploadMs = backlogPendingMs(summary, expectUploads()),
                watch = watch,
            ),
        )
        CaptureOverview(
            health = health,
            summary = summary,
            storage = sources.storage,
            gaps = sources.session.gaps,
            lastSavedAgoMs = sources.engine.lastSealedElapsedMs?.let { now - it },
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
