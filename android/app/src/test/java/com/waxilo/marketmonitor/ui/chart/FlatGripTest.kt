package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水平类线两端把手：命中判定 + 它如何改变「这一场单指干什么」。
 *
 * 背景（2026-11-21 用户「上下移动图表，直线穿过的位置变了」）：水平线横贯整个绘图区，
 * 若「按住线身」就算抓到线，上下平移图表时起手压到线就会把线拖走。
 * 现在把「拖线」的入口收到两端那枚把手上，[onFlatGrip] 就是那道闸门 ——
 * 判宽的代价是拖图表又拖走线，判严的代价是线再也拖不动，两个方向都得钉住。
 */
class FlatGripTest {

    private val width = 1000f
    private val inset = 10f
    private val hit = 18f
    private val y = 500f

    private fun grip(x: Float, py: Float) = onFlatGrip(Offset(x, py), y, width, inset, hit)

    @Test
    fun `两端圆点正中——算抓到`() {
        assertTrue(grip(inset, y))
        assertTrue(grip(width - inset, y))
    }

    @Test
    fun `圆点边缘一个像素内——算抓到`() {
        assertTrue(grip(inset + hit - 0.5f, y))
        assertTrue(grip(width - inset - hit + 0.5f, y))
    }

    @Test
    fun `线身中段——不算抓到（这一下要留给平移画布）`() {
        assertFalse(grip(width / 2f, y))
        assertFalse(grip(width * 0.3f, y))
        assertFalse(grip(width * 0.7f, y))
    }

    @Test
    fun `纵向偏出命中半径——不算抓到`() {
        assertFalse(grip(inset, y + hit + 1f))
        assertFalse(grip(width - inset, y - hit - 1f))
    }

    @Test
    fun `斜着落在圆外——不算抓到`() {
        // 半径是欧氏距离：横向、纵向都没超，但斜向已经出圈
        val d = hit * 0.75f
        assertFalse(grip(inset + d, y + d))
        assertTrue(grip(inset + hit * 0.5f, y + hit * 0.5f))
    }

    @Test
    fun `非法输入一律不算抓到`() {
        assertFalse(onFlatGrip(Offset(Float.NaN, y), y, width, inset, hit))
        assertFalse(onFlatGrip(Offset(inset, Float.NaN), y, width, inset, hit))
        assertFalse(onFlatGrip(Offset(inset, y), Float.NaN, width, inset, hit))
        assertFalse(onFlatGrip(Offset(inset, y), y, 0f, inset, hit))
        assertFalse(onFlatGrip(Offset(inset, y), y, width, inset, 0f))
    }

    @Test
    fun `窄屏时两枚把手各自独立，不会把整条线都算成把手`() {
        // 绘图区只有 60px 宽：左右把手各覆盖 28px，中间仍留得下一条缝
        assertTrue(onFlatGrip(Offset(inset, y), y, 60f, inset, hit))
        assertTrue(onFlatGrip(Offset(50f, y), y, 60f, inset, hit))
        assertFalse(onFlatGrip(Offset(30f, y), y, 60f, inset, hit))
    }

    /**
     * 闸门的**下游后果**：抓到把手才 Drag；按在线身降级成 Select（本场只当点击，
     * 手指一动就是平移画布，线纹丝不动）。这是本次修复真正落地的语义。
     */
    @Test
    fun `已选中的水平线——抓把手才拖，按线身只当点击`() {
        val line = LineRef.Guide(42L)
        assertEquals(LineTouch.Drag, lineTouch(line, line, dragAllowed = true))
        assertEquals(LineTouch.Select, lineTouch(line, line, dragAllowed = false))
    }

    @Test
    fun `直线不受把手限制——dragAllowed 默认仍为真`() {
        val trend = LineRef.Guide(7L)
        assertEquals(LineTouch.Drag, lineTouch(trend, trend))
        assertEquals(LineTouch.None, lineTouch(null, trend))
    }
}
