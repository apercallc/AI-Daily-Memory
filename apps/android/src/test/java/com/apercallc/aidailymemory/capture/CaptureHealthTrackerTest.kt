package com.apercallc.aidailymemory.capture

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.data.segmentEntity
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.health.HealthIssue
import com.apercallc.aidailymemory.recording.segment.CaptureSummary
import com.apercallc.aidailymemory.recording.segment.SegmentState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression: the capture-health "upload backlog" warning presumes a linked account. With no
 * account connected (the whole private device-test milestone), hours of local-only audio are
 * the design, and warning about them would recolor every long recording day as a problem.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureHealthTrackerTest {
    private val app = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>()
    private val container = app.container

    @Test
    fun `backlog warnings wait for a linked account`() {
        val summary = CaptureSummary(
            capturedMs = SEVEN_HOURS,
            pendingUploadMs = SEVEN_HOURS,
            pendingUploadBytes = 15L * 1024 * 1024,
            quarantinedSegments = 0,
            lastPersistedSequence = 1,
        )

        // Linked: the evaluator sees the real backlog (7 h is past the 6 h warn threshold).
        assertEquals(SEVEN_HOURS, backlogPendingMs(summary, uploadsExpected = true))
        // Unlinked: nothing to upload to, so nothing to warn about.
        assertEquals(0L, backlogPendingMs(summary, uploadsExpected = false))
    }

    @Test
    fun `a long unlinked recording day stays a healthy recording`() = runBlocking {
        // Real AppContainer wiring: no account is linked in a fresh install under test.
        val sessionId = SessionId("session-unlinked-backlog")
        container.database.segments().insert(
            segmentEntity(sessionId = sessionId.value, state = SegmentState.SEALED.name)
                .copy(durationMs = SEVEN_HOURS),
        )

        // No microphone: reach RECORDING through the state machine with a no-op effect handler.
        container.controller.awaitRestored()
        container.controller.attach(EffectHandler { _, _ -> emptyList() })
        container.controller.dispatch(RecordingCommand.Start(sessionId))
        container.controller.dispatch(CaptureSignal.Started(sessionId))

        val keepWarm = launch { container.overview.collect {} }
        withTimeout(5_000) {
            while (container.health.overview.value?.summary?.pendingUploadMs != SEVEN_HOURS) delay(10)
        }
        val overview = container.health.overview.value

        // The "Only on this phone" stat stays honest: the audio really is only here.
        assertEquals(SEVEN_HOURS, overview?.summary?.pendingUploadMs)
        // But it is not a health warning while no account is connected.
        assertFalse(HealthIssue.UPLOAD_BACKLOG in (overview?.health?.issues ?: listOf()))

        keepWarm.cancel()
    }

    private companion object {
        /** Past the evaluator's 6 h upload-backlog warn threshold (HealthPolicy.Provisional). */
        const val SEVEN_HOURS = 7 * 60 * 60_000L
    }
}