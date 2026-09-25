package com.apercallc.aidailymemory.recording.health

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel

enum class HealthLevel { OK, INFO, WARNING, CRITICAL }

enum class HealthIssue(val level: HealthLevel) {
    /** Recording claimed, but no segment has been sealed recently. Audio is not being saved. */
    CAPTURE_STALLED(HealthLevel.CRITICAL),

    /** The microphone is delivering exact digital silence (blocked or revoked). */
    MICROPHONE_BLOCKED(HealthLevel.CRITICAL),

    /** The session is interrupted or recovering; see RecordingState.problem. */
    CAPTURE_INTERRUPTED(HealthLevel.CRITICAL),
    STORAGE_CRITICAL(HealthLevel.CRITICAL),
    STORAGE_LOW(HealthLevel.WARNING),
    STORAGE_UNKNOWN(HealthLevel.WARNING),
    UPLOAD_BACKLOG(HealthLevel.WARNING),

    /** Capture is unaffected; only watch control is unavailable. */
    WATCH_DISCONNECTED(HealthLevel.INFO),
}

enum class WatchLink { NOT_PAIRED, CONNECTED, DISCONNECTED }

data class CaptureHealthInputs(
    val state: RecordingState,
    val nowElapsedMs: Long,
    /** Monotonic time when the current status was entered. */
    val statusSinceElapsedMs: Long,
    /** Monotonic end time of the newest sealed segment in this boot, if any. */
    val lastSealedSegmentEndElapsedMs: Long?,
    val storage: StorageHealth,
    val microphoneSilent: Boolean,
    val pendingUploadMs: Long,
    val watch: WatchLink,
)

data class HealthPolicy(
    val expectedSegmentMs: Long,
    val stallGraceMs: Long,
    val uploadBacklogWarnMs: Long,
) {
    init {
        require(expectedSegmentMs > 0) { "expectedSegmentMs must be positive" }
        require(stallGraceMs >= 0) { "stallGraceMs must not be negative" }
        require(uploadBacklogWarnMs > 0) { "uploadBacklogWarnMs must be positive" }
    }

    companion object {
        /** Provisional until Phase 0 picks the segment length (ADR-0007). */
        val Provisional = HealthPolicy(
            expectedSegmentMs = 60_000,
            stallGraceMs = 15_000,
            uploadBacklogWarnMs = 6 * 60 * 60_000L,
        )
    }
}

data class CaptureHealth(val issues: List<HealthIssue>) {
    val level: HealthLevel = issues.maxOfOrNull { it.level } ?: HealthLevel.OK

    companion object {
        val Unknown = CaptureHealth(emptyList())
    }
}

/**
 * Pure evaluator for the capture-health surface. Its main job is making sure the UI can never
 * show "Recording" while audio is not actually being saved.
 */
class CaptureHealthEvaluator(private val policy: HealthPolicy) {
    fun evaluate(inputs: CaptureHealthInputs): CaptureHealth {
        val issues = mutableListOf<HealthIssue>()
        when (inputs.state.status) {
            RecordingStatus.RECORDING -> {
                if (isStalled(inputs)) issues += HealthIssue.CAPTURE_STALLED
                if (inputs.microphoneSilent) issues += HealthIssue.MICROPHONE_BLOCKED
            }
            RecordingStatus.INTERRUPTED, RecordingStatus.RECOVERING ->
                issues += HealthIssue.CAPTURE_INTERRUPTED
            else -> Unit
        }
        when (inputs.storage.level) {
            StorageLevel.CRITICAL -> issues += HealthIssue.STORAGE_CRITICAL
            StorageLevel.LOW -> issues += HealthIssue.STORAGE_LOW
            StorageLevel.UNKNOWN -> issues += HealthIssue.STORAGE_UNKNOWN
            StorageLevel.OK -> Unit
        }
        if (inputs.pendingUploadMs > policy.uploadBacklogWarnMs) {
            issues += HealthIssue.UPLOAD_BACKLOG
        }
        if (inputs.watch == WatchLink.DISCONNECTED) issues += HealthIssue.WATCH_DISCONNECTED
        return CaptureHealth(issues.sortedByDescending { it.level })
    }

    private fun isStalled(inputs: CaptureHealthInputs): Boolean {
        // Measure from whichever is newer: entering RECORDING, or the last sealed segment.
        // A segment sealed before a pause must not make a fresh resume look stalled.
        val lastProgress = maxOf(
            inputs.statusSinceElapsedMs,
            inputs.lastSealedSegmentEndElapsedMs ?: Long.MIN_VALUE,
        )
        return inputs.nowElapsedMs - lastProgress > policy.expectedSegmentMs + policy.stallGraceMs
    }
}
