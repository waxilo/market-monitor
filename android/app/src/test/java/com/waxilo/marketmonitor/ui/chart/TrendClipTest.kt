package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直线「看得见的那一段」：把过两个锚点的**无限直线**裁到主图矩形的纯几何。
 *
 * 为什么值得单测（2026-11-22 真机反馈）：
 * - **直线无法点击选中** —— 命中按两个锚点之间的线段判，而屏幕上画的是延长到边界的线；
 *   点在延长段上算落空，一次都选不中。
 *
 * 裁剪结果喂给绘制段与线身命中带，所以这里的断言就是「看得见 = 点得着」。
 * 两端把手**不走裁剪**：它们落在用户画线时的真实锚点上（2026-11-25 用户
 * 「调角度的两个点并不是我画线时的落点」），锚点出视野时干脆不画。
 */
class TrendClipTest {

    // 主图矩形：1440 宽、y 在 100..700
    private val left = 0f
    private val top = 100f
    private val right = 1440f
    private val bottom = 700f

    private fun clip(
        a: Offset,
        b: Offset,
        l: Float = left,
        t: Float = top,
        r: Float = right,
        bt: Float = bottom,
    ) = clipLineToRect(a, b, l, t, r, bt)

    @Test
    fun `穿过的斜线——按底线裁，不受锚点远近影响`() {
        // 斜率 1、过 (100,200) ⇒ y = x + 100：左边界 x=0 处 y=100（正好压 top），
        // 底边 y=700 处 x=600（先撞底、不到右边界）
        val span = clip(Offset(100f, 200f), Offset(500f, 600f))
        assertNotNull(span)
        val (s, e) = span!!
        assertEquals(0f, s.x, 0.01f)
        assertEquals(100f, s.y, 0.01f)
        assertEquals(600f, e.x, 0.01f)
        assertEquals(700f, e.y, 0.01f)
    }

    @Test
    fun `两个锚点都在矩形内——仍向两侧延长到边界`() {
        // 直线 y = 0.4x + 180：左边界 (0,180)，底边 y=700 ⇒ x=1300
        val (s, e) = clip(Offset(300f, 300f), Offset(800f, 500f))!!
        assertEquals(0f, s.x, 0.01f)
        assertEquals(180f, s.y, 0.01f)
        assertEquals(1300f, e.x, 0.5f)
        assertEquals(700f, e.y, 0.5f)
    }

    @Test
    fun `整条都在矩形外——返回 null（不画、也不该命中）`() {
        // 水平线 y=20，矩形从 y=100 起
        assertNull(clip(Offset(100f, 20f), Offset(900f, 20f)))
        // 竖线 x=2000，矩形到 x=1440 为止
        assertNull(clip(Offset(2000f, 200f), Offset(2000f, 600f)))
    }

    @Test
    fun `近似竖直的线——裁到上下边，x 基本不动`() {
        // dx 只有 1px：旧实现 `(p2.y - p1.y) / dx` 在这种角度会放大误差，
        // 现在 Liang–Barsky 不除斜率，直接按参数裁
        val span = clip(Offset(700f, 50f), Offset(701f, 800f))
        assertNotNull(span)
        val (s, e) = span!!
        assertEquals(top, s.y, 0.5f)
        assertEquals(bottom, e.y, 0.5f)
        assertTrue("x 应守在 700 附近，实际 ${s.x}", s.x in 699f..702f)
        assertTrue("x 应守在 701 附近，实际 ${e.x}", e.x in 699f..702f)
    }

    @Test
    fun `水平线——裁成左右边界之间的一段`() {
        val (s, e) = clip(Offset(-500f, 400f), Offset(3000f, 400f))!!
        assertEquals(0f, s.x, 0.01f)
        assertEquals(1440f, e.x, 0.01f)
        assertEquals(400f, s.y, 0.01f)
        assertEquals(400f, e.y, 0.01f)
    }

    @Test
    fun `退化点与非法输入——一律 null`() {
        assertNull(clip(Offset(300f, 300f), Offset(300f, 300f)))
        assertNull(clip(Offset(Float.NaN, 300f), Offset(500f, 500f)))
        assertNull(clip(Offset(300f, 300f), Offset(Float.POSITIVE_INFINITY, 500f)))
        assertNull(clip(Offset(300f, 300f), Offset(500f, 500f), l = 100f, r = 100f))
    }

    @Test
    fun `可见段两端不再是把手——把手在真实锚点上`() {
        // 2026-11-25 用户「调角度的两个点并不是我画线时的落点」：可见段两端是直线撞到
        // 绘图区边框的位置，与落笔位置无关。把手必须画在、抓在原来的两个锚点上，
        // 这里的两个锚点都在画布外 —— 那就一枚把手都不画，而不是把边框交点伪装成把手。
        val a = Offset(-2000f, -600f)
        val b = Offset(4000f, 1400f)
        val span = clip(a, b)!!
        val (s, e) = span
        assertNull(onTrendGrip(s, a, b, 20f))
        assertNull(onTrendGrip(e, a, b, 20f))
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(a, a, b, 20f))
        assertEquals(TrendGrip.ANCHOR_B, onTrendGrip(b, a, b, 20f))
        // 线身仍按可见段判：点得着的是屏幕上画出来的那一截
        val mid = Offset((s.x + e.x) / 2f, (s.y + e.y) / 2f)
        assertTrue(onTrendBody(mid, s, e, 10f))
    }

    @Test
    fun `可见段上任一点都算命中——这就是「点线能选中」`() {
        val span = clip(Offset(200f, 250f), Offset(1200f, 650f))!!
        val slop = 24f
        for (i in 0..20) {
            val t = i / 20f
            val p = Offset(
                span.first.x + (span.second.x - span.first.x) * t,
                span.first.y + (span.second.y - span.first.y) * t,
            )
            assertTrue("第 $i 点应命中", distanceToSegment(p, span.first, span.second) <= slop)
        }
    }
}
