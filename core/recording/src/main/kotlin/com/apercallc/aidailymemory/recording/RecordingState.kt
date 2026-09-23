package com.apercallc.aidailymemory.recording

enum class RecordingStatus {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    INTERRUPTED,
    RECOVERING,
    STOPPING,
    COMPLETED,
}

sealed interface RecordingEvent {
    data object Start : RecordingEvent
    data object Started : RecordingEvent
    data object Pause : RecordingEvent
    data object Resume : RecordingEvent
    data object Interrupt : RecordingEvent
    data object Recover : RecordingEvent
    data object Recovered : RecordingEvent
    data object Stop : RecordingEvent
    data object Stopped : RecordingEvent
    data object Reset : RecordingEvent
}

class InvalidRecordingTransition(
    val current: RecordingStatus,
    val event: RecordingEvent,
) : IllegalStateException("Invalid recording transition: $current + $event")

class RecordingStateMachine {
    fun reduce(
        current: RecordingStatus,
        event: RecordingEvent,
    ): RecordingStatus = when (current to event) {
        RecordingStatus.IDLE to RecordingEvent.Start -> RecordingStatus.STARTING
        RecordingStatus.STARTING to RecordingEvent.Started -> RecordingStatus.RECORDING
        RecordingStatus.RECORDING to RecordingEvent.Pause -> RecordingStatus.PAUSED
        RecordingStatus.PAUSED to RecordingEvent.Resume -> RecordingStatus.RECORDING
        RecordingStatus.RECORDING to RecordingEvent.Interrupt -> RecordingStatus.INTERRUPTED
        RecordingStatus.INTERRUPTED to RecordingEvent.Recover -> RecordingStatus.RECOVERING
        RecordingStatus.RECOVERING to RecordingEvent.Recovered -> RecordingStatus.RECORDING
        RecordingStatus.RECORDING to RecordingEvent.Stop -> RecordingStatus.STOPPING
        RecordingStatus.PAUSED to RecordingEvent.Stop -> RecordingStatus.STOPPING
        RecordingStatus.INTERRUPTED to RecordingEvent.Stop -> RecordingStatus.STOPPING
        RecordingStatus.RECOVERING to RecordingEvent.Stop -> RecordingStatus.STOPPING
        RecordingStatus.STOPPING to RecordingEvent.Stopped -> RecordingStatus.COMPLETED
        RecordingStatus.COMPLETED to RecordingEvent.Reset -> RecordingStatus.IDLE
        else -> throw InvalidRecordingTransition(current, event)
    }
}
