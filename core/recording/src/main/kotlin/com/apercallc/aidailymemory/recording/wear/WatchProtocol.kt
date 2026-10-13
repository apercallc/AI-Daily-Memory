package com.apercallc.aidailymemory.recording.wear

import java.util.LinkedHashMap

/** User-issued phone-controller commands. Capture-engine signals are intentionally absent. */
enum class WatchCommand(val serviceAction: String, val isMarker: Boolean = false) {
    Start("START"),
    Pause("PAUSE"),
    Resume("RESUME"),
    Stop("STOP"),
    Highlight("HIGHLIGHT", isMarker = true),
    QuickNote("QUICK_NOTE", isMarker = true),
}

/** Bounded recent-outcome cache for MessageClient redelivery deduplication. */
class CommandAcknowledgements<T>(private val capacity: Int) {
    private val entries = LinkedHashMap<String, T>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun get(commandId: String): T? = entries[commandId]

    /** Oldest first, for persisting. */
    @Synchronized
    fun snapshot(): List<Pair<String, T>> = entries.entries.map { it.key to it.value }

    @Synchronized
    fun remember(commandId: String, outcome: T) {
        entries[commandId] = outcome
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}

object SnapshotRevisionPolicy {
    fun shouldAccept(currentRevision: Long?, incomingRevision: Long, reset: Boolean = false): Boolean =
        incomingRevision >= 0 && (reset || currentRevision == null || incomingRevision >= currentRevision)
}

enum class CommandOutcome { Applied, Ignored, Rejected }

data class CommandAcknowledgement(
    val commandId: String,
    val outcome: CommandOutcome,
    val revision: Long,
)

data class PhoneRecordingSnapshot(
    val revision: Long,
    val status: String,
    val sessionStartedAt: Long?,
    val problem: String?,
    val captureHealth: String,
    val reset: Boolean = false,
) {
    init {
        require(revision >= 0)
    }
}
