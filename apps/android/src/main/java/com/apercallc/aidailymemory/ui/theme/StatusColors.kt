package com.apercallc.aidailymemory.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.apercallc.aidailymemory.recording.design.BrandTokens
import com.apercallc.aidailymemory.recording.presentation.StatusTone

/**
 * Semantic capture-state colors.
 *
 * These are fixed on purpose rather than derived from dynamic color: "recording" must look
 * the same on every wallpaper, because the recording state must never be hidden or ambiguous
 * (AGENTS.md rule 1). Pairs were picked for at least 4.5:1 contrast between container and content.
 */
@Immutable
data class StatusColors(
    val recording: Color,
    val onRecording: Color,
    val paused: Color,
    val onPaused: Color,
    val warning: Color,
    val onWarning: Color,
    val error: Color,
    val onError: Color,
    val neutral: Color,
    val onNeutral: Color,
    /** The vivid dot beside "Recording": the same saturated red in light and dark. */
    val recordingDot: Color = Color(BrandTokens.RECORDING_DOT),
) {
    fun container(tone: StatusTone): Color = when (tone) {
        StatusTone.RECORDING -> recording
        StatusTone.PAUSED -> paused
        StatusTone.WARNING -> warning
        StatusTone.ERROR -> error
        StatusTone.NEUTRAL -> neutral
    }

    fun content(tone: StatusTone): Color = when (tone) {
        StatusTone.RECORDING -> onRecording
        StatusTone.PAUSED -> onPaused
        StatusTone.WARNING -> onWarning
        StatusTone.ERROR -> onError
        StatusTone.NEUTRAL -> onNeutral
    }
}

internal val LightStatusColors = StatusColors(
    recording = Color(0xFFB3261E),
    onRecording = Color(0xFFFFFFFF),
    // Paused and warning are each a named brand token, not an independently hand-picked hex
    // value -- but deliberately two *different* tokens (same amber family, visibly distinct
    // shade), not one: collapsing them would make a paused session indistinguishable from an
    // actual warning (AGENTS.md: color semantics here are safety signals).
    paused = Color(BrandTokens.Light.PAUSED),
    onPaused = Color(0xFFFFFFFF),
    warning = Color(BrandTokens.Light.WARN),
    onWarning = Color(0xFFFFFFFF),
    error = Color(0xFF8C1D18),
    onError = Color(0xFFFFFFFF),
    neutral = Color(0xFF49454F),
    onNeutral = Color(0xFFFFFFFF),
)

internal val DarkStatusColors = StatusColors(
    recording = Color(0xFFFFB4AB),
    onRecording = Color(0xFF690005),
    paused = Color(BrandTokens.Dark.PAUSED),
    onPaused = Color(0xFF402D00),
    warning = Color(BrandTokens.Dark.WARN),
    onWarning = Color(0xFF4C2700),
    error = Color(0xFFFFDAD6),
    onError = Color(0xFF410002),
    neutral = Color(0xFFCAC4D0),
    onNeutral = Color(0xFF322F35),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
