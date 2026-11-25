package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按下落在一条直线上算什么」的定性 —— [trendTouchAt]。
 *
 * 口径经过两轮用户反馈才定下来（2026-11-25）：
 * ① 「不管什么线，都应该要先选中才能编辑，现在没选中就可以拖动」——
 *    未选中时线身与端点都只产生「选中」，本函数一律给 [TrendTouch.NONE]；
 * ② 「调角度的两个点并不是我画线时的落点」—— 端点用**真实锚点**
 *    ([TrendLinePixels.anchorA]/[TrendLinePixels.anchorB])，不是可见段端点。
 *
 * 所以断言的必须是**具体档位**（BODY / ANCHOR_A / ANCHOR_B / NONE），不是「非空」。
 */
class TrendTouchTest {

    private val gripHit = 20f
    private val band = 10f

    // 可见段（裁到主图矩形的画线）：贯穿整个绘图区
    private val p1 = Offset(0f, 200f)
    private val p2 = Offset(1440f, 600f)
    private val mid = Offset(720f, 400f)

    // 用户画线时的两个真实落点：在绘图区中部，与可见段端点完全不同
    private val anchorA = Offset(400f, 311.1f)
    private val anchorB = Offset(1000f, 477.8f)

    private val line = TrendLinePixels(
        id = 7L,
        p1 = p1,
        p2 = p2,
        anchorA = anchorA,
        anchorB = anchorB,
    )

    private fun touch(x: Float, y: Float, selected: Boolean = false) = trendTouchAt(
        line = line,
        selected = selected,
        point = Offset(x, y),
        gripHitPx = gripHit,
        bodyHitPx = band,
    )

    /* ── 核心口径：未选中只产生「选中」，任何档位都不许动线 ── */

    @Test
    fun `未选中——线身中段只当点击（NONE）`() {
        assertEquals(TrendTouch.NONE, touch(mid.x, mid.y))
        assertEquals(TrendTouch.NONE, touch(300f, 283.3f))
        assertEquals(TrendTouch.NONE, touch(1100f, 505.6f))
    }

    @Test
    fun `未选中——按在真实锚点上也只当点击，不凭空改角度`() {
        // 把手只在选中态画出来；未选中时认端点等于「按在什么都没有的地方改了角度」。
        assertEquals(TrendTouch.NONE, touch(anchorA.x, anchorA.y))
        assertEquals(TrendTouch.NONE, touch(anchorB.x, anchorB.y))
    }

    /* ── 把手用真实锚点：不是可见段端点 ── */

    @Test
    fun `已选中——两枚把手落在画线时的落点上`() {
        assertEquals(TrendTouch.ANCHOR_A, touch(anchorA.x, anchorA.y, selected = true))
        assertEquals(TrendTouch.ANCHOR_B, touch(anchorB.x, anchorB.y, selected = true))
        // 端点 12dp 内：把手圈（20dp）里、线身带（10dp）外的斜向落点也算把手
        assertEquals(TrendTouch.ANCHOR_A, touch(anchorA.x + 12f, anchorA.y + 12f, selected = true))
    }

    @Test
    fun `已选中——可见段两端不再是把手`() {
        // 这两处是直线撞到绘图区边框的位置：用户报「调角度的两个点并不是我画线时的落点」。
        // 离真实锚点几百像素，判成把手就是又一次「按空白改角度」。
        assertEquals(TrendTouch.BODY, touch(p1.x, p1.y, selected = true))
        assertEquals(TrendTouch.BODY, touch(p2.x, p2.y, selected = true))
    }

    @Test
    fun `已选中——两端之外的线身仍算整根平移`() {
        assertEquals(TrendTouch.BODY, touch(mid.x, mid.y, selected = true))
    }

    /* ── 距离闸门：带外一律还给画布 ── */

    @Test
    fun `线身带外——按不到就是 NONE（不许抢走画布手势）`() {
        // 单位法向 ≈ (-0.268, 0.964)：沿它偏 11px 就出带（与 TrendGripTest 同口径）
        assertEquals(TrendTouch.NONE, touch(mid.x - 0.268f * 11f, mid.y + 0.964f * 11f, selected = true))
        assertEquals(TrendTouch.NONE, touch(anchorA.x - 60f, anchorA.y - 30f, selected = true))
    }

    @Test
    fun `没命中任何直线——NONE`() {
        assertEquals(
            TrendTouch.NONE,
            trendTouchAt(null, selected = false, point = mid, gripHitPx = gripHit, bodyHitPx = band),
        )
        assertEquals(
            TrendTouch.NONE,
            trendTouchAt(null, selected = true, point = mid, gripHitPx = gripHit, bodyHitPx = band),
        )
    }

    @Test
    fun `非法输入一律 NONE`() {
        assertEquals(TrendTouch.NONE, touch(Float.NaN, mid.y))
        assertEquals(TrendTouch.NONE, touch(mid.x, Float.NaN, selected = true))
        assertEquals(
            TrendTouch.NONE,
            trendTouchAt(line, true, mid, gripHitPx = gripHit, bodyHitPx = 0f),
        )
        assertEquals(
            TrendTouch.NONE,
            TrendLinePixels(1L, p1, Offset(Float.NaN, 400f)).let {
                trendTouchAt(it, true, mid, gripHit, band)
            },
        )
    }
}