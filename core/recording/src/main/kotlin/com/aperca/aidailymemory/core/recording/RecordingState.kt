package com.aperca.aidailymemory.core.recording

/**
 * Persisted logical recording states.
 *
 * Keep this model framework-free so transitions can be exhaustively unit tested.
 */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    INTERRUPTED,
    RECOVERING,
    STOPPING,
    COMPLETED,
}

class InvalidRecordingTransition(
    from: RecordingState,
    to: RecordingState,
) : IllegalStateException("Invalid recording transition: $from -> $to")

object RecordingTransitions {
    private val allowed: Map<RecordingState, Set<RecordingState>> =
        mapOf(
            RecordingState.IDLE to setOf(RecordingState.STARTING),
            RecordingState.STARTING to setOf(RecordingState.RECORDING, RecordingState.INTERRUPTED, RecordingState.IDLE),
            RecordingState.RECORDING to setOf(RecordingState.PAUSED, RecordingState.INTERRUPTED, RecordingState.STOPPING),
            RecordingState.PAUSED to setOf(RecordingState.RECORDING, RecordingState.STOPPING),
            RecordingState.INTERRUPTED to setOf(RecordingState.RECOVERING, RecordingState.STOPPING),
            RecordingState.RECOVERING to setOf(RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.STOPPING),
            RecordingState.STOPPING to setOf(RecordingState.COMPLETED),
            RecordingState.COMPLETED to emptySet(),
        )

    fun canTransition(
        from: RecordingState,
        to: RecordingState,
    ): Boolean = to in allowed.getValue(from)

    fun requireTransition(
        from: RecordingState,
        to: RecordingState,
    ): RecordingState {
        if (!canTransition(from, to)) {
            throw InvalidRecordingTransition(from, to)
        }
        return to
    }
}
