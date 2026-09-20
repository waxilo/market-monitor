package com.waxilo.marketmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = AccentYellowDark,
    onPrimary = OnAccentLight,
    primaryContainer = AccentYellow,
    onPrimaryContainer = OnAccentLight,
    secondary = AccentYellowDark,
    onSecondary = OnAccentLight,
    secondaryContainer = AccentYellow.copy(alpha = 0.18f),
    onSecondaryContainer = OnAccentLight,
    error = DownRed,
    background = BackgroundLight,
    onBackground = TextSecondaryLight,
    surface = SurfaceLight,
    onSurface = TextSecondaryLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFF7F7F87),
    outlineVariant = Color(0xFFD9DCE2),
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceBright = SurfaceLight,
    surfaceDim = BackgroundLight,
)

private val DarkScheme = darkColorScheme(
    primary = AccentYellow,
    onPrimary = OnAccentDark,
    primaryContainer = AccentYellow.copy(alpha = 0.16f),
    onPrimaryContainer = AccentYellow,
    secondary = UpGreen,
    onSecondary = OnAccentDark,
    secondaryContainer = UpGreen.copy(alpha = 0.18f),
    onSecondaryContainer = UpGreen,
    error = DownRed,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = SurfaceContainerHighestDark,
    outlineVariant = SurfaceContainerHighDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceContainerLowest = BackgroundDark,
    surfaceBright = SurfaceContainerHighDark,
    surfaceDim = BackgroundDark,
)

@Composable
fun MarketMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}