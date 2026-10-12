package com.waxilo.marketmonitor.ui.chart

import androidx.compose.ui.geometry.Offset
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * 视觉画线的两条纯函数链：
 * 1. 时间戳 ↔ 分数蜡烛下标的折算（[ChartLine.toGuideLine] / [DrawGuideLine.toChartLine]），
 *    持久化只认绝对时间，显示层只认下标，两边一正一反必须对得上；
 * 2. 直线抓取判定（[closestTrend] / [distanceToSegment]）—— 判错就是
 *    「按在延长线上却把整根线拖走」或「想抓线却多画了一根」。
 */
class DrawGuideLineTest {

    private val interval = CandleInterval.of(OfficialInterval.H1)
    private val stepMs = 60 * 60_000L
    private val firstT = 1_700_000_000_000L / stepMs * stepMs

    private fun candle(index: Int): Kline = Kline(
        openTime = firstT + index * stepMs,
        closeTime = firstT + (index + 1) * stepMs,
        open = BigDecimal.ONE,
        high = BigDecimal("2"),
        low = BigDecimal.ONE,
        close = BigDecimal.ONE,
        volume = BigDecimal.ONE,
        quoteVolume = BigDecimal.ONE,
    )

    /** 首根 index 0、共 30 根 */
    private val candles = (0 until 30).map(::candle)

    @Test
    fun `水平线往返 价格原样 不碰时间轴`() {
        val saved = ChartLine(
            id = 7L, market = MarketType.FUTURES, symbol = "BTCUSDT",
            kind = ChartLineKind.HORIZONTAL, price = BigDecimal("43210.5"), createdAt = 1L,
        )
        val guide = saved.toGuideLine(candles, interval)!!
        assertEquals(43210.5, guide.price, 1e-9)
        val back = guide.toChartLine(MarketType.FUTURES, "BTCUSDT", candles, interval, 1L)!!
        assertEquals(BigDecimal("43210.5"), back.price)
        assertEquals(7L, back.id)
    }

    @Test
    fun `直线往返 时间戳折成下标再折回 逐点相等`() {
        val saved = ChartLine(
            id = 3L, market = MarketType.FUTURES, symbol = "BTCUSDT",
            kind = ChartLineKind.TREND,
            t1 = firstT + 5 * stepMs, p1 = BigDecimal("100.25"),
            t2 = firstT + 12 * stepMs, p2 = BigDecimal("88.5"),
            createdAt = 1L,
        )
        val guide = saved.toGuideLine(candles, interval)!!
        assertEquals(5.0, guide.anchor1!!.index, 1e-9)
        assertEquals(12.0, guide.anchor2!!.index, 1e-9)
        val back = guide.toChartLine(MarketType.FUTURES, "BTCUSDT", candles, interval, 1L)!!
        assertEquals(saved.t1, back.t1)
        assertEquals(saved.t2, back.t2)
        assertEquals(BigDecimal("100.25"), back.p1)
        assertEquals(BigDecimal("88.5"), back.p2)
    }

    @Test
    fun `非整根时刻落在两根蜡烛之间`() {
        val saved = ChartLine(
            id = 4L, market = MarketType.FUTURES, symbol = "BTCUSDT",
            kind = ChartLineKind.TREND,
            t1 = firstT + stepMs / 2, p1 = BigDecimal.ONE,
            t2 = firstT + 2 * stepMs, p2 = BigDecimal.ONE,
            createdAt = 1L,
        )
        val guide = saved.toGuideLine(candles, interval)!!
        assertEquals(0.5, guide.anchor1!!.index, 1e-9)
    }

    @Test
    fun `序列为空或数据缺项一律丢弃`() {
        val horizontal = ChartLine(
            id = 1L, market = MarketType.FUTURES, symbol = "S",
            kind = ChartLineKind.HORIZONTAL, price = BigDecimal.ONE, createdAt = 1L,
        )
        assertNull(horizontal.toGuideLine(emptyList(), interval))
        // 直线缺第二个端点（异常行）：不猜，直接不画
        val broken = ChartLine(
            id = 5L, market = MarketType.FUTURES, symbol = "S", kind = ChartLineKind.TREND,
            t1 = firstT, p1 = BigDecimal.ONE, t2 = null, p2 = BigDecimal.ONE, createdAt = 1L,
        )
        assertNull(broken.toGuideLine(candles, interval))
        // 草稿价格还没定（NaN）时不该落库
        val draft = DrawGuideLine(id = 0L, kind = ChartLineKind.HORIZONTAL)
        assertNull(draft.toChartLine(MarketType.FUTURES, "S", candles, interval, 1L))
    }

    @Test
    fun `翻页前插历史后同一条线跟着挪下标 时间戳不变`() {
        val saved = ChartLine(
            id = 9L, market = MarketType.FUTURES, symbol = "BTCUSDT",
            kind = ChartLineKind.TREND,
            t1 = firstT + 3 * stepMs, p1 = BigDecimal("100"),
            t2 = firstT + 9 * stepMs, p2 = BigDecimal("120"),
            createdAt = 1L,
        )
        val before = saved.toGuideLine(candles, interval)!!
        assertEquals(3.0, before.anchor1!!.index, 1e-9)
        // 前插一根更早的蜡烛：同一条线在序列里的下标整体 +1
        val shifted = listOf(candle(-1)) + candles
        val after = saved.toGuideLine(shifted, interval)!!
        assertEquals(4.0, after.anchor1!!.index, 1e-9)
        assertEquals(
            saved.t1,
            after.toChartLine(MarketType.FUTURES, "BTCUSDT", shifted, interval, 1L)!!.t1,
        )
    }

    private val pixels = listOf(
        TrendLinePixels(id = 1L, p1 = Offset(0f, 0f), p2 = Offset(200f, 100f)),
        TrendLinePixels(id = 2L, p1 = Offset(0f, 300f), p2 = Offset(200f, 300f)),
    )

    @Test
    fun `容差内抓到最近的线段`() {
        // (100, 55) 距第一条线约 4.5px、距第二条 245px
        assertEquals(1L, pixels.closestTrend(Offset(100f, 55f), slopPx = 24f)?.id)
    }

    @Test
    fun `延长线上的落点不算抓取`() {
        // 第一条线向右延长到 x=400 处 y 应在 200 —— 但锚段只到 (200,100)，
        // 按在延长线上距离端点 223px，超出容差 = 落一颗新种子
        assertNull(pixels.closestTrend(Offset(400f, 200f), slopPx = 24f))
        // 紧贴端点外一点仍在容差内（距离按端点算）
        assertEquals(1L, pixels.closestTrend(Offset(210f, 105f), slopPx = 24f)?.id)
    }

    @Test
    fun `超出容差返回 null`() {
        assertNull(pixels.closestTrend(Offset(100f, 150f), slopPx = 24f))
        assertNull(emptyList<TrendLinePixels>().closestTrend(Offset(100f, 100f), slopPx = 24f))
    }

    @Test
    fun `点到线段 垂足在内取垂直距离 在外取近端点`() {
        val a = Offset(0f, 0f)
        val b = Offset(100f, 0f)
        assertEquals(3f, distanceToSegment(Offset(50f, 3f), a, b), 1e-4f)
        assertEquals(5f, distanceToSegment(Offset(-5f, 0f), a, b), 1e-4f)
        assertEquals(5f, distanceToSegment(Offset(105f, 0f), a, b), 1e-4f)
    }
}
