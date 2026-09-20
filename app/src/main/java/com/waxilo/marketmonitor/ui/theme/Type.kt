package com.waxilo.marketmonitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版：编辑部风。
 *
 * 思路是「两种字体各管一件事」，而不是全篇一个无衬线体加粗减细：
 * - **Mono（等宽）承载所有数字与标签**——价格、涨跌、成交量、区块小标题。
 *   行情数字一屏几十个且每 500ms 变一次，等宽 + tabular 保证列宽不抖；
 *   小标题也走等宽，是因为等宽体的机械感恰好就是「终端 / 数据手册」的语感。
 * - **Sans（系统无衬线）承载所有自然语言**——币种名、页面标题、按钮、说明。
 *
 * 刻意不引入自定义字体文件：APK 体积敏感，且中文自定义字体动辄数 MB，
 * 收益远不抵代价。靠字重、字距、大小写这三种免费手段做出层次。
 */

private val Mono = FontFamily.Monospace
private val Sans = FontFamily.Default

/** 数字基线：所有价格/数量文本都从这里派生，保证 tabular 特性一致。 */
private val Numeric = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
)

val AppTypography = Typography(
    // ── 页面级 ──────────────────────────────────────────────
    /** 页面大标题。字重压到 Bold 而非 Black，避免中文标题糊成一团。 */
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = FontSize.Display,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = FontSize.RowTitle,
        lineHeight = 21.sp,
        letterSpacing = (-0.2).sp,
    ),

    // ── 正文 ────────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),

    // ── 标签 ────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    /** 等宽小标签：区块小标题、单位、状态。 */
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    /** 等宽尾注：免责声明、页脚统计。 */
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

/** 行情列表与统计里的数字正文。 */
val PriceTextStyle = Numeric.copy(
    fontSize = FontSize.Numeric,
    fontWeight = FontWeight.SemiBold,
)

/** 详情页主价格：全 App 最强视觉锚点，负字距让大数字更紧凑。 */
val HeroPriceStyle = Numeric.copy(
    fontSize = FontSize.HeroPrice,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-1.5).sp,
)

/** 次级数字（统计值、详情页 24h 高低）。 */
val MetricValueStyle = Numeric.copy(
    fontSize = 17.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.4).sp,
)

/** 区块小标题：等宽 + 大写 + 拉开字距，编辑部风的签名手法。 */
val SectionOverlineStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = FontSize.Overline,
    lineHeight = 14.sp,
    letterSpacing = 1.4.sp,
)
