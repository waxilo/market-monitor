package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 直线三枚把手：命中判定。
 *
 * 背景（2026-11-22 用户「app 的纵坐标缩放，画的直线会漂移」）：直线向左右沿无限延长，
 * 按线段判命中等于给画布斜切一条带子 —— 已选中时纵向调量程、起手压上去就把线拖走了。
 * 现在编辑入口收到把手上（两端改角度、中点整体平移），[onTrendGrip] 是那道闸门。
 *
 * 端点在先也一并钉住：两点挨得近时中点必然也在命中圈里，而「改角度」才是主操作。
 */
class TrendGripTest {

    private val hit = 20f
    private val p1 = Offset(200f, 300f)
    private val p2 = Offset(600f, 500f)
    private val mid = Offset(400f, 400f)

    private fun grip(x: Float, y: Float) = onTrendGrip(Offset(x, y), p1, p2, hit)

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
    fun `中点——判整体平移`() {
        assertEquals(TrendGrip.MID, grip(mid.x, mid.y))
        assertEquals(TrendGrip.MID, grip(mid.x + hit * 0.6f, mid.y))
    }

    @Test
    fun `线段上离三点都远的地方——不算抓到（这一下留给平移画布）`() {
        // 线从 (200,300) 到 (600,500)：1/4 与 3/4 处到最近的把手都有 100px 以上
        assertNull(grip(300f, 350f))
        assertNull(grip(500f, 450f))
    }

    @Test
    fun `短线段时端点优先于中点`() {
        // 两点只隔 10px，中点必然也在命中圈里：改角度比整体挪更常用，端点先吃
        val a = Offset(200f, 300f)
        val b = Offset(210f, 300f)
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(Offset(205f, 302f), a, b, hit))
        assertEquals(TrendGrip.ANCHOR_B, onTrendGrip(Offset(209f, 302f), a, b, hit))
    }

    @Test
    fun `两点重合——退化成端点，不会判成中点`() {
        val same = Offset(300f, 300f)
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(Offset(305f, 300f), same, same, hit))
    }

    @Test
    fun `非法输入一律不命中`() {
        assertNull(onTrendGrip(Offset(Float.NaN, 300f), p1, p2, hit))
        assertNull(onTrendGrip(Offset(200f, Float.NaN), p1, p2, hit))
        assertNull(onTrendGrip(p1, Offset(Float.NaN, 300f), p2, hit))
        assertNull(onTrendGrip(p1, p1, p2, 0f))
        assertNull(onTrendGrip(p1, p1, p2, Float.NaN))
    }
}
