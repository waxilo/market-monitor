package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右轴价格标的落位规则（[placeAxisTags]）。
 *
 * 要守住的一条：**标签永远贴着它所指的那条线** —— 落位只把中心裁进主图（贴边不被裁半截），
 * 不做任何避让。太近就原地叠住，谁露出来交给绘制顺序（优先级高的压在上面）。
 */
class AxisPriceTagLayoutTest {

    private val height = 30f

    private fun layout(vararg desired: Float, top: Float = 0f, bottom: Float = 300f) =
        placeAxisTags(desired.toList(), heightPx = height, topPx = top, bottomPx = bottom)

    @Test
    fun `都在主图内时原样返回`() {
        // 现价 100、划线 105：离得再近也不挪，叠住由绘制顺序决定谁在上面
        assertEquals(listOf(100f, 105f), layout(100f, 105f))
    }

    @Test
    fun `贴边时中心裁进半个标签高`() {
        // 落点在底沿之外 ⇒ 中心夹到 maxCenter（300 - 15），标签不会被裁掉半截
        assertEquals(285f, layout(299f)[0])
        // 上沿同理
        assertEquals(15f, layout(1f)[0])
    }

    @Test
    fun `多枚同价线全部原位重叠`() {
        val placed = layout(100f, 100f, 100f, 100f, 100f)
        assertEquals(List(5) { 100f }, placed)
    }

    @Test
    fun `主图比标签还矮时原样返回`() {
        // 高度不足一个标签：裁无可裁，宁可叠住也不乱挪（绘制顺序兜底）
        assertEquals(listOf(10f, 20f), layout(10f, 20f, top = 40f, bottom = 20f))
    }

    @Test
    fun `空清单与退化尺寸都不炸`() {
        assertEquals(emptyList<Float>(), placeAxisTags(emptyList(), 30f, 0f, 300f))
        // 高度为 0 / 主图退化：裁无可裁，原样返回，别把 coerceIn 用非法区间炸了
        assertEquals(listOf(10f, 20f), placeAxisTags(listOf(10f, 20f), 0f, 0f, 300f))
        assertEquals(listOf(10f, 20f), placeAxisTags(listOf(10f, 20f), 30f, 100f, 100f))
    }
}
