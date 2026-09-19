package com.waxilo.marketmonitor.ui.chart

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** 图表纯模型：窗口、刻度、指标序列。像素与 Compose 不在此覆盖。 */
class ChartModelTest {

    private val day = CandleInterval.of(OfficialInterval.D1)
    private val minuteMs = 60_000L

    private fun candles(count: Int, base: Double = 100.0): List<Kline> = (0 until count).map { i ->
        // 收盘价线性上涨，便于手算指标与涨幅
        val close = base + i
        Kline(
            openTime = i * 1_440 * minuteMs,
            closeTime = (i + 1) * 1_440 * minuteMs - 1,
            open = BigDecimal.valueOf(close - 0.5),
            high = BigDecimal.valueOf(close + 1),
            low = BigDecimal.valueOf(close - 1.5),
            close = BigDecimal.valueOf(close),
            volume = BigDecimal.valueOf(10),
            quoteVolume = BigDecimal.valueOf(100),
            trades = 5,
        )
    }

    // region 视口
    @Test
    fun `空序列的窗口是空区间`() {
        assertTrue(ChartViewport(0, 120, 0).window().isEmpty())
    }

    @Test
    fun `序列不足一屏时可见根数收到实际长度`() {
        val viewport = ChartViewport(barCount = 6, visibleBars = 120).clamp()
        assertEquals(6, viewport.visibleBars)
        assertEquals(0..5, viewport.window())
    }

    @Test
    fun `右端不能拖过最新一根`() {
        assertEquals(0, ChartViewport(100, 50, -10).clamp().rightOffset)
    }

    @Test
    fun `往更早方向拖到底时停在序列起点`() {
        val moved = ChartViewport(100, 50, 0).pan(1_000f)
        assertEquals(50, moved.rightOffset)
        assertEquals(0, moved.startIndex())
    }

    @Test
    fun `缩放上下界固定`() {
        val viewport = ChartViewport(1_000, 100, 0)
        assertEquals(ChartViewport.MIN_BARS, viewport.zoom(0.0001f).visibleBars)
        assertEquals(ChartViewport.MAX_BARS, viewport.zoom(1_000f).visibleBars)
    }

    @Test
    fun `缩放时锚点处的蜡烛保持不动`() {
        val viewport = ChartViewport(1_000, 100, 200)
        val start = viewport.startIndex()
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f)
        // 锚点在左端：缩放后左端仍是同一根蜡烛
        assertEquals(start, anchored.startIndex())
    }

    @Test
    fun `横坐标反查与归一化互为逆运算`() {
        val viewport = ChartViewport(300, 100, 0)
        val index = viewport.indexAt(0.5f)
        val fraction = viewport.fractionOf(index)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `序列变长后仍贴着最新`() {
        val grown = ChartViewport(300, 100, 0).resize(301)
        assertEquals(300, grown.endIndex())
    }
    // endregion

    // region 刻度
    @Test
    fun `归一化位置顶部为0底部为1`() {
        val range = ValueRange(10.0, 20.0)
        assertEquals(0f, range.toFraction(20.0), 1e-6f)
        assertEquals(1f, range.toFraction(10.0), 1e-6f)
        assertEquals(0.5f, range.toFraction(15.0), 1e-6f)
    }

    @Test
    fun `常数序列留余量避免出现零跨度`() {
        val range = ValueRange(5.0, 5.0).padded()
        assertTrue(range.high > range.low)
    }

    @Test
    fun `NaN 不参与取值`() {
        val range = ValueRange.of(doubleArrayOf(3.0, Double.NaN, 1.0, 9.0, Double.NaN))!!
        assertEquals(1.0, range.low, 1e-9)
        assertEquals(9.0, range.high, 1e-9)
    }

    @Test
    fun `网格线单调且落在范围内`() {
        val lines = ValueRange(1_000.0, 4_500.0).gridLines()
        // 步长向下取整到 1-2-5 阶梯，因此线数允许略多于请求的 5 条
        assertTrue(lines.size in 3..9)
        assertTrue(lines.zipWithNext().all { (a, b) -> a < b })
        assertTrue(lines.all { it >= 1_000.0 && it <= 4_500.0 })
        assertTrue(lines.first() % 500.0 == 0.0)
    }
    // endregion

    // region 指标序列
    @Test
    fun `均线窗口不足处为 NaN 且不画假线`() {
        val series = ChartModel.build(candles(60), listOf(5, 10), showBoll = false, subPane = SubPaneKind.NONE)
        val ma5 = series.overlay.lines.first { it.label == "MA5" }
        assertTrue(ma5.values[3].isNaN())
        assertFalse(ma5.values[4].isNaN())
    }

    @Test
    fun `开启 BOLL 后给出上下轨与填充带`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = true, subPane = SubPaneKind.NONE)
        assertNotNull(series.overlay.bandFill)
        assertTrue(series.overlay.lines.any { it.label == "BOLL.U" })
    }

    @Test
    fun `RSI 副图刻度固定在 0 到 100`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = false, subPane = SubPaneKind.RSI)
        assertEquals(ValueRange(0.0, 100.0), series.subPane?.fixedRange)
    }

    @Test
    fun `VOL 副图从 0 起画`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = false, subPane = SubPaneKind.VOLUME)
        val pane = series.subPane!!
        assertTrue(pane.fromZero)
        assertNotNull(pane.bars)
        assertEquals(0.0, series.subRange(0, 59).low, 1e-9)
    }

    @Test
    fun `主图范围包含可见的均线值`() {
        val series = ChartModel.build(candles(60), listOf(30), showBoll = false, subPane = SubPaneKind.NONE)
        val range = series.mainRange(0, 59)
        assertTrue(range.high >= series.candles.last().high.toDouble())
    }
    // endregion

    // region tooltip
    @Test
    fun `tooltip 的涨幅按上一根收盘计算`() {
        val tip = ChartModel.tooltip(candles(10), 5, day, null)!!
        // 第 5 根收 105、上一根收 104 → +0.96%
        assertEquals("较上根 +0.96%", tip.changeText)
        assertEquals("105.00", tip.close)
        assertEquals("106.00", tip.high)
    }

    @Test
    fun `首根没有上一根可比时涨幅为占位`() {
        val tip = ChartModel.tooltip(candles(10), 0, day, null)!!
        assertEquals("较上根 --", tip.changeText)
    }

    @Test
    fun `下标越界不返回 tooltip`() {
        assertNull(ChartModel.tooltip(candles(3), 9, day, null))
    }

    @Test
    fun `日线 tooltip 只到天，分钟线带时分`() {
        // 不断言具体日期：CI 用 UTC、本地时区可能不同
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(ChartModel.formatTime(0L, 1_440L)))
        assertTrue(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(ChartModel.formatTime(0L, 5L)))
    }
    // endregion
}
