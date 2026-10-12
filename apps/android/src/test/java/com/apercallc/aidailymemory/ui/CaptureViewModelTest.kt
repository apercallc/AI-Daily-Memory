package com.apercallc.aidailymemory.ui

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.capture.EffectHandler
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The on-stop Keep/Delete decision (TODO.md "private device-test milestone") drives real local
 * deletion and had no coverage at all (code-review-graph flagged `handledStopDecisionSessionId`,
 * `markStopDecisionHandled` as untested). Drives
 * [com.apercallc.aidailymemory.capture.RecordingController] straight to COMPLETED with a fake
 * attached effect handler, the same technique `ControllerResilienceTest` uses, so no real
 * microphone or service is needed.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`, which under Robolectric is backed by a
 * paused Looper by default; a real `Main` would silently never pump `combine`/`stateIn`
 * updates posted to it. `setMain(UnconfinedTestDispatcher())` makes it run inline instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>()
    private val container = app.container

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Reaches COMPLETED without a real microphone: the engine's `Started`/`Stopped` signals are simulated. */
    private suspend fun completeSession(sessionId: SessionId) {
        container.controller.awaitRestored()
        container.controller.attach(
            EffectHandler { effect, _ ->
                when (effect) {
                    is CaptureEffect.FinishSession -> listOf(CaptureSignal.Stopped(effect.sessionId))
                    else -> emptyList()
                }
            },
        )
        container.controller.dispatch(RecordingCommand.Start(sessionId))
        container.controller.dispatch(CaptureSignal.Started(sessionId))
        container.controller.dispatch(RecordingCommand.Stop)
    }

    /** Polls the StateFlow's current value: avoids `first()` racing a `WhileSubscribed` restart. */
    private suspend fun awaitDecision(viewModel: CaptureViewModel, present: Boolean) = withTimeout(5_000) {
        while ((viewModel.uiState.value.stopDecisionSessionId != null) != present) delay(10)
    }

    @Test
    fun `a completed session surfaces exactly its own keep-or-delete decision`() = runBlocking {
        val sessionId = SessionId("session-decision")
        val viewModel = CaptureViewModel(container) {}
        val keepWarm = launch { viewModel.uiState.collect {} }

        completeSession(sessionId)
        awaitDecision(viewModel, present = true)

        assertEquals(sessionId.value, viewModel.uiState.value.stopDecisionSessionId)
        assertEquals(RecordingStatus.COMPLETED, container.controller.state.value.status)
        keepWarm.cancel()
    }

    @Test
    fun `keeping a session clears the decision and persists it so it never reappears`() = runBlocking {
        val sessionId = SessionId("session-keep")
        val viewModel = CaptureViewModel(container) {}
        val keepWarm = launch { viewModel.uiState.collect {} }

        completeSession(sessionId)
        awaitDecision(viewModel, present = true)

        viewModel.keepStoppedSession(sessionId.value)
        awaitDecision(viewModel, present = false)

        assertNull(viewModel.uiState.value.stopDecisionSessionId)
        assertEquals(sessionId.value, container.handledStopDecisionSessionId())
        keepWarm.cancel()
    }

    @Test
    fun `deleting a completed session clears the decision and removes its local evidence`() = runBlocking {
        val sessionId = SessionId("session-delete")
        container.database.sessions().register(
            SessionInfoEntity(
                sessionId = sessionId.value,
                memoryDay = "2026-10-11",
                zoneId = "America/Chicago",
                startedAtEpochMs = 1,
                customTitle = null,
            ),
        )
        val viewModel = CaptureViewModel(container) {}
        val keepWarm = launch { viewModel.uiState.collect {} }

        completeSession(sessionId)
        awaitDecision(viewModel, present = true)

        viewModel.deleteStoppedSession(sessionId.value)
        awaitDecision(viewModel, present = false)

        assertNull(viewModel.uiState.value.stopDecisionSessionId)
        assertEquals(sessionId.value, container.handledStopDecisionSessionId())
        // The process-wide registry may consume COMPLETED only after deletion finishes.
        // Reproduce that ordering explicitly: it must not recreate the removed session.
        container.sessions.register(sessionId.value)
        assertEquals(
            emptyList<String>(),
            container.database.sessions().observeSince("2026-10-01").first()
                .map { it.sessionId }
                .filter { it == sessionId.value },
        )
        keepWarm.cancel()
    }
}
