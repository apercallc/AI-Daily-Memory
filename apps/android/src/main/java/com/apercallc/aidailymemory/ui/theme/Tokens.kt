package com.apercallc.aidailymemory.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    /** Corner radius of status cards. */
    val cardCorner: Dp = 24.dp,
    /** Diameter of the decorative status dot next to a headline. */
    val statusDot: Dp = 12.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/**
 * Type scale. The Material 3 baseline sizes (all in sp, so they follow the user's font scale)
 * with heavier weights on the roles this app uses for state, so status reads at a glance.
 */
val AppTypography: Typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}
