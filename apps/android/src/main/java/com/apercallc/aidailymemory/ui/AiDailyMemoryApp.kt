package com.apercallc.aidailymemory.ui

import android.util.Log
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.recording.CaptureSignal
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.Transition
import java.util.UUID

private const val TAG = "AiDailyMemoryApp"

@Composable
fun AiDailyMemoryApp() {
    // Phase 0 shell: state lives here only until the recorder service owns it
    // (TODO.md "Wire state machine to Android service").
    var state by remember { mutableStateOf(RecordingState.Initial) }
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
    val status = state.status

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "AI Daily Memory",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "Capture what matters. Keep the evidence. Recall it later.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CaptureHealthCard(status = status)

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when (status) {
                        RecordingStatus.IDLE,
                        RecordingStatus.COMPLETED,
                        -> {
                            // Simulated: real IDs are UUIDv7 from the session repository (ADR-0006).
                            val sessionId = SessionId(UUID.randomUUID().toString())
                            dispatch(
                                RecordingCommand.Start(sessionId),
                                CaptureSignal.Started(sessionId),
                            )
                        }

                        RecordingStatus.RECORDING -> dispatch(RecordingCommand.Pause)
                        RecordingStatus.PAUSED -> dispatch(RecordingCommand.Resume)
                        else -> Unit
                    }
                },
            ) {
                Text(
                    when (status) {
                        RecordingStatus.RECORDING -> "Pause"
                        RecordingStatus.PAUSED -> "Resume"
                        else -> "Start My Day"
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = status == RecordingStatus.RECORDING,
                    onClick = { /* Phase 0: persist a highlight marker. */ },
                ) {
                    Text("Highlight")
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = status == RecordingStatus.RECORDING,
                    onClick = { /* Phase 0: add Quick Note flow. */ },
                ) {
                    Text("Quick Note")
                }
            }

            if (status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        state.sessionId?.let { sessionId ->
                            dispatch(RecordingCommand.Stop, CaptureSignal.Stopped(sessionId))
                        }
                    },
                ) {
                    Text("End Session")
                }
            }

            Text(
                text = "Phase 0 scaffold — controls currently exercise the deterministic state machine only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptureHealthCard(status: RecordingStatus) {
    val isActive = status == RecordingStatus.RECORDING

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = status.name.lowercase().replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(
                text = if (isActive) {
                    "Capture health checks will appear here once the recorder service is wired."
                } else {
                    "Ready to start a local recording session."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
