package com.apercallc.aidailymemory.ui

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.presentation.Headline
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

private const val TAG = "AiDailyMemoryApp"

/**
 * Phase 0 shell. State lives here only until the recorder service owns it
 * (TODO.md "Wire state machine to Android service"). Engine signals are simulated.
 */
@Composable
fun AiDailyMemoryApp() {
    var state by remember { mutableStateOf(RecordingState.Initial) }
    val ids = remember { UuidV7Generator() }

    fun dispatch(vararg events: RecordingEvent) {
        for (event in events) {
            val result = RecordingStateMachine.reduce(state, event)
            if (result is Transition.Rejected) {
                // No silent failure: the service will surface this in capture health.
                Log.w(TAG, "Rejected ${result.event} in ${result.state.status}")
                return
            }
            state = result.state
        }
    }

    CaptureScreen(
        model = CaptureStatusPresenter.present(state, CaptureHealth.Unknown),
        onPrimaryAction = { action ->
            when (action) {
                PrimaryAction.START -> {
                    val sessionId = SessionId(ids.next().toString())
                    dispatch(RecordingCommand.Start(sessionId), CaptureSignal.Started(sessionId))
                }
                PrimaryAction.PAUSE -> dispatch(RecordingCommand.Pause)
                PrimaryAction.RESUME -> dispatch(RecordingCommand.Resume)
                PrimaryAction.NONE -> Unit
            }
        },
        onStop = {
            state.sessionId?.let { sessionId ->
                dispatch(RecordingCommand.Stop, CaptureSignal.Stopped(sessionId))
            }
        },
    )
}

/** Stateless: renders [model] and reports user intents upward. */
@Composable
fun CaptureScreen(
    model: CaptureStatusModel,
    onPrimaryAction: (PrimaryAction) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                // Scrolls instead of clipping at 200% font scale.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )

            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CaptureStatusCard(model = model)

            if (model.primaryAction != PrimaryAction.NONE) {
                PrimaryActionButton(model = model, onClick = onPrimaryAction)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = model.canMark,
                    onClick = { /* Phase 0: persist a highlight marker. */ },
                ) {
                    Text(stringResource(R.string.action_highlight))
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = model.canMark,
                    onClick = { /* Phase 0: Quick Note model is an open decision. */ },
                ) {
                    Text(stringResource(R.string.action_quick_note))
                }
            }

            if (model.canStop) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onStop) {
                    Text(stringResource(R.string.action_end_session))
                }
            }

            Text(
                text = stringResource(R.string.scaffold_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(model: CaptureStatusModel, onClick: (PrimaryAction) -> Unit) {
    val status = AiDailyMemoryTheme.statusColors
    val colors = if (model.primaryAction == PrimaryAction.PAUSE) {
        // Pause is the privacy control: give it the recording color so it is unmistakable.
        ButtonDefaults.buttonColors(
            containerColor = status.recording,
            contentColor = status.onRecording,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
        onClick = { onClick(model.primaryAction) },
    ) {
        Text(
            stringResource(
                when (model.primaryAction) {
                    PrimaryAction.PAUSE -> R.string.action_pause
                    PrimaryAction.RESUME -> R.string.action_resume
                    PrimaryAction.START, PrimaryAction.NONE -> R.string.action_start
                },
            ),
        )
    }
}

@Composable
private fun CaptureStatusCard(model: CaptureStatusModel) {
    val status = AiDailyMemoryTheme.statusColors

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                // Read as one unit, and announce changes: recording state must never be
                // silent for TalkBack users.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Decorative: the headline text carries the meaning, so color is never the
                // only signal.
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = status.container(model.tone), shape = CircleShape),
                )
                Text(
                    text = stringResource(headlineText(model.headline)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(
                text = stringResource(detailText(model)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun headlineText(headline: Headline): Int = when (headline) {
    Headline.READY -> R.string.status_ready
    Headline.START_FAILED -> R.string.status_start_failed
    Headline.STARTING -> R.string.status_starting
    Headline.RECORDING -> R.string.status_recording
    Headline.NOT_CAPTURING -> R.string.status_not_capturing
    Headline.PAUSED -> R.string.status_paused
    Headline.INTERRUPTED -> R.string.status_interrupted
    Headline.RECOVERING -> R.string.status_recovering
    Headline.STOPPING -> R.string.status_stopping
    Headline.COMPLETED -> R.string.status_completed
}

@StringRes
private fun detailText(model: CaptureStatusModel): Int = model.problem?.let(::problemText)
    ?: when (model.headline) {
        Headline.READY, Headline.COMPLETED, Headline.START_FAILED -> R.string.detail_idle
        else -> R.string.detail_active
    }

@StringRes
private fun problemText(problem: CaptureProblem): Int = when (problem) {
    CaptureProblem.PERMISSION_DENIED -> R.string.problem_permission_denied
    CaptureProblem.MICROPHONE_UNAVAILABLE -> R.string.problem_microphone_unavailable
    CaptureProblem.PHONE_CALL -> R.string.problem_phone_call
    CaptureProblem.FOREGROUND_START_NOT_ALLOWED -> R.string.problem_foreground_start_not_allowed
    CaptureProblem.STORAGE_LOW -> R.string.problem_storage_low
    CaptureProblem.PROCESS_RESTARTED -> R.string.problem_process_restarted
    CaptureProblem.DEVICE_REBOOTED -> R.string.problem_device_rebooted
    CaptureProblem.UNKNOWN -> R.string.problem_unknown
}
