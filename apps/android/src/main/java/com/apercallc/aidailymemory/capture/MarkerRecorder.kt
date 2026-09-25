package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.MarkerDao
import com.apercallc.aidailymemory.data.MarkerEntity
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.marker.MarkerDecision
import com.apercallc.aidailymemory.recording.marker.MarkerKind
import com.apercallc.aidailymemory.recording.marker.MarkerPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Places highlight and Quick Note markers (ADR-0011) according to [MarkerPolicy]. */
class MarkerRecorder(
    private val controller: RecordingController,
    private val dao: MarkerDao,
    private val policy: MarkerPolicy,
    private val clock: DeviceClock,
    private val ids: UuidV7Generator,
) {
    private val lock = Mutex()

    /** @param capturing the presenter's canMark (RECORDING and health not critical). */
    suspend fun place(kind: MarkerKind, capturing: Boolean): MarkerDecision = lock.withLock {
        val state = controller.state.value
        val time = clock.now()
        val sessionId = state.sessionId
        val previous = sessionId?.let { dao.latest(it.value, kind.name) }
            ?.time
            // Only a marker from this boot can be compared on the monotonic clock.
            ?.takeIf { it.bootId == time.bootId }
            ?.elapsedRealtimeMs
        val decision = policy.decide(state, capturing, kind, time.elapsedRealtimeMs, previous)
        if (decision is MarkerDecision.Place && sessionId != null) {
            dao.insert(
                MarkerEntity(
                    markerId = ids.next().toString(),
                    sessionId = sessionId.value,
                    kind = decision.kind.name,
                    windowBeforeMs = decision.window.beforeMs,
                    windowAfterMs = decision.window.afterMs,
                    time = CaptureTimeColumns.of(time),
                ),
            )
        }
        decision
    }
}
