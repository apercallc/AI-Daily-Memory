package com.apercallc.aidailymemory.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.apercallc.aidailymemory.recording.design.BrandTokens

/**
 * Material roles adapted from the shared paper, ink and red brand tokens.
 */
internal val BrandDarkColors = darkColorScheme(
    primary = Color(BrandTokens.Dark.RED),
    onPrimary = Color(BrandTokens.Dark.ON_RED),
    primaryContainer = Color(BrandTokens.Dark.RED_CONTAINER),
    onPrimaryContainer = Color(BrandTokens.Dark.ON_RED_CONTAINER),
    secondary = Color(BrandTokens.Dark.SLATE),
    onSecondary = Color(BrandTokens.Dark.ON_SLATE),
    secondaryContainer = Color(BrandTokens.Dark.SLATE_CONTAINER),
    onSecondaryContainer = Color(BrandTokens.Dark.ON_SLATE_CONTAINER),
    tertiary = Color(BrandTokens.Dark.OK),
    onTertiary = Color(BrandTokens.Dark.ON_SLATE),
    tertiaryContainer = Color(BrandTokens.Dark.SLATE_CONTAINER),
    onTertiaryContainer = Color(BrandTokens.Dark.ON_SLATE_CONTAINER),
    background = Color(BrandTokens.Dark.PAPER),
    onBackground = Color(BrandTokens.Dark.INK),
    surface = Color(BrandTokens.Dark.PAPER),
    onSurface = Color(BrandTokens.Dark.INK),
    surfaceVariant = Color(BrandTokens.Dark.PAPER_DEEP),
    onSurfaceVariant = Color(BrandTokens.Dark.INK_SOFT),
    surfaceContainerLowest = Color(BrandTokens.Dark.SURFACE_LOWEST),
    surfaceContainerLow = Color(BrandTokens.Dark.SURFACE_LOW),
    surfaceContainer = Color(BrandTokens.Dark.CARD),
    surfaceContainerHigh = Color(BrandTokens.Dark.SURFACE_HIGH),
    surfaceContainerHighest = Color(BrandTokens.Dark.SURFACE_HIGHEST),
    outline = Color(BrandTokens.Dark.OUTLINE),
    outlineVariant = Color(BrandTokens.Dark.RULE),
    error = Color(BrandTokens.Dark.ERROR),
    onError = Color(BrandTokens.Dark.ON_ERROR),
    errorContainer = Color(BrandTokens.Dark.ERROR_CONTAINER),
    onErrorContainer = Color(BrandTokens.Dark.ON_ERROR_CONTAINER),
)

internal val BrandLightColors = lightColorScheme(
    primary = Color(BrandTokens.Light.RED),
    onPrimary = Color(BrandTokens.Light.WHITE),
    primaryContainer = Color(BrandTokens.Light.RED_CONTAINER),
    onPrimaryContainer = Color(BrandTokens.Light.ON_RED_CONTAINER),
    secondary = Color(BrandTokens.Light.SLATE),
    onSecondary = Color(BrandTokens.Light.WHITE),
    secondaryContainer = Color(BrandTokens.Light.SLATE_CONTAINER),
    onSecondaryContainer = Color(BrandTokens.Light.ON_SLATE_CONTAINER),
    tertiary = Color(BrandTokens.Light.OK),
    onTertiary = Color(BrandTokens.Light.WHITE),
    tertiaryContainer = Color(BrandTokens.Light.SLATE_CONTAINER),
    onTertiaryContainer = Color(BrandTokens.Light.ON_SLATE_CONTAINER),
    background = Color(BrandTokens.Light.PAPER),
    onBackground = Color(BrandTokens.Light.INK),
    surface = Color(BrandTokens.Light.PAPER),
    onSurface = Color(BrandTokens.Light.INK),
    surfaceVariant = Color(BrandTokens.Light.PAPER_DEEP),
    onSurfaceVariant = Color(BrandTokens.Light.INK_SOFT),
    surfaceContainerLowest = Color(BrandTokens.Light.SURFACE_LOWEST),
    surfaceContainerLow = Color(BrandTokens.Light.SURFACE_LOW),
    surfaceContainer = Color(BrandTokens.Light.CARD),
    surfaceContainerHigh = Color(BrandTokens.Light.SURFACE_HIGH),
    surfaceContainerHighest = Color(BrandTokens.Light.SURFACE_HIGHEST),
    outline = Color(BrandTokens.Light.OUTLINE),
    outlineVariant = Color(BrandTokens.Light.RULE),
    error = Color(BrandTokens.Light.ERROR),
    onError = Color(BrandTokens.Light.WHITE),
    errorContainer = Color(BrandTokens.Light.ERROR_CONTAINER),
    onErrorContainer = Color(BrandTokens.Light.ON_ERROR_CONTAINER),
)
