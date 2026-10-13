package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin

object WatchSnapshotFactory {
    fun from(
        state: RecordingState,
        captureHealth: String,
        reset: Boolean = false,
        origin: RecordingOrigin? = null,
    ): PhoneRecordingSnapshot = PhoneRecordingSnapshot(
        revision = state.revision,
        status = state.status.name,
        sessionStartedAt = null,
        problem = state.problem?.name,
        captureHealth = captureHealth,
        reset = reset,
        origin = origin?.name,
    )

    /**
     * A watch must accept this snapshot unconditionally (ADR-0008 "reset"): either the phone's
     * journal is fresh, or its revision went *backwards* against what was already published.
     *
     * Deleting the newest session removes that session's journal rows, so a later restore can
     * replay revisions a watch has already shown; without the flag the watch would discard
     * every snapshot of the next session as stale and keep showing the deleted one — possibly
     * "recording" — forever. [lastPublishedRevision] comes from [WatchRevisionWatermark].
     */
    fun needsReset(revision: Long, lastPublishedRevision: Long?): Boolean =
        revision == 0L || (lastPublishedRevision != null && revision < lastPublishedRevision)
}
