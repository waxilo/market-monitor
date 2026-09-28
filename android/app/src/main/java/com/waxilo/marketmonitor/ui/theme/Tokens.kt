package com.waxilo.marketmonitor.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计常量：间距、圆角、动效曲线。
 *
 * 为什么集中在一处：旧版把 `12.dp` / `16.dp` / `10.dp` 散落在 60 多处 padding 里，
 * 改一次节奏要翻遍所有页面且必然漏掉几处，页面之间的留白因此永远对不齐。
 * 这里给出一个 4 的倍数阶梯，页面只允许从这里取值。
 */
object Spacing {
    /** 4dp 阶梯：Xxs=4, Xs=8, Sm=12, Md=16, Lg=24, Xl=32, Xxl=48 */
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
    val Xxl = 48.dp

    /** 页面左右统一留白。整个 App 只有这一个横向边距值。 */
    val Gutter = 20.dp

    /** 列表行的最小高度：保证 48dp 触控区，同时不至于松散。 */
    val RowMinHeight = 64.dp
}

object Radius {
    /** 极小圆角，用在 pill / 标签。 */
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    /** 全圆角，用在状态胶囊。 */
    val Full = 100.dp

    val xsShape = RoundedCornerShape(Xs)
    val smShape = RoundedCornerShape(Sm)
    val mdShape = RoundedCornerShape(Md)
    val lgShape = RoundedCornerShape(Lg)
    val fullShape = RoundedCornerShape(Full)
}

/**
 * 动效曲线与时长。
 * 全部走 anticipation-free 的减速曲线：行情刷新很频繁，任何回弹/过冲在 500ms 一次的
 * 刷新节奏下都会变成持续的视觉噪音。
 */
object Motion {
    /** 标准减速：微交互（颜色、透明度）。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 强调减速：位移较大的转场。 */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 快：悬停/按压反馈，要立即响应。 */
    const val FastMs = 120

    /** 常规：滑块移动、颜色过渡。 */
    const val BaseMs = 220

    /** 慢：页面转场、闪现淡出。 */
    const val SlowMs = 420
}

/**
 * 编辑部风排版用的字号。
 * 与 Material 的 type scale 并存：M3 那套（titleLarge/bodyMedium…）语义偏 UI 控件，
 * 而本设计系统需要「刊头 / 引题 / 正文 / 尾注」这类印刷概念，单独放一组更直白。
 */
object FontSize {
    /** 页面大标题（刊头）。有了底栏导航后不必再靠巨型刊头撑层级，收小给内容让位。 */
    val Display = 22.sp

    /** 详情页主价格：全 App 最大的数字。 */
    val HeroPrice = 42.sp

    /** 区块小标题（大写 + 字距拉开）。 */
    val Overline = 11.sp

    /** 列表主文字。 */
    val RowTitle = 16.sp

    /** 数字正文。 */
    val Numeric = 15.sp
}
