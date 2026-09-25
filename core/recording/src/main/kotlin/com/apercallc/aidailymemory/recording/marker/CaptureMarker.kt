package com.apercallc.aidailymemory.recording.marker

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus

/** User-placed marker inside a recording session (ADR-0011). */
enum class MarkerKind {
    /** "That was important": covers the moments just before the tap. */
    HIGHLIGHT,

    /** "Listen to what I say next": covers the moments just after the tap. */
    QUICK_NOTE,
}

/**
 * The span of session audio a marker points at, relative to the tap on the monotonic clock.
 * Evidence stays in the segments; a marker only references a window of them.
 */
data class MarkerWindow(val beforeMs: Long, val afterMs: Long) {
    init {
        require(beforeMs >= 0 && afterMs >= 0) { "window bounds must not be negative" }
        require(beforeMs + afterMs > 0) { "window must not be empty" }
    }
}

sealed interface MarkerDecision {
    data class Place(val kind: MarkerKind, val window: MarkerWindow) : MarkerDecision

    /** A second tap of the same kind inside the debounce window: acknowledge, don't duplicate. */
    data object Duplicate : MarkerDecision

    /** Nothing is being captured, so a marker would point at audio that does not exist. */
    data object NotCapturing : MarkerDecision
}

/**
 * Pure placement rules. Markers are only placed while audio is really being captured: a
 * highlight during a pause or an interruption would cite evidence that was never recorded.
 */
data class MarkerPolicy(
    val highlightWindow: MarkerWindow,
    val quickNoteWindow: MarkerWindow,
    val debounceMs: Long,
) {
    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
    }

    /**
     * @param capturing the presenter's `canMark`: RECORDING *and* capture health is not critical.
     * @param lastSameKindElapsedMs monotonic time of the previous marker of [kind] in this
     * session and boot, or null.
     */
    fun decide(
        state: RecordingState,
        capturing: Boolean,
        kind: MarkerKind,
        nowElapsedMs: Long,
        lastSameKindElapsedMs: Long?,
    ): MarkerDecision {
        if (state.status != RecordingStatus.RECORDING || !capturing) {
            return MarkerDecision.NotCapturing
        }
        if (lastSameKindElapsedMs != null && nowElapsedMs - lastSameKindElapsedMs in 0..debounceMs) {
            return MarkerDecision.Duplicate
        }
        val window = when (kind) {
            MarkerKind.HIGHLIGHT -> highlightWindow
            MarkerKind.QUICK_NOTE -> quickNoteWindow
        }
        return MarkerDecision.Place(kind, window)
    }

    companion object {
        /** Provisional until usage data exists; the window is metadata and can be re-derived. */
        val Default = MarkerPolicy(
            highlightWindow = MarkerWindow(beforeMs = 30_000, afterMs = 5_000),
            quickNoteWindow = MarkerWindow(beforeMs = 0, afterMs = 60_000),
            debounceMs = 2_000,
        )
    }
}
