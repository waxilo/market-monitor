package com.waxilo.marketmonitor.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 配色：极简杂志风。
 *
 * 设计立场——行情数据本身就是高信息密度的东西，界面再花就会喧宾夺主。
 * 所以这里刻意反着来：**不用任何品牌强调色做大面积填充**，只用墨黑/纸白两级
 * 中性色撑结构，把饱和度全部留给「涨跌」这一个信号。
 *
 * 三档灰阶各司其职，不再像旧版那样铺 6 层 surfaceContainer：
 * - [Ink]  / [InkDark]    —— 正文与结构线（近黑，带一点冷调避免死黑）
 * - [Muted]                —— 次级信息（标签、单位、说明）
 * - [Hairline]             —— 唯一的描边层级（1px 细线代替卡片阴影）
 */

/** ---------- 涨跌语义色：全 App 唯一允许出现的高饱和色 ---------- */
/** 涨绿跌红（默认，可在设置反转）。注意与国内股市相反：加密货币沿用国际惯例。 */
val UpGreen = Color(0xFF00A96E)
val DownRed = Color(0xFFE5384B)

/**
 * 涨跌的「弱化版本」，用于文字与背景：
 * 大面积铺 [UpGreen] 会刺眼，列表里的涨跌幅文字用这一档更耐看。
 */
val UpGreenSoft = Color(0xFF0E9F6E)
val DownRedSoft = Color(0xFFDC2F45)

/** 涨跌闪现底色：价格变化那瞬间铺一层极淡的色再淡出（约 6% 透明度）。 */
val FlashUp = Color(0x0F00A96E)
val FlashDown = Color(0x0FE5384B)

/** ---------- 亮色（纸白） ---------- */
/** 背景不是纯白：纯白在 OLED/高亮度下发刺，带一点暖调的纸白更像印刷品。 */
val PaperLight = Color(0xFFFAFAF8)
val SurfaceLight = Color(0xFFFFFFFF)
/** 唯一的浅底层级，用于「内嵌区块」与输入框，不再铺 6 层。 */
val WashLight = Color(0xFFF2F2EF)
val WashStrongLight = Color(0xFFE8E8E4)
val Ink = Color(0xFF14161A)
val Muted = Color(0xFF6E7480)
val HairlineLight = Color(0xFFE4E4E0)
val HairlineStrongLight = Color(0xFFD3D3CE)

/** ---------- 暗色（墨黑） ---------- */
val PaperDark = Color(0xFF0C0D0F)
val SurfaceDarkColor = Color(0xFF141619)
val WashDark = Color(0xFF1B1E22)
val WashStrongDark = Color(0xFF23272C)
val InkLight = Color(0xFFECEDEF)
val MutedDark = Color(0xFF8C939E)
val HairlineDark = Color(0xFF2A2E34)
val HairlineStrongDark = Color(0xFF3A3F47)

/** 黄色仅作为「已选中」的极小面积信号，保留品牌识别度但不做主角。 */
val AccentYellow = Color(0xFFF0B90B)
val OnAccentDark = Color(0xFF1B1200)

/**
 * 指标线色板：按 [com.waxilo.marketmonitor.ui.chart.LineRole] 的线位顺序取用。
 *
 * 取经典统计图表色板的中间调：饱和度统一压在中低档，与涨跌红绿拉开——
 * 高饱和线压在 K 线上既抢蜡烛的注意力又显廉价，这套灰调彩在墨黑/纸白
 * 两个主题下都耐看。八个色位刚好覆盖最满配置（MA 五期 + BOLL 三线），两两可辨。
 */
val ChartLineColors = listOf(
    Color(0xFF5C8AC6), // PRIMARY    钢蓝（MA5）
    Color(0xFFF09A3E), // SECONDARY  琥珀（MA10）
    Color(0xFFE8C547), // TERTIARY   软黄（MA20）
    Color(0xFF9D6FC0), // ACCENT     藕紫（MA30）
    Color(0xFF5FBDB2), // QUATERNARY 青瓷（MA60）
    Color(0xFFC9825E), // QUINARY    陶土（BOLL 中轨）
    Color(0xFFD98CA6), // SENARY     藕粉（BOLL 上轨）
    Color(0xFF9AA3AE), // OCTONARY   冷灰（BOLL 下轨）
)

/**
 * 主题实际取用的语义色集合。
 * 放进 [androidx.compose.runtime.CompositionLocal] 而不是塞进 Material3 的
 * colorScheme：M3 的角色命名（surfaceContainerHighest…）是为 Material 组件设计的，
 * 拿来表达「发丝线 / 弱化文字」这类本设计系统的概念会很别扭。
 */
@Immutable
data class MarketColors(
    val up: Color,
    val down: Color,
    val upSoft: Color,
    val downSoft: Color,
    val flashUp: Color,
    val flashDown: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val paper: Color,
    val surface: Color,
    val wash: Color,
    val washStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val flat: Color,
) {
    /** 涨跌取色：null / NaN 一律走中性 [flat]，与 [com.waxilo.marketmonitor.ui.common.ChangeText] 的判定保持一致。 */
    fun forChange(changePercent: Double?): Color = when {
        changePercent == null || changePercent.isNaN() -> flat
        changePercent > 0 -> upSoft
        changePercent < 0 -> downSoft
        else -> flat
    }
}

internal val LightMarketColors = MarketColors(
    up = UpGreen,
    down = DownRed,
    upSoft = UpGreenSoft,
    downSoft = DownRedSoft,
    flashUp = FlashUp,
    flashDown = FlashDown,
    ink = Ink,
    muted = Muted,
    hairline = HairlineLight,
    hairlineStrong = HairlineStrongLight,
    paper = PaperLight,
    surface = SurfaceLight,
    wash = WashLight,
    washStrong = WashStrongLight,
    accent = AccentYellow,
    onAccent = OnAccentDark,
    flat = Muted,
)

internal val DarkMarketColors = MarketColors(
    up = UpGreen,
    down = DownRed,
    upSoft = UpGreen,
    downSoft = DownRed,
    flashUp = FlashUp,
    flashDown = FlashDown,
    ink = InkLight,
    muted = MutedDark,
    hairline = HairlineDark,
    hairlineStrong = HairlineStrongDark,
    paper = PaperDark,
    surface = SurfaceDarkColor,
    wash = WashDark,
    washStrong = WashStrongDark,
    accent = AccentYellow,
    onAccent = OnAccentDark,
    flat = MutedDark,
)

/** 供 [MarketMonitorTheme] 提供、[MarketTheme] 消费。 */
internal val LocalMarketColors = staticCompositionLocalOf { LightMarketColors }

/** Material3 色板：只映射本设计系统真正会用到的角色，其余留空。 */
internal fun lightMaterialScheme(c: MarketColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    background = c.paper,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.wash,
    onSurfaceVariant = c.muted,
    outline = c.hairlineStrong,
    outlineVariant = c.hairline,
    error = DownRed,
)

internal fun darkMaterialScheme(c: MarketColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    background = c.paper,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.wash,
    onSurfaceVariant = c.muted,
    outline = c.hairlineStrong,
    outlineVariant = c.hairline,
    error = DownRed,
)
