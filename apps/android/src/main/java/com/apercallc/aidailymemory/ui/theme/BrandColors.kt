package com.apercallc.aidailymemory.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand palette: indigo "night" surfaces with a soft violet accent. Dark surfaces are tinted
 * and layered (lowest → highest) so cards separate from the background instead of reading as
 * one flat slab. Text pairs keep at least 4.5:1 contrast.
 */
internal val BrandDarkColors = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF1F1B6B),
    primaryContainer = Color(0xFF373585),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFFC8C4E4),
    onSecondary = Color(0xFF302E4A),
    secondaryContainer = Color(0xFF3F3D5B),
    onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = Color(0xFFFFB0C8),
    onTertiary = Color(0xFF5A1136),
    tertiaryContainer = Color(0xFF762B4C),
    onTertiaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF0F0F1A),
    onBackground = Color(0xFFE6E3F2),
    surface = Color(0xFF0F0F1A),
    onSurface = Color(0xFFE6E3F2),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC9C5D3),
    surfaceContainerLowest = Color(0xFF0A0A14),
    surfaceContainerLow = Color(0xFF16162A),
    surfaceContainer = Color(0xFF1B1B31),
    surfaceContainerHigh = Color(0xFF25253D),
    surfaceContainerHighest = Color(0xFF303049),
    outline = Color(0xFF928F9D),
    outlineVariant = Color(0xFF47464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

internal val BrandLightColors = lightColorScheme(
    primary = Color(0xFF4F4CB0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF0D0864),
    secondary = Color(0xFF5E5C78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFFF),
    onSecondaryContainer = Color(0xFF1A1832),
    tertiary = Color(0xFF8F4467),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3A0722),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE5E0EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerHigh = Color(0xFFEAE6F1),
    surfaceContainerHighest = Color(0xFFE5E1EB),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC9C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)
