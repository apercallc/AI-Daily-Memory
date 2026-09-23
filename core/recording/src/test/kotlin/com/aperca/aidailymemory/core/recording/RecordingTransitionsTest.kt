package com.aperca.aidailymemory.core.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingTransitionsTest {
    @Test
    fun idleCanStart() {
        assertTrue(RecordingTransitions.canTransition(RecordingState.IDLE, RecordingState.STARTING))
    }

    @Test
    fun recordingCanPauseInterruptOrStop() {
        assertTrue(RecordingTransitions.canTransition(RecordingState.RECORDING, RecordingState.PAUSED))
        assertTrue(RecordingTransitions.canTransition(RecordingState.RECORDING, RecordingState.INTERRUPTED))
        assertTrue(RecordingTransitions.canTransition(RecordingState.RECORDING, RecordingState.STOPPING))
    }

    @Test
    fun pausedCanResumeOrStop() {
        assertTrue(RecordingTransitions.canTransition(RecordingState.PAUSED, RecordingState.RECORDING))
        assertTrue(RecordingTransitions.canTransition(RecordingState.PAUSED, RecordingState.STOPPING))
    }

    @Test
    fun completedCannotTransition() {
        RecordingState.entries.forEach { target ->
            assertFalse(RecordingTransitions.canTransition(RecordingState.COMPLETED, target))
        }
    }

    @Test
    fun invalidTransitionThrows() {
        assertThrows(InvalidRecordingTransition::class.java) {
            RecordingTransitions.requireTransition(RecordingState.IDLE, RecordingState.COMPLETED)
        }
    }
}
