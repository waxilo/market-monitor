package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直线命中判定 —— 两种入口：两端把手（[onTrendGrip]，改角度）与线身（[onTrendBody]，整体平移）。
 *
 * 背景两段：
 * ① 2026-11-22 用户「app 的纵坐标缩放，画的直线会漂移」：直线向左右无限延长，
 *    按线身判命中等于给画布斜切一条带子 —— 已选中时纵向调量程、起手压上去就把线拖走了。
 *    于是编辑入口收成把手（当时是三枚：两端 + 中点）。
 * ② 2026-11-23 用户「直线中间的点去掉，点其他两点之外就能平移」：三点退成两点 ——
 *    端点仍改角度，**线身**接手整体平移，那枚正落在屏幕中央的中点圆点整档删除。
 *
 * 两条闸门都钉在这里：判宽了「想缩量程却把线拖走」，判严了「怎么按都拖不动」，
 * 两种故障方向相反、用户都很难描述，所以断言的必须是值。
 */
class TrendGripTest {

    private val hit = 20f
    private val band = 10f
    private val p1 = Offset(200f, 300f)
    private val p2 = Offset(600f, 500f)
    private val mid = Offset(400f, 400f)

    private fun grip(x: Float, y: Float) = onTrendGrip(Offset(x, y), p1, p2, hit)

    private fun body(x: Float, y: Float, tolerance: Float = band) =
        onTrendBody(Offset(x, y), p1, p2, tolerance)

    /* ── 两端把手：改角度 ── */

    @Test
    fun `两端锚点正中——分别判 A 与 B`() {
        assertEquals(TrendGrip.ANCHOR_A, grip(p1.x, p1.y))
        assertEquals(TrendGrip.ANCHOR_B, grip(p2.x, p2.y))
    }

    @Test
    fun `锚点边缘一个像素内——仍算抓到`() {
        assertEquals(TrendGrip.ANCHOR_A, grip(p1.x + hit - 0.5f, p1.y))
        assertEquals(TrendGrip.ANCHOR_B, grip(p2.x, p2.y - hit + 0.5f))
    }

    @Test
    fun `短线段时两端各自判自己——不给中点留位置`() {
        // 两点只隔 10px：以前中点必然也在命中圈里，靠「端点优先」兜住；现在中点已不是把手，
        // 按在两端之间只会落到线身上（整体平移），不会再出现「按中间改了角度」。
        val a = Offset(200f, 300f)
        val b = Offset(210f, 300f)
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(Offset(205f, 302f), a, b, hit))
        assertEquals(TrendGrip.ANCHOR_B, onTrendGrip(Offset(209f, 302f), a, b, hit))
    }

    @Test
    fun `两点重合——退化成端点，不会判成 null`() {
        val same = Offset(300f, 300f)
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(Offset(305f, 300f), same, same, hit))
    }

    @Test
    fun `中点不再是把手——线身正中不给 TrendGrip`() {
        // 2026-11-23 的核心断言：那枚中点圆点删了，判命中也不能再认它，
        // 否则「看不见的点」会吃掉一次本该是平移画布的起手。
        assertNull(grip(mid.x, mid.y))
        assertNull(grip(mid.x + hit * 0.6f, mid.y))
        assertNull(grip(mid.x, mid.y - hit * 0.5f))
    }

    @Test
    fun `离两端都远的地方——不判把手（这一下交给线身或画布）`() {
        // 线从 (200,300) 到 (600,500)：1/4 与 3/4 处到最近的把手都有 100px 以上
        assertNull(grip(300f, 350f))
        assertNull(grip(500f, 450f))
    }

    @Test
    fun `把手非法输入一律不命中`() {
        assertNull(onTrendGrip(Offset(Float.NaN, 300f), p1, p2, hit))
        assertNull(onTrendGrip(Offset(200f, Float.NaN), p1, p2, hit))
        assertNull(onTrendGrip(p1, Offset(Float.NaN, 300f), p2, hit))
        assertNull(onTrendGrip(p1, p1, p2, 0f))
        assertNull(onTrendGrip(p1, p1, p2, Float.NaN))
    }

    /* ── 线身：整体平移（2026-11-23 起的中段唯一入口） ── */

    @Test
    fun `线身中段——落在可见段上就算抓到`() {
        assertTrue(body(mid.x, mid.y))
        assertTrue(body(300f, 350f))
        assertTrue(body(500f, 450f))
    }

    @Test
    fun `线身命中带按垂直距离算——带宽内抓得住，band 外放开`() {
        // 单位法向 ≈ (-0.447, 0.894)：沿它偏移 9px / 11px 就是「带内 / 带外」
        assertTrue(body(mid.x - 0.447f * 9f, mid.y + 0.894f * 9f))
        assertFalse(body(mid.x - 0.447f * 11f, mid.y + 0.894f * 11f))
        // 刚好压在带宽上（允许相等）
        assertTrue(body(mid.x - 0.447f * band, mid.y + 0.894f * band))
    }

    @Test
    fun `可见段之外的延长线——不算抓到`() {
        // 屏幕上画出来的只有裁到绘图区的那一段（p1..p2）；段外按「到端点距离」算，
        // 不能因为「这条无限直线还经过那里」就把落点认成线身 —— 那里根本没有线。
        assertFalse(body(p2.x + 100f, p2.y + 50f))
        assertFalse(body(p1.x - 100f, p1.y - 50f))
    }

    @Test
    fun `线身非法输入一律不抓`() {
        assertFalse(onTrendBody(Offset(Float.NaN, 300f), p1, p2, band))
        assertFalse(onTrendBody(Offset(200f, Float.NaN), p1, p2, band))
        assertFalse(onTrendBody(p1, Offset(Float.NaN, 300f), p2, band))
        assertFalse(onTrendBody(p1, p1, Offset(200f, Float.NaN), band))
        assertFalse(onTrendBody(p1, p1, p2, 0f))
        assertFalse(onTrendBody(p1, p1, p2, Float.NaN))
    }

    @Test
    fun `两点重合时线身退化成端点圆——band 内仍抓得到`() {
        val same = Offset(300f, 300f)
        assertTrue(onTrendBody(Offset(305f, 300f), same, same, band))
        assertFalse(onTrendBody(Offset(315f, 300f), same, same, band))
    }
}
