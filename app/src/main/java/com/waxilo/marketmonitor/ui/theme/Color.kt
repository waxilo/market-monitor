package com.waxilo.marketmonitor.ui.theme

import androidx.compose.ui.graphics.Color

/** 币价涨跌：涨绿跌红（默认，可在设置反转）。 */
val UpGreen = Color(0xFF16C784)
val DownRed = Color(0xFFEA3949)

/** 主题色：以币安黄作为强调色的暗色版本，避免大面积高饱和。 */
val AccentYellow = Color(0xFFF0B90B)
val AccentYellowDark = Color(0xFFC99A06)

/** 黄色上承载的主文字用深棕而非白字：黄底白字对比不足，深字才达 WCAG 对比。 */
val OnAccentDark = Color(0xFF1B1200)
val OnAccentLight = Color(0xFF2A1D00)

/** ---------- 亮色（surface 层级自浅到深：背景细化，内容浮层逐级提亮） ---------- */
val BackgroundLight = Color(0xFFF5F6F8)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF0F1F4)
val SurfaceContainerLight = Color(0xFFEBECF0)
val SurfaceContainerHighLight = Color(0xFFE2E4E9)
val SurfaceContainerHighestLight = Color(0xFFD9DCE2)

/** ---------- 暗色（交易所惯例：近黑的深底 + 细微提升的表面） ---------- */
val BackgroundDark = Color(0xFF0B0D12)
val SurfaceDark = Color(0xFF13161D)
val SurfaceContainerLowDark = Color(0xFF10141B)
val SurfaceContainerDark = Color(0xFF1A1E26)
val SurfaceContainerHighDark = Color(0xFF20242E)
val SurfaceContainerHighestDark = Color(0xFF262B36)
val OnSurfaceDark = Color(0xFFE6E8EE)
val OnSurfaceVariantDark = Color(0xFF939AA6)

val TextSecondaryLight = Color(0xFF6B7280)
val TextSecondaryDark = Color(0xFF9AA3B2)