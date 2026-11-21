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
 * 为什么值得单测（2026-11-22 真机两条反馈）：
 * - **直线无法点击选中** —— 命中按两个锚点之间的线段判，而屏幕上画的是延长到边界的线；
 *   点在延长段上算落空，一次都选不中。
 * - **把手抓不着** —— 锚点可能被横向平移推到屏幕外，把手画在那里就等于没有。
 *
 * 裁剪结果同时喂给绘制、命中、把手，所以这里的断言就是「看得见 = 点得着 = 抓得住」。
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
    fun `可见段两端就是把手位置——锚点全在屏幕外也抓得着`() {
        // 两个锚点都在画布外（画完又平移走了）：只有可见段端点判得到把手
        val span = clip(Offset(-2000f, -600f), Offset(4000f, 1400f))!!
        val (s, e) = span
        assertEquals(TrendGrip.ANCHOR_A, onTrendGrip(s, s, e, 20f))
        assertEquals(TrendGrip.ANCHOR_B, onTrendGrip(e, s, e, 20f))
        val mid = Offset((s.x + e.x) / 2f, (s.y + e.y) / 2f)
        assertEquals(TrendGrip.MID, onTrendGrip(mid, s, e, 20f))
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
