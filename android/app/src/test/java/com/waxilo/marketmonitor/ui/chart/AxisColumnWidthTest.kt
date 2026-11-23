package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右侧价格列的宽度夹取（2026-11-23-2 用户「价格签应该放在价格轴上，现在价格轴太窄了」）。
 *
 * 背景：列宽曾经写死 58dp（280dpi 上 101.5px），而 `60,701.58` 这种 5 位整数 + 2 位小数的
 * 实心价签（8sp 文字 + 左右各 3.5dp 内边距）要 120px 以上 ⇒ 价签左溢压到 K 线上。
 * 现在宽度按这一列里最宽的文本量出来，本类只测最后那一步夹取。
 *
 * 只断言**值**：这四个边界（价签最宽 / 刻度最宽 / 触下限 / 触上限）决定了「会不会裁字」。
 */
class AxisColumnWidthTest {

    private val badgePadH = 3.5f
    private val gutter = 2f
    private val minWidth = 58f
    private val maxWidth = 672f // 1920px 画布 × 35%

    @Test
    fun `价签比刻度宽时按价签算 且左右两侧都算内边距`() {
        val w = axisColumnWidthPx(
            widestTagTextPx = 100f,
            widestAxisTextPx = 60f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = minWidth,
            maxWidthPx = maxWidth,
        )
        // 100 + 3.5*2 + 2 —— 内边距是**左右各一份**，只加一份就会少 3.5px，价签右边被切
        assertEquals(109f, w, 1e-4f)
    }

    @Test
    fun `刻度比价签宽时按刻度算`() {
        val w = axisColumnWidthPx(
            widestTagTextPx = 40f,
            widestAxisTextPx = 120f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = minWidth,
            maxWidthPx = maxWidth,
        )
        assertEquals(122f, w, 1e-4f)
    }

    @Test
    fun `内容很窄时回落到下限 且下限不再额外加留白`() {
        val w = axisColumnWidthPx(
            widestTagTextPx = 10f,
            widestAxisTextPx = 10f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = minWidth,
            maxWidthPx = maxWidth,
        )
        // 内容 10+7+2 = 19 < 58 ⇒ 58。正确顺序是「先加留白再夹」，夹完再加就会变成 60
        assertEquals(minWidth, w, 1e-4f)
    }

    @Test
    fun `内容超宽时被上限截断`() {
        val w = axisColumnWidthPx(
            widestTagTextPx = 400f,
            widestAxisTextPx = 60f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = minWidth,
            maxWidthPx = 300f,
        )
        assertEquals(300f, w, 1e-4f)
    }

    @Test
    fun `没有任何样本时回落到下限而不是 gutter`() {
        val w = axisColumnWidthPx(
            widestTagTextPx = 0f,
            widestAxisTextPx = 0f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = minWidth,
            maxWidthPx = maxWidth,
        )
        assertEquals(minWidth, w, 1e-4f)
    }

    @Test
    fun `上限小于下限时不抛异常 取下限`() {
        // 极窄画布 + 大字体：max = 画布宽 * 35% 可能比下限还小。
        // coerceIn 的 min > max 会抛 IllegalArgumentException，必须在这里挡住。
        val w = axisColumnWidthPx(
            widestTagTextPx = 200f,
            widestAxisTextPx = 0f,
            badgePadH = badgePadH,
            gutter = gutter,
            minWidthPx = 100f,
            maxWidthPx = 50f,
        )
        assertEquals(100f, w, 1e-4f)
    }

    @Test
    fun `BTC 价位下算出来的宽度够放下价签 而死值 58dp 不够`() {
        // 280dpi ⇒ 58dp = 101.5px；'60,701.58' 在 8sp 下约 108px 宽
        val legacyWidthPx = 58f * 1.75f
        val tagTextPx = 108f
        val padPx = badgePadH * 1.75f
        val gutterPx = gutter * 1.75f
        val w = axisColumnWidthPx(
            widestTagTextPx = tagTextPx,
            widestAxisTextPx = 70f,
            badgePadH = padPx,
            gutter = gutterPx,
            minWidthPx = legacyWidthPx,
            maxWidthPx = 1920f * 0.35f,
        )
        assertTrue(
            "死值 101.5px 装不下「108px 文字 + 左右各 6.1px 内边距」",
            legacyWidthPx < tagTextPx + padPx * 2f,
        )
        assertTrue("量出来的宽度必须能装下整枚价签（含留白）：$w", w >= tagTextPx + padPx * 2f + gutterPx)
    }

    @Test
    fun `列宽恰好等于价签宽加贴边留白 没有多余空白`() {
        // 2026-11-23 用户「价格轴宽度和价格签一样宽」：下限降为 0，列宽由价签自身决定。
        // 280dpi 口径：10sp 字高 17.5px，等宽数字约 9.6px/字符；价签内边距左右各 5.5dp。
        val density = 1.75f
        val badgePadHpx = 5.5f * density
        val tagTextPx = 8 * 9.6f // '60,701.58' 八个字符
        val gutterPx = 2f * density
        val badgeWidthPx = tagTextPx + badgePadHpx * 2f
        val w = axisColumnWidthPx(
            widestTagTextPx = tagTextPx,
            widestAxisTextPx = 0f, // 刻度不参与量宽（AXIS_TICKS_VISIBLE = false）
            badgePadH = badgePadHpx,
            gutter = gutterPx,
            minWidthPx = 0f,
            maxWidthPx = 1920f * 0.35f,
        )
        assertEquals("列宽 = 牌宽 + 贴边留白", badgeWidthPx + gutterPx, w, 1e-3f)
        // 价签右对齐（x = 画布右缘 - gutter - 牌宽）⇒ 它左缘正好落在列左边界上，留白为 0
        val badgeLeftInColumn = w - gutterPx - badgeWidthPx
        assertEquals(0f, badgeLeftInColumn, 1e-3f)
    }

    @Test
    fun `刻度文本更长时才会把列撑宽 说明关闭刻度后不会多算`() {
        // 反向口径：刻度**参与**量宽（AXIS_TICKS_VISIBLE = true）时列必须跟着刻度走，
        // 否则打开刻度就会把最长那条裁掉。这条守住「开关两种状态都对」。
        val w = axisColumnWidthPx(
            widestTagTextPx = 70f,
            widestAxisTextPx = 130f,
            badgePadH = 9.6f,
            gutter = 3.5f,
            minWidthPx = 0f,
            maxWidthPx = 1920f * 0.35f,
        )
        assertEquals(133.5f, w, 1e-3f)
    }
}
