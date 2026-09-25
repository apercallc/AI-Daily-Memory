package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.platform.StorageMonitor
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.CaptureHealthEvaluator
import com.apercallc.aidailymemory.recording.health.CaptureHealthInputs
import com.apercallc.aidailymemory.recording.health.WatchLink
import com.apercallc.aidailymemory.recording.segment.CaptureSummary
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

/**
 * Feeds [CaptureHealthEvaluator] from live sources. Runs only while someone is watching
 * (UI or notification), and ticks slowly: stall detection needs seconds, not frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureHealthTracker(
    controller: RecordingController,
    engineHealth: StateFlow<EngineHealth>,
    segments: SegmentDao,
    storage: StorageMonitor,
    evaluator: CaptureHealthEvaluator,
    elapsedRealtimeMs: () -> Long,
    scope: CoroutineScope,
    tickMs: Long = 5_000,
) {
    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(elapsedRealtimeMs())
            delay(tickMs)
        }
    }

    private val summaries: Flow<CaptureSummary> = controller.state
        .map { it.sessionId?.value }
        .flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(CaptureSummary.Empty)
            } else {
                segments.observeSession(sessionId).map { rows ->
                    CaptureSummary.of(rows.map { it.toSegment() })
                }
            }
        }

    private val storageHealth: Flow<StorageHealth> =
        combine(segments.observePendingBytes(), ticks) { pending, _ -> storage.evaluate(pending) }
            .flowOn(Dispatchers.IO)

    val overview: StateFlow<CaptureOverview?> = combine(
        controller.state,
        controller.statusSinceElapsedMs,
        engineHealth,
        summaries,
        storageHealth,
    ) { state, since, engine, summary, storageNow ->
        val health = evaluator.evaluate(
            CaptureHealthInputs(
                state = state,
                nowElapsedMs = elapsedRealtimeMs(),
                statusSinceElapsedMs = since,
                lastSealedSegmentEndElapsedMs = engine.lastSealedElapsedMs,
                storage = storageNow,
                microphoneSilent = engine.microphoneSilent,
                pendingUploadMs = summary.pendingUploadMs,
                // Phase 3 wires the Data Layer; until then there is no watch to report.
                watch = WatchLink.NOT_PAIRED,
            ),
        )
        CaptureOverview(health, summary, storageNow)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
