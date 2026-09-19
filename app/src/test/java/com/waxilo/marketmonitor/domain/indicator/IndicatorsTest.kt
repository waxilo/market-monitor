package com.waxilo.marketmonitor.domain.indicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class IndicatorsTest {

    private fun series(vararg values: Int): DoubleArray = values.map { it.toDouble() }.toDoubleArray()

    @Test
    fun `SMA 窗口不足处为 NaN 且使用滚动求和`() {
        val out = Indicators.sma(series(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 3)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertEquals(2.0, out[2], 1e-9)
        assertEquals(9.0, out[9], 1e-9)
    }

    @Test
    fun `EMA 以首个窗口的均值起算`() {
        val out = Indicators.ema(series(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 5)
        assertTrue(out[3].isNaN())
        assertEquals(3.0, out[4], 1e-9)
        // alpha = 2/6
        assertEquals(4.0, out[5], 1e-9)
    }

    @Test
    fun `EMA 对常数序列保持常数`() {
        val input = DoubleArray(30) { 2.0 }
        val out = Indicators.ema(input, 10)
        for (i in 9 until 30) assertEquals(2.0, out[i], 1e-9)
        assertTrue(out[8].isNaN())
    }

    @Test
    fun `MACD 对常数序列全为零且滞后出值`() {
        val close = DoubleArray(60) { 100.0 }
        val macd = Indicators.macd(close)
        assertTrue(macd.dif[24].isNaN())
        assertEquals(0.0, macd.dif[25], 1e-9)
        assertTrue(macd.signal[32].isNaN())
        assertEquals(0.0, macd.signal[33], 1e-9)
        assertEquals(0.0, macd.histogram[40], 1e-9)
    }

    @Test
    fun `RSI 单边上涨为 100，横盘为 50`() {
        val rising = Indicators.rsi(series(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), 14)
        assertTrue(rising[13].isNaN())
        assertEquals(100.0, rising[14], 1e-9)

        val flat = Indicators.rsi(DoubleArray(20) { 7.0 }, 14)
        assertEquals(50.0, flat[14], 1e-9)
    }

    @Test
    fun `RSI 单调下跌趋近 0`() {
        val falling = Indicators.rsi(DoubleArray(30) { 100.0 - it }, 14)
        assertEquals(0.0, falling[29], 1e-9)
    }

    @Test
    fun `BOLL 上下轨关于中轴对称`() {
        val close = series(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val boll = Indicators.boll(close, period = 20, multiplier = 2.0)
        val deviation = sqrt(33.25)
        assertEquals(10.5, boll.middle[19], 1e-9)
        assertEquals(10.5 + 2 * deviation, boll.upper[19], 1e-6)
        assertEquals(10.5 - 2 * deviation, boll.lower[19], 1e-6)
        assertTrue(boll.upper[18].isNaN())
    }

    @Test
    fun `BOLL 上界不低于下界`() {
        val close = DoubleArray(120) { i -> 100.0 + (i % 7) - (i % 5) }
        val boll = Indicators.boll(close, 20, 2.0)
        for (i in 19 until close.size) {
            assertTrue("upper>=lower at $i", boll.upper[i] >= boll.middle[i])
            assertTrue("lower<=middle at $i", boll.lower[i] <= boll.middle[i])
        }
    }

    @Test
    fun `KDJ 横盘收敛于 50`() {
        val high = DoubleArray(30) { 10.0 }
        val low = DoubleArray(30) { 10.0 }
        val close = DoubleArray(30) { 10.0 }
        val kdj = Indicators.kdj(high, low, close)
        assertTrue(kdj.k[7].isNaN())
        assertEquals(50.0, kdj.k[8], 1e-9)
        assertEquals(50.0, kdj.d[8], 1e-9)
        assertEquals(50.0, kdj.j[8], 1e-9)
    }

    @Test
    fun `KDJ 在极值处保持在合理区间`() {
        val size = 200
        val close = DoubleArray(size) { i -> 100.0 + 10.0 * kotlin.math.sin(i / 5.0) }
        val high = DoubleArray(size) { i -> close[i] + 1.0 }
        val low = DoubleArray(size) { i -> close[i] - 1.0 }
        val kdj = Indicators.kdj(high, low, close)
        for (i in 8 until size) {
            assertFalse(kdj.k[i].isNaN())
            assertTrue("K 值超出合理区间", kdj.k[i] in -50.0..150.0)
        }
    }

    @Test
    fun `均线族按周期返回独立序列`() {
        val close = series(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val mas = Indicators.movingAverages(close, listOf(3, 5, 0))
        assertEquals(setOf(3, 5), mas.keys)
        assertEquals(2.0, mas.getValue(3)[2], 1e-9)
        assertEquals(8.0, mas.getValue(5)[9], 1e-9)
    }
}
