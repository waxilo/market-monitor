package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两点直线的纵向换算（2026-11-23 用户「缩放纵坐标，直线穿过的点还是会变」）。
 *
 * 根因不在划线状态，而在 `ValueRange.toFraction` 的 `coerceIn(-0.5f, 1.5f)`：
 * 夹取对**单点**（蜡烛、网格、价签）无害 —— 越界本来也要被裁掉；
 * 但对一条**两点定斜率**的直线是灾难：任一端被按在边界上，斜率就变了，
 * 纵轴一缩放、锚点出量程，线绕着另一端转，于是「穿过的点」跟着漂。
 *
 * 这里的断言全部按 `KlineChart.anchorPx` 的同一条链复算：
 * `fractionRaw(price)` → `top + fraction * 主图高`。
 */
class TrendAnchorGeometryTest {

    private val top = 0f
    private val height = 400f

    /** 与 `ChartGeo.yOf(fraction, top, height)` 同式。 */
    private fun yOf(range: ValueRange, price: Double): Float =
        top + range.fractionRaw(price) * height

    /** 旧口径（夹取），只用来当反例。 */
    private fun yOfClamped(range: ValueRange, price: Double): Float =
        top + range.toFraction(price) * height

    @Test
    fun `锚点跑出量程时 fractionRaw 不夹取 toFraction 仍按老口径夹`() {
        val range = ValueRange(100.0, 200.0)
        // 200 以下全是量程内，价格越低 fraction 越大；150 是纵向正中
        assertEquals(0f, range.fractionRaw(200.0), 1e-6f)
        assertEquals(1f, range.fractionRaw(100.0), 1e-6f)
        assertEquals(0.5f, range.fractionRaw(150.0), 1e-6f)
        // 出量程：真值 2.0 / -1.0，不夹取就必须原样给出来
        assertEquals(2f, range.fractionRaw(0.0), 1e-6f)
        assertEquals(-1f, range.fractionRaw(300.0), 1e-6f)
        // 极远：夹取会把它按死在 1.5，不夹取要保留真实斜率信息
        assertEquals(11f, range.fractionRaw(-900.0), 1e-6f)
        // 单点口径不许变（蜡烛 / 网格 / 价签全靠它）
        assertEquals(1.5f, range.toFraction(-900.0), 1e-6f)
        assertEquals(0.5f, range.toFraction(150.0), 1e-6f)
    }

    @Test
    fun `缩放纵轴时直线在同一价位上的像素与蜡烛严格重合`() {
        // 直线两锚点：120（视野内）→ 20（远在下方，缩放后必然出量程）
        val p1 = 120.0
        val p2 = 20.0
        // 交点取 90：它在两个锚点之间，且在自动量程内，蜡烛一定画得出来
        val crossing = 90.0
        val base = ValueRange(100.0, 200.0)

        // factor < 1 = 放大（量程变小），一路压到钳制下限 0.08
        listOf(1f, 0.8f, 0.5f, 0.3f, 0.2f, 0.12f, 0.08f).forEach { factor ->
            val range = base.scaled(factor, base)
            val y1 = yOf(range, p1)
            val y2 = yOf(range, p2)
            // 直线的像素位置：数据空间是仿射的，按价格比例插值就是画出来的那一段
            val t = ((crossing - p1) / (p2 - p1)).toFloat()
            val lineY = y1 + t * (y2 - y1)
            val candleY = yOf(range, crossing)
            assertEquals(
                "factor=$factor 量程=${range.low}~${range.high}：直线必须压在同一个价上",
                candleY,
                lineY,
                0.01f,
            )
        }
    }

    @Test
    fun `同一缩放下夹取版会让直线偏离蜡烛 这正是要回归的 bug`() {
        val base = ValueRange(100.0, 200.0)
        // 0.2f：p1=120 与 p2=20 都已出量程，夹取会把两端同时按在 1.5 上
        val range = base.scaled(0.2f, base)
        val y1 = yOfClamped(range, 120.0)
        val y2 = yOfClamped(range, 20.0)
        val t = ((90.0 - 120.0) / (20.0 - 120.0)).toFloat()
        val lineY = y1 + t * (y2 - y1)
        val candleY = yOf(range, 90.0)
        assertTrue(
            "夹取后直线应当明显偏离（斜率被压平），偏差=$lineY vs $candleY",
            kotlin.math.abs(lineY - candleY) > 10f,
        )
    }

    @Test
    fun `两端都在量程外时越界像素仍是有限值 交给裁剪处理`() {
        val base = ValueRange(100.0, 200.0)
        val range = base.scaled(0.08f, base)
        // 两个远在量程外的锚点：像素会到 1e4 量级，但必须有限、且保持线性
        val y1 = yOf(range, 30.0)
        val y2 = yOf(range, 25.0)
        assertTrue("y1=$y1", y1.isFinite())
        assertTrue("y2=$y2", y2.isFinite())
        assertTrue("必须落在量程下方（像素更大）", y1 > height && y2 > height)
        // 价格差 5，像素差 = 5 / span * 高 —— 与量程内完全同一式
        val span = range.high - range.low
        assertEquals((5.0 / span * height).toFloat(), y2 - y1, 0.01f)
    }

    @Test
    fun `越界锚点不影响可见段的裁剪`() {
        // 一条缓斜率的长线：两端像素都在矩形外（x 在左右外、y 在上下外），
        // 但穿过主图 —— 必须裁出一段横贯绘图区的可见段。
        val seg = clipLineToRect(
            a = Offset(-2000f, 1450f),
            b = Offset(3000f, -1050f),
            left = 0f,
            top = 0f,
            right = 1000f,
            bottom = 400f,
        )
        assertNotNull("穿图而过就必须有可见段", seg)
        val (s, e) = seg!!
        assertTrue("端点必须有限", s.x.isFinite() && s.y.isFinite() && e.x.isFinite() && e.y.isFinite())
        assertTrue("可见段必须横贯绘图区：$s → $e", kotlin.math.abs(e.x - s.x) > 700f)
        listOf(s, e).forEach { pt ->
            assertTrue("可见段不会跑出矩形：y=${pt.y}", pt.y >= -0.5f && pt.y <= 400.5f)
        }
    }

    @Test
    fun `完全在矩形外的直线不产出可见段`() {
        val seg = clipLineToRect(
            a = Offset(0f, 10_000f),
            b = Offset(1000f, 12_000f),
            left = 0f,
            top = 0f,
            right = 1000f,
            bottom = 400f,
        )
        assertNull("整条线在图外就什么都不画", seg)
    }

    @Test
    fun `量程退化时不产生 NaN`() {
        val flat = ValueRange(100.0, 100.0)
        assertEquals(0.5f, flat.fractionRaw(100.0), 1e-6f)
        assertEquals(0.5f, flat.fractionRaw(1_000.0), 1e-6f)
        assertTrue(flat.fractionRaw(Double.NaN).isNaN())
    }
}
