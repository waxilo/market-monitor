package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 右轴价格标的让位规则（[staggerAxisTags]）。
 *
 * 要守住的一条：**最新价永远在最前面、永远不被挤动**，划线（告警线 / 水平画线）
 * 在撞上它时自己挪开。这里的输入顺序就是优先级顺序（高 → 低），
 * 所以断言的是「第 0 枚不动、后面的自己走」。
 */
class AxisPriceTagLayoutTest {

    private val height = 30f
    private val step = 32f

    private fun layout(vararg desired: Float, top: Float = 0f, bottom: Float = 300f) =
        staggerAxisTags(desired.toList(), heightPx = height, topPx = top, bottomPx = bottom, stepPx = step)

    @Test
    fun `互不重叠时原样返回`() {
        // 现价 100、划线 220：中间隔着一个标签高以上，谁也不用动
        assertEquals(listOf(100f, 220f), layout(100f, 220f))
    }

    @Test
    fun `划线压在最新价上时让位一档`() {
        val placed = layout(100f, 100f)
        // 第 0 枚（最新价）保持原位
        assertEquals(100f, placed[0])
        // 第 1 枚（划线）被推开恰好一档，且不再与最新价相交
        assertEquals(132f, placed[1])
        assertTrue(abs(placed[1] - placed[0]) >= height)
    }

    @Test
    fun `下方没有空间时改向上让位`() {
        // 现价贴着主图底沿（中心最多到 100），下面无位可退 ⇒ 只能往上
        val placed = layout(100f, 100f, bottom = 115f)
        assertEquals(100f, placed[0])
        assertEquals(68f, placed[1])
    }

    @Test
    fun `刻度贴近边缘时先裁进主图再排`() {
        // 落点在底沿之外 ⇒ 中心先夹到 maxCenter（300 - 15），不吃到副图上
        val placed = layout(299f, top = 0f, bottom = 300f)
        assertEquals(285f, placed[0])
    }

    @Test
    fun `多条同价线依次散开且一条不少`() {
        val placed = layout(100f, 100f, 100f, 100f, 100f)
        assertEquals(5, placed.size)
        assertEquals(100f, placed[0])
        // 相邻两枚都不再相交
        placed.zipWithNext().forEach { (a, b) -> assertTrue(abs(a - b) >= height) }
    }

    @Test
    fun `实在放不下时不丢条目`() {
        // 主图只有 60px 高，塞两枚 30px 的标：挤不下也该都留着（交给绘制顺序压住）
        val placed = staggerAxisTags(
            desired = listOf(30f, 30f),
            heightPx = 30f,
            topPx = 0f,
            bottomPx = 60f,
            stepPx = 32f,
        )
        assertEquals(2, placed.size)
    }

    @Test
    fun `空清单与退化尺寸都不炸`() {
        assertEquals(emptyList<Float>(), staggerAxisTags(emptyList(), 30f, 0f, 300f, 32f))
        // 高度不足一个标签：原样返回，宁可重叠也不乱挪
        assertEquals(listOf(10f, 20f), staggerAxisTags(listOf(10f, 20f), 30f, 40f, 20f, 32f))
    }
}
