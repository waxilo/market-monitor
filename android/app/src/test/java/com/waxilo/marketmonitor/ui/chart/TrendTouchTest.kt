package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按下落在一条直线上算什么」的定性 —— [trendTouchAt]。
 *
 * 这条判据 2026-11-25 改过一次口径，两个方向都是用户报出来的故障：
 * ① 只认已选中那条（2026-11-23 ～ 11-24）：用户「先点一下选中、第二下才拖得动」，
 *    观感就是「直线拖不动，只有两端把手能摸」；
 * ② 反过来把端点也算成线身：按在端点上会变成整根平移，改角度就永远做不出来。
 * 所以断言的必须是**具体档位**（BODY / ANCHOR_A / ANCHOR_B / NONE），不是「非空」。
 */
class TrendTouchTest {

    private val gripHit = 20f
    private val band = 10f
    private val p1 = Offset(200f, 300f)
    private val p2 = Offset(600f, 500f)
    private val mid = Offset(400f, 400f)
    private val line = TrendLinePixels(id = 7L, p1 = p1, p2 = p2)

    private fun touch(x: Float, y: Float, selected: Boolean = false) = trendTouchAt(
        line = line,
        selected = selected,
        point = Offset(x, y),
        gripHitPx = gripHit,
        bodyHitPx = band,
    )

    /* ── 核心改动：线身对「未选中」的直线同样可抓 ── */

    @Test
    fun `未选中——按在线身中段直接算抓到（BODY）`() {
        assertEquals(TrendTouch.BODY, touch(mid.x, mid.y))
        assertEquals(TrendTouch.BODY, touch(300f, 350f))
        assertEquals(TrendTouch.BODY, touch(500f, 450f))
    }

    @Test
    fun `未选中——按在端点上也走线身，不凭空造出一枚看不见的把手`() {
        // 把手只在选中态画出来；未选中时认端点等于「按在什么都没有的地方改了角度」。
        assertEquals(TrendTouch.BODY, touch(p1.x, p1.y))
        assertEquals(TrendTouch.BODY, touch(p2.x, p2.y))
    }

    /* ── 端点优先级：已选中时按在端点上仍然是改角度 ── */

    @Test
    fun `已选中——端点仍是改角度（端点优先于线身）`() {
        assertEquals(TrendTouch.ANCHOR_A, touch(p1.x, p1.y, selected = true))
        assertEquals(TrendTouch.ANCHOR_B, touch(p2.x, p2.y, selected = true))
        // 端点 12dp 内：把手圈（20dp）里、线身带（10dp）外的斜向落点也算把手
        assertEquals(TrendTouch.ANCHOR_A, touch(p1.x + 12f, p1.y + 12f, selected = true))
    }

    @Test
    fun `已选中——两端之外的线身仍算整根平移`() {
        assertEquals(TrendTouch.BODY, touch(mid.x, mid.y, selected = true))
    }

    /* ── 距离闸门：带外一律还给画布 ── */

    @Test
    fun `线身带外——按不到就是 NONE（不许抢走画布手势）`() {
        // 单位法向 ≈ (-0.447, 0.894)：沿它偏 11px 就出带（与 TrendGripTest 同口径）
        assertEquals(TrendTouch.NONE, touch(mid.x - 0.447f * 11f, mid.y + 0.894f * 11f))
        assertEquals(TrendTouch.NONE, touch(p1.x - 60f, p1.y - 30f, selected = true))
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
            trendTouchAt(line, false, mid, gripHitPx = gripHit, bodyHitPx = 0f),
        )
        assertEquals(
            TrendTouch.NONE,
            TrendLinePixels(1L, p1, Offset(Float.NaN, 400f)).let {
                trendTouchAt(it, false, mid, gripHit, band)
            },
        )
    }
}
