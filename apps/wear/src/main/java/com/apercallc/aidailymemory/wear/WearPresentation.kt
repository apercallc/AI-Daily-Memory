package com.apercallc.aidailymemory.wear

import androidx.annotation.StringRes
import com.apercallc.aidailymemory.recording.health.HealthLevel
import com.apercallc.aidailymemory.recording.wear.WatchCommand

/** How loudly the status area speaks. Drives the dot colour; the headline text always says it too. */
enum class WearTone { RECORDING, PAUSED, BUSY, ATTENTION, IDLE, OFFLINE }

/** Visual weight of a control: the likely next action is filled, Stop is clearly destructive. */
enum class Emphasis { PRIMARY, TONAL, DANGER }

/** Pure presentation rules for the watch; the composables only render what these decide. */
internal object WearPresentation {
    fun tone(state: WearControlState): WearTone {
        val snapshot = state.snapshot
        if (!state.phoneConnected || snapshot == null) return WearTone.OFFLINE
        if (snapshot.problem != null || snapshot.captureHealth == HealthLevel.CRITICAL.name) {
            return WearTone.ATTENTION
        }
        return when (snapshot.status) {
            "RECORDING" -> WearTone.RECORDING
            "PAUSED" -> WearTone.PAUSED
            "STARTING", "RESUMING", "STOPPING" -> WearTone.BUSY
            "INTERRUPTED", "RECOVERING" -> WearTone.ATTENTION
            "IDLE", "COMPLETED" -> WearTone.IDLE
            else -> WearTone.OFFLINE
        }
    }

    /** The phone only reports a start time for a live session; it is meaningless otherwise. */
    fun showsSessionStart(state: WearControlState, ignoreStartTime: Boolean = false): Boolean {
        val snapshot = state.snapshot ?: return false
        if (!state.phoneConnected) return false
        if (!ignoreStartTime && snapshot.sessionStartedAt == null) return false
        return snapshot.status in LIVE_STATUSES
    }

    /**
     * How long the open session has run, for the same live+connected+known-start session
     * [showsSessionStart] gates ("Session duration" — TODO.md Phase 3). Clamped to zero so a
     * watch clock that drifts ahead of the phone's reported start never shows a negative time.
     */
    fun sessionDurationMs(state: WearControlState, nowEpochMs: Long): Long? {
        if (!showsSessionStart(state)) return null
        val startedAt = state.snapshot?.sessionStartedAt ?: return null
        return (nowEpochMs - startedAt).coerceAtLeast(0L)
    }

    /** Most-used first: a marker while recording, then pause, with Stop last and apart. */
    fun displayOrder(commands: List<WatchCommand>): List<WatchCommand> =
        commands.sortedBy { ORDER.indexOf(it) }

    fun emphasis(command: WatchCommand): Emphasis = when (command) {
        WatchCommand.Start, WatchCommand.Resume, WatchCommand.Highlight -> Emphasis.PRIMARY
        WatchCommand.Pause, WatchCommand.QuickNote -> Emphasis.TONAL
        WatchCommand.Stop -> Emphasis.DANGER
    }

    /** One-line explanation under a marker's label, matching the phone's captions. */
    @StringRes
    fun hint(command: WatchCommand): Int? = when (command) {
        WatchCommand.Highlight -> R.string.action_highlight_hint
        WatchCommand.QuickNote -> R.string.action_quick_note_hint
        else -> null
    }

    private val LIVE_STATUSES = setOf("RECORDING", "PAUSED", "INTERRUPTED", "RECOVERING", "RESUMING")
    private val ORDER = listOf(
        WatchCommand.Start,
        WatchCommand.Resume,
        WatchCommand.Highlight,
        WatchCommand.QuickNote,
        WatchCommand.Pause,
        WatchCommand.Stop,
    )
}
