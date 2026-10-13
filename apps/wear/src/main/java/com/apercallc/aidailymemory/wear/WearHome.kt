package com.apercallc.aidailymemory.wear

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.apercallc.aidailymemory.recording.health.HealthLevel
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.wear.WatchControls
import java.text.DateFormat
import java.util.Date

/** Same red as the phone's recording dot, so one glance means the same thing on both. */
private val RecordingRed = Color(0xFFFF3B30)

private const val PULSE_MS = 900
private const val PULSE_LOW_ALPHA = 0.3f

@Composable
internal fun WearHome(
    state: WearControlState,
    onCommand: (WatchCommand) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot = state.snapshot
    val enabled = state.phoneConnected && snapshot != null && state.pendingCommandId == null
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // AppScaffold hosts the time text; ScreenScaffold ties it and the scroll indicator to the
    // list, and the list scrolls with the rotary input by default.
    AppScaffold {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { StatusHeader(state, spec) }
                notices(state, spec)

                if (state.pendingCommandId != null && state.error != null) {
                    item { WearButton(R.string.action_retry, Emphasis.PRIMARY, true, onRetry, spec) }
                    // A way out: without it an unconfirmed command blocks every other control.
                    item { WearButton(R.string.action_dismiss, Emphasis.TONAL, true, onDismiss, spec) }
                }

                if (WearControlReducer.needsPhoneCheck(state)) {
                    if (state.phoneConnected) {
                        // Connected, but the phone's acknowledged state has not arrived; a retry
                        // re-requests the snapshot instead of leaving the watch a dead end.
                        item { Notice(R.string.phone_controls_waiting, NoticeTone.INFO, spec) }
                    }
                    item { WearButton(R.string.action_check_phone, Emphasis.PRIMARY, true, onRefresh, spec) }
                } else {
                    // needsPhoneCheck false means the watch holds a phone-acknowledged snapshot.
                    val commands = WearPresentation.displayOrder(
                        WatchControls.availableCommands(checkNotNull(snapshot)),
                    )
                    items(commands.size) { index ->
                        val command = commands[index]
                        WearButton(
                            label = command.label,
                            emphasis = WearPresentation.emphasis(command),
                            enabled = enabled,
                            onClick = { onCommand(command) },
                            spec = spec,
                            hint = WearPresentation.hint(command),
                        )
                    }
                }
                item { Notice(R.string.phone_controller, NoticeTone.QUIET, spec) }
            }
        }
    }
}

/** Dot + headline (+ when the session began). Everything the user needs at a glance. */
@Composable
private fun TransformingLazyColumnItemScope.StatusHeader(state: WearControlState, spec: TransformationSpec) {
    val tone = WearPresentation.tone(state)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .padding(bottom = 4.dp),
    ) {
        StatusDot(tone)
        Text(
            text = stringResource(WearStrings.headline(state)),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        val startedAt = state.snapshot?.sessionStartedAt
        if (startedAt != null && WearPresentation.showsSessionStart(state)) {
            val since = remember(startedAt) {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startedAt))
            }
            Text(
                text = stringResource(R.string.session_since, since),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Decorative: the headline already says the same thing in words, so the dot is never the only
 * signal (and is hidden from TalkBack to avoid reading the state twice).
 */
@Composable
private fun StatusDot(tone: WearTone) {
    val color = when (tone) {
        WearTone.RECORDING -> RecordingRed
        WearTone.PAUSED -> MaterialTheme.colorScheme.tertiary
        WearTone.BUSY -> MaterialTheme.colorScheme.primary
        WearTone.ATTENTION -> MaterialTheme.colorScheme.error
        WearTone.IDLE -> MaterialTheme.colorScheme.secondary
        WearTone.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alpha = if (tone == WearTone.RECORDING) {
        rememberInfiniteTransition(label = "recording-dot").animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW_ALPHA,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "recording-dot-alpha",
        ).value
    } else {
        1f
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Row(
            modifier = Modifier
                .size(14.dp)
                .alpha(alpha)
                .background(color, CircleShape),
        ) {}
    }
}

private enum class NoticeTone { INFO, WARNING, ERROR, QUIET }

/** Everything the phone or watch has to say beyond the headline, each in one consistent style. */
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.notices(
    state: WearControlState,
    spec: TransformationSpec,
) {
    val snapshot = state.snapshot
    // Cached details are not current status when the phone is disconnected.
    if (state.phoneConnected) {
        snapshot?.problem?.let { problem ->
            item { Notice(WearStrings.problem(problem), NoticeTone.ERROR, spec) }
        }
        when (snapshot?.captureHealth) {
            HealthLevel.CRITICAL.name -> item { Notice(R.string.health_critical, NoticeTone.ERROR, spec) }
            HealthLevel.WARNING.name -> item { Notice(R.string.health_warning, NoticeTone.WARNING, spec) }
        }
    }
    if (state.pendingCommandId != null && state.error == null) {
        item { Notice(R.string.command_waiting, NoticeTone.INFO, spec) }
    }
    state.lastAcknowledgement?.let { ack ->
        item { Notice(WearStrings.acknowledgement(ack.outcome), NoticeTone.INFO, spec) }
    }
    state.error?.let { error ->
        item { Notice(WearStrings.error(error), NoticeTone.ERROR, spec) }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.Notice(
    @androidx.annotation.StringRes text: Int,
    tone: NoticeTone,
    spec: TransformationSpec,
) {
    val colors = MaterialTheme.colorScheme
    val color = when (tone) {
        NoticeTone.ERROR -> colors.error
        NoticeTone.WARNING -> colors.tertiary
        NoticeTone.INFO -> colors.onSurface
        NoticeTone.QUIET -> colors.onSurfaceVariant
    }
    Text(
        text = stringResource(text),
        color = color,
        textAlign = TextAlign.Center,
        style = if (tone == NoticeTone.QUIET) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { if (tone != NoticeTone.QUIET) liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.WearButton(
    @androidx.annotation.StringRes label: Int,
    emphasis: Emphasis,
    enabled: Boolean,
    onClick: () -> Unit,
    spec: TransformationSpec,
    @androidx.annotation.StringRes hint: Int? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = emphasis.colors(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = hint?.let { { Text(stringResource(it)) } },
    ) {
        Text(stringResource(label))
    }
}

@Composable
private fun Emphasis.colors(): ButtonColors = when (this) {
    Emphasis.PRIMARY -> ButtonDefaults.buttonColors()
    Emphasis.TONAL -> ButtonDefaults.filledTonalButtonColors()
    Emphasis.DANGER -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

private val WatchCommand.label: Int
    get() = when (this) {
        WatchCommand.Start -> R.string.action_start
        WatchCommand.Pause -> R.string.action_pause
        WatchCommand.Resume -> R.string.action_resume
        WatchCommand.Stop -> R.string.action_stop
        WatchCommand.Highlight -> R.string.action_highlight
        WatchCommand.QuickNote -> R.string.action_quick_note
    }
