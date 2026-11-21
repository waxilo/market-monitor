package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 纵轴刻度的小数位与步长。
 *
 * 背景（2026-11-22 用户「移动端的纵坐标不显示间隔：8000，9000 这种」）：
 * 小数位原本跟着 tickSize 走，BTC 的 0.1 会把刻度写成「8000.0 / 8500.0」，
 * 而且条数固定 5 条 —— 主图被压矮时每条都被「太近就丢」的判据滤掉，右轴一条不剩。
 * 现在条数按高度自适应、小数位跟着步长走，两条都在这里钉住。
 */
class AxisDecimalsTest {

    @Test
    fun `步长整千整百——不写小数`() {
        assertEquals(0, decimalsForStep(1000.0))
        assertEquals(0, decimalsForStep(500.0))
        assertEquals(0, decimalsForStep(1.0))
        assertEquals(0, decimalsForStep(20000.0))
    }

    @Test
    fun `步长小于一——按量级补小数`() {
        assertEquals(1, decimalsForStep(0.5))
        assertEquals(2, decimalsForStep(0.05))
        assertEquals(4, decimalsForStep(0.0005))
        assertEquals(6, decimalsForStep(0.000002))
    }

    @Test
    fun `非法步长兜底成整数`() {
        assertEquals(0, decimalsForStep(0.0))
        assertEquals(0, decimalsForStep(-1.0))
        assertEquals(0, decimalsForStep(Double.NaN))
        assertEquals(0, decimalsForStep(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `8000 到 9000 的区间——刻度是整千整百的干净数`() {
        val range = ValueRange(8000.0, 9000.0)
        for (count in 2..8) {
            val decimals = decimalsForStep(range.gridStep(count))
            assertEquals("count=$count 的刻度不该带小数", 0, decimals)
            for (value in range.gridLines(count)) {
                assertTrue("刻度 $value 不是整数", abs(value - Math.round(value)) < 1e-6)
            }
        }
    }

    @Test
    fun `极小价格的区间——不会把小数量化成 0`() {
        val range = ValueRange(0.0000120, 0.0000130)
        val step = range.gridStep(5)
        val decimals = decimalsForStep(step)
        assertTrue("步长 $step 至少要能看见末位", decimals >= 6)
        assertTrue("刻度不该全是 0", range.gridLines(5).any { it > 0.0 })
    }

    @Test
    fun `窄量程也不会算不出步长`() {
        val range = ValueRange(100.0, 100.0001)
        assertTrue(range.gridStep(5).isFinite())
        assertTrue(range.gridLines(5).isNotEmpty())
    }
}
