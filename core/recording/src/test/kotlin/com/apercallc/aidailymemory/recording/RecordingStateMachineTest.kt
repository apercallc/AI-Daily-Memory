package com.apercallc.aidailymemory.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingStateMachineTest {
    private val machine = RecordingStateMachine()

    @Test
    fun `all supported transitions resolve to expected states`() {
        val cases = listOf(
            Case(RecordingStatus.IDLE, RecordingEvent.Start, RecordingStatus.STARTING),
            Case(RecordingStatus.STARTING, RecordingEvent.Started, RecordingStatus.RECORDING),
            Case(RecordingStatus.RECORDING, RecordingEvent.Pause, RecordingStatus.PAUSED),
            Case(RecordingStatus.PAUSED, RecordingEvent.Resume, RecordingStatus.RECORDING),
            Case(
                RecordingStatus.RECORDING,
                RecordingEvent.Interrupt,
                RecordingStatus.INTERRUPTED,
            ),
            Case(
                RecordingStatus.INTERRUPTED,
                RecordingEvent.Recover,
                RecordingStatus.RECOVERING,
            ),
            Case(
                RecordingStatus.RECOVERING,
                RecordingEvent.Recovered,
                RecordingStatus.RECORDING,
            ),
            Case(RecordingStatus.RECORDING, RecordingEvent.Stop, RecordingStatus.STOPPING),
            Case(RecordingStatus.PAUSED, RecordingEvent.Stop, RecordingStatus.STOPPING),
            Case(RecordingStatus.INTERRUPTED, RecordingEvent.Stop, RecordingStatus.STOPPING),
            Case(RecordingStatus.RECOVERING, RecordingEvent.Stop, RecordingStatus.STOPPING),
            Case(RecordingStatus.STOPPING, RecordingEvent.Stopped, RecordingStatus.COMPLETED),
            Case(RecordingStatus.COMPLETED, RecordingEvent.Reset, RecordingStatus.IDLE),
        )

        cases.forEach { case ->
            assertEquals(case.expected, machine.reduce(case.current, case.event))
        }
    }

    @Test
    fun `invalid transition fails closed`() {
        val error = assertFailsWith<InvalidRecordingTransition> {
            machine.reduce(RecordingStatus.IDLE, RecordingEvent.Pause)
        }

        assertEquals(RecordingStatus.IDLE, error.current)
        assertEquals(RecordingEvent.Pause, error.event)
    }

    private data class Case(
        val current: RecordingStatus,
        val event: RecordingEvent,
        val expected: RecordingStatus,
    )
}
