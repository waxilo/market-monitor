package com.waxilo.marketmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * 主题装配。
 *
 * 同时供给两套色板：Material3 的 colorScheme（给 M3 组件用，如 Switch/Slider 的默认着色）
 * 与自定义的 [MarketColors]（给本设计系统自己的组件用）。两者由同一个
 * [MarketColors] 实例派生，所以不会出现「自己的卡片是纸白、M3 的对话框是灰白」这种错位。
 */
@Composable
fun MarketMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val marketColors = if (darkTheme) DarkMarketColors else LightMarketColors
    val scheme = if (darkTheme) darkMaterialScheme(marketColors) else lightMaterialScheme(marketColors)

    CompositionLocalProvider(LocalMarketColors provides marketColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * 本设计系统的色彩入口：`MarketTheme.colors.up`。
 * 用对象包一层而不是裸暴露 CompositionLocal，是为了让调用点读起来像
 * `MarketTheme.colors.ink` 而不是 `LocalMarketColors.current.ink`——
 * 后者在页面里出现几十次会显著拖慢扫读。
 */
object MarketTheme {
    val colors: MarketColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMarketColors.current
}
