package com.apercallc.aidailymemory.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.recording.design.TypeFamily
import com.apercallc.aidailymemory.recording.design.TypeScale

/**
 * Spacing scale (4 dp grid). Screens use these names instead of raw dp so density changes
 * stay consistent across the app.
 */
@Immutable
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    /** Minimum touch target for any interactive element (Android accessibility). */
    val minTouchTarget: Dp = 48.dp,
    /** Corner radius for every `Card()` in the app — one tier, not two (see AGENTS.md design
     *  standards: semantic tokens over ad hoc values). Previously only "status" cards used this
     *  and plain content cards fell back to M3's ~12dp default by omission; that was accidental
     *  drift, not a deliberate hero/content distinction, so every card now shares this radius. */
    val cardCorner: Dp = 24.dp,
    /** Diameter of the decorative status dot next to a headline. */
    val statusDot: Dp = 12.dp,
    /** Width of the leading time column in a transcript line list. */
    val timeColumnWidth: Dp = 56.dp,
    /** Border width for a card's "accent" emphasis stroke (selected/highlighted state). */
    val accentBorder: Dp = 1.5.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Turns the platform-agnostic [TypeScale] decision into an actual Compose [FontFamily]. */
internal fun TypeFamily.toFontFamily(): FontFamily = when (this) {
    TypeFamily.SERIF -> FontFamily.Serif
    TypeFamily.MONOSPACE -> FontFamily.Monospace
}

/**
 * Type scale. The Material 3 baseline sizes (all in sp, so they follow the user's font scale)
 * with heavier weights on the roles this app uses for state, so status reads at a glance.
 * Which role is serif vs. monospace comes from [TypeScale], shared with the watch.
 */
val AppTypography: Typography = Typography().run {
    val display = TypeScale.display.toFontFamily()
    val headline = TypeScale.headline.toFontFamily()
    val title = TypeScale.title.toFontFamily()
    val label = TypeScale.label.toFontFamily()
    copy(
        displayLarge = displayLarge.copy(fontFamily = display),
        displayMedium = displayMedium.copy(fontFamily = display),
        displaySmall = displaySmall.copy(fontFamily = display),
        headlineLarge = headlineLarge.copy(fontFamily = headline),
        headlineMedium = headlineMedium.copy(fontFamily = headline, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = headline),
        titleLarge = titleLarge.copy(fontFamily = title, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = title, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = title),
        labelLarge = labelLarge.copy(fontFamily = label, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = label),
        labelSmall = labelSmall.copy(fontFamily = label),
    )
}
