package com.waxilo.marketmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = AccentYellowDark,
    secondary = UpGreen,
    background = BackgroundLight,
    surface = SurfaceLight,
)

private val DarkScheme = darkColorScheme(
    primary = AccentYellow,
    secondary = UpGreen,
    background = Color(0xFF0E1116),
    surface = SurfaceDark,
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
