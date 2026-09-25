package com.apercallc.aidailymemory.recording.presentation

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.HealthIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureStatusPresenterTest {
    private val healthy = CaptureHealth.Unknown

    private fun state(status: RecordingStatus, problem: CaptureProblem? = null) = RecordingState(
        status = status,
        sessionId = if (status == RecordingStatus.IDLE) null else SessionId("s"),
        revision = 3,
        problem = problem,
    )

    private fun present(
        status: RecordingStatus,
        health: CaptureHealth = healthy,
        problem: CaptureProblem? = null,
    ) = CaptureStatusPresenter.present(state(status, problem), health)

    @Test
    fun `every status maps to a headline, tone, and primary action`() {
        val expected = mapOf(
            RecordingStatus.IDLE to Triple(Headline.READY, StatusTone.NEUTRAL, PrimaryAction.START),
            RecordingStatus.STARTING to
                Triple(Headline.STARTING, StatusTone.NEUTRAL, PrimaryAction.NONE),
            RecordingStatus.RECORDING to
                Triple(Headline.RECORDING, StatusTone.RECORDING, PrimaryAction.PAUSE),
            RecordingStatus.PAUSED to
                Triple(Headline.PAUSED, StatusTone.PAUSED, PrimaryAction.RESUME),
            RecordingStatus.RESUMING to
                Triple(Headline.RESUMING, StatusTone.NEUTRAL, PrimaryAction.PAUSE),
            RecordingStatus.INTERRUPTED to
                Triple(Headline.INTERRUPTED, StatusTone.ERROR, PrimaryAction.PAUSE),
            RecordingStatus.RECOVERING to
                Triple(Headline.RECOVERING, StatusTone.WARNING, PrimaryAction.PAUSE),
            RecordingStatus.STOPPING to
                Triple(Headline.STOPPING, StatusTone.NEUTRAL, PrimaryAction.NONE),
            RecordingStatus.COMPLETED to
                Triple(Headline.COMPLETED, StatusTone.NEUTRAL, PrimaryAction.START),
        )
        RecordingStatus.entries.forEach { status ->
            val model = present(status)
            assertEquals(
                expected.getValue(status),
                Triple(model.headline, model.tone, model.primaryAction),
                "$status",
            )
        }
    }

    @Test
    fun `recording is never shown when health says audio is not being saved`() {
        val model = present(
            RecordingStatus.RECORDING,
            CaptureHealth(listOf(HealthIssue.CAPTURE_STALLED)),
        )

        assertEquals(Headline.NOT_CAPTURING, model.headline)
        assertEquals(StatusTone.ERROR, model.tone)
        assertFalse(model.canMark)
        assertTrue(model.canStop)
        assertEquals(PrimaryAction.PAUSE, model.primaryAction)
    }

    @Test
    fun `warnings keep recording visible but change its tone`() {
        val model = present(RecordingStatus.RECORDING, CaptureHealth(listOf(HealthIssue.STORAGE_LOW)))

        assertEquals(Headline.RECORDING, model.headline)
        assertEquals(StatusTone.WARNING, model.tone)
        assertTrue(model.canMark)
        assertEquals(listOf(HealthIssue.STORAGE_LOW), model.issues)
    }

    @Test
    fun `a failed start explains itself`() {
        val model = present(RecordingStatus.IDLE, problem = CaptureProblem.PERMISSION_DENIED)

        assertEquals(Headline.START_FAILED, model.headline)
        assertEquals(StatusTone.ERROR, model.tone)
        assertEquals(CaptureProblem.PERMISSION_DENIED, model.problem)
        assertEquals(PrimaryAction.START, model.primaryAction)
    }

    @Test
    fun `stop and marking are offered only where they make sense`() {
        val stoppable = RecordingStatus.entries.filter { present(it).canStop }.toSet()
        val markable = RecordingStatus.entries.filter { present(it).canMark }.toSet()

        assertEquals(
            setOf(
                RecordingStatus.STARTING,
                RecordingStatus.RECORDING,
                RecordingStatus.PAUSED,
                RecordingStatus.RESUMING,
                RecordingStatus.INTERRUPTED,
                RecordingStatus.RECOVERING,
            ),
            stoppable,
        )
        assertEquals(setOf(RecordingStatus.RECORDING), markable)
    }

    @Test
    fun `only an interrupted session offers a user retry`() {
        val retryable = RecordingStatus.entries.filter { present(it).canRetry }.toSet()

        assertEquals(setOf(RecordingStatus.INTERRUPTED), retryable)
    }

    @Test
    fun `a schedule pause reads as quiet time, still offers resume, and never shows red`() {
        val quiet = RecordingState(
            RecordingStatus.PAUSED,
            SessionId("s"),
            revision = 3,
            problem = null,
            pausedBySchedule = true,
        )

        val model = CaptureStatusPresenter.present(quiet, CaptureHealth.Unknown)

        assertEquals(Headline.QUIET_TIME, model.headline)
        assertEquals(StatusTone.PAUSED, model.tone)
        assertEquals(PrimaryAction.RESUME, model.primaryAction)
        assertTrue(model.canStop)
        assertFalse(model.canMark)
    }
}
