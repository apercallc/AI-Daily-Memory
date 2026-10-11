package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot

object WatchSnapshotFactory {
    fun from(
        state: RecordingState,
        captureHealth: String,
        reset: Boolean = false,
    ): PhoneRecordingSnapshot = PhoneRecordingSnapshot(
        revision = state.revision,
        status = state.status.name,
        sessionStartedAt = null,
        problem = state.problem?.name,
        captureHealth = captureHealth,
        reset = reset,
    )
}
