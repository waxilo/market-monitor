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
        lineHeight = 28.sp,
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

/**
 * 与标题**同一行**的动作文字（「刷新」「关闭」这类）。
 *
 * 为什么不能直接用 [Typography.labelLarge]（15sp）：**中文比拉丁大写字母看起来大**。
 * 标的名是 `BTC/USDT` 这种全大写拉丁串，它的视觉高度只有 cap-height（≈0.72em，
 * 16sp 的字只占 11.4sp）；而「刷新」是汉字，按字面框撑满（≈0.86em，15sp 就占 12.9sp）。
 * 于是同一行里右侧那个 15sp 的中文按钮反而比 16sp 的标题还高出一成 —— 标题被压了一头，
 * 看着就是「刷新这个字怎么比标的名还大」（2026-10-24 用户截图指出）。
 *
 * 所以同行动作统一**比标题低一档**（16sp 标题配 14sp 动作）：14 × 0.86 ≈ 12.0sp，
 * 与 16 × 0.72 ≈ 11.4sp 基本齐平，层级关系也回到「标题是主、操作是从」。
 * 不再往 13sp 压：那会明显小于全 App 其它按钮（labelLarge 15sp），同一颗按钮换个
 * 位置就换了字号，比差这一两像素更难解释。
 *
 * lineHeight 取 21sp（= titleMedium 的行高）：两个盒子等高、都在各自行盒里居中，
 * 居中后基线才不会差出半像素。
 */
val InlineActionStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 21.sp,
)
