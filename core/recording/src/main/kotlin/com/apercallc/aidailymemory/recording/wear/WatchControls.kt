package com.apercallc.aidailymemory.recording.wear

import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.health.HealthLevel

/**
 * Which controls the watch offers for a phone-confirmed snapshot. Pure and shared so the rule
 * is tested once, in the module that owns [RecordingStatus] and [HealthLevel].
 */
object WatchControls {
    fun availableCommands(snapshot: PhoneRecordingSnapshot): List<WatchCommand> {
        // An unrecognised status (a newer phone) offers nothing rather than guessing.
        val status = RecordingStatus.entries.firstOrNull { it.name == snapshot.status } ?: return emptyList()
        val stateCommands = when (status) {
            RecordingStatus.IDLE, RecordingStatus.COMPLETED -> listOf(WatchCommand.Start)
            RecordingStatus.RECORDING,
            RecordingStatus.RESUMING,
            RecordingStatus.RECOVERING,
            -> listOf(WatchCommand.Pause, WatchCommand.Stop)
            RecordingStatus.STARTING -> listOf(WatchCommand.Stop)
            RecordingStatus.PAUSED, RecordingStatus.INTERRUPTED -> listOf(WatchCommand.Resume, WatchCommand.Stop)
            RecordingStatus.STOPPING -> emptyList()
        }
        // Markers need audio actually being saved.
        if (status != RecordingStatus.RECORDING || snapshot.captureHealth == HealthLevel.CRITICAL.name) {
            return stateCommands
        }
        return stateCommands + listOf(WatchCommand.Highlight, WatchCommand.QuickNote)
    }
}

/** A reachable device as the Wearable API reports it, reduced to what choosing needs. */
data class WatchNode(val id: String, val isNearby: Boolean)

object PhoneNodeChoice {
    /**
     * The node commands go to: a directly connected (nearby) phone first. A cloud-relayed node
     * can be a different device, so it is only used when no nearby node exists.
     */
    fun pick(nodes: List<WatchNode>): WatchNode? = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
}
