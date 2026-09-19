package com.waxilo.marketmonitor.domain

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.KlineAggregator
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

fun kline(
    openTime: Long,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String = "1",
    closed: Boolean = true,
): Kline = Kline(
    openTime = openTime,
    closeTime = openTime + 60_000L,
    open = BigDecimal(open),
    high = BigDecimal(high),
    low = BigDecimal(low),
    close = BigDecimal(close),
    volume = BigDecimal(volume),
    quoteVolume = BigDecimal(volume).multiply(BigDecimal(close)),
    trades = 1,
    closed = closed,
)

private const val MINUTE = 60_000L

class CandleIntervalTest {

    @Test
    fun `自定义周期选取最大可整除的官方周期作为基础周期`() {
        assertEquals(OfficialInterval.M5, CandleInterval.custom(10)?.baseInterval)
        assertEquals(OfficialInterval.M15, CandleInterval.custom(45)?.baseInterval)
        assertEquals(OfficialInterval.H1, CandleInterval.custom(180)?.baseInterval)
        // 7m 只能由 1m 聚合
        assertEquals(OfficialInterval.M1, CandleInterval.custom(7)?.baseInterval)
    }

    @Test
    fun `官方周期本身不需要聚合`() {
        val interval = CandleInterval.of(OfficialInterval.H4)
        assertTrue(interval.isOfficial)
        assertEquals(1, interval.aggregateRatio)
        assertEquals("4h", interval.apiCode)
    }

    @Test
    fun `非法自定义周期被拒绝`() {
        assertNull(CandleInterval.custom(240)) // 240m 即官方 4h
        assertNull(CandleInterval.custom(0))
        assertNull(CandleInterval.custom(43_201))
    }

    @Test
    fun `聚合倍率与请求周期码`() {
        val tenMinutes = CandleInterval.custom(10)!!
        assertEquals(2, tenMinutes.aggregateRatio)
        assertEquals("5m", tenMinutes.apiCode)
        assertEquals("10m", tenMinutes.label)
        assertEquals("3h", CandleInterval.custom(180)!!.label)
        assertEquals("2d", CandleInterval.custom(2_880)!!.label)
    }

    @Test
    fun `存储键可往返`() {
        for (interval in CandleInterval.quickPickPresets) {
            assertEquals(interval, CandleInterval.fromStorageKey(interval.storageKey))
        }
        val custom = CandleInterval.custom(10)!!
        assertEquals(custom, CandleInterval.fromStorageKey(custom.storageKey))
    }
}

class KlineAggregatorTest {

    @Test
    fun `五根一分钟蜡烛聚合成一根五周期蜡烛`() {
        val source = (0 until 5).map { i ->
            kline(i * MINUTE, "${1 + i}", "${2 + i}", "${0 + i}.5", "${1.5 + i}", volume = "2")
        }
        val merged = KlineAggregator.aggregate(source, CandleInterval.of(OfficialInterval.M5))

        assertEquals(1, merged.size)
        val candle = merged.first()
        assertEquals(BigDecimal("1"), candle.open)
        assertEquals(BigDecimal("5.5"), candle.close)
        assertEquals(BigDecimal("6"), candle.high)
        assertEquals(BigDecimal("0.5"), candle.low)
        assertEquals(BigDecimal("10"), candle.volume)
        assertEquals(4 * MINUTE + 60_000L, candle.closeTime)
        assertTrue(candle.closed)
    }

    @Test
    fun `聚合按目标周期边界分桶而非按根数`() {
        // 5m 蜡烛：00:00 与 00:05 属于同一个 10m 桶；00:10 与 00:15 属于下一个
        val source = listOf(0L, 5, 10, 15).map { i ->
            kline(i * 5 * MINUTE, "$i", "$i", "$i", "$i", volume = "1")
        }
        val merged = KlineAggregator.aggregate(source, CandleInterval.custom(10)!!)

        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].openTime)
        assertEquals(10 * 5 * MINUTE, merged[1].openTime)
        assertTrue(merged.all { it.closed })
    }

    @Test
    fun `不完整的最后一个桶视为未收盘`() {
        val source = listOf(0L, 5L, 10L).map { i ->
            kline(i * 5 * MINUTE, "$i", "$i", "$i", "$i")
        }
        val merged = KlineAggregator.aggregate(source, CandleInterval.custom(10)!!)

        assertEquals(2, merged.size)
        assertTrue(merged[0].closed)
        assertFalse("最后一桶只有 1 根 5m 数据", merged[1].closed)
    }

    @Test
    fun `乱序输入先排序再聚合`() {
        val source = listOf(
            kline(10 * MINUTE, "3", "3", "3", "3"),
            kline(0L, "1", "1", "1", "1"),
            kline(5 * MINUTE, "2", "2", "2", "2"),
        )
        // 三根 1m/5m 混合数据落在同一个 15m 桶内，排序后 open 取最早一根
        val merged = KlineAggregator.aggregate(source, CandleInterval.of(OfficialInterval.M15))
        assertEquals(1, merged.size)
        assertEquals(BigDecimal("1"), merged.first().open)
        assertEquals(BigDecimal("3"), merged.first().close)
    }

    @Test
    fun `同桶重复推送保留最新一根`() {
        val deduped = KlineAggregator.dedupeByOpenTime(
            listOf(kline(0L, "1", "9", "1", "2"), kline(0L, "1", "9", "1", "3")),
        )
        assertEquals(1, deduped.size)
        assertEquals(BigDecimal("3"), deduped.first().close)
    }

    @Test
    fun `官方周期聚合对同源数据等价于恒等`() {
        val source = (0 until 20).map { i -> kline(i * 60 * MINUTE, "$i", "$i", "$i", "$i") }
        val merged = KlineAggregator.aggregate(source, CandleInterval.of(OfficialInterval.H1))
        assertEquals(source.size, merged.size)
        assertEquals(source.map { it.openTime }, merged.map { it.openTime })
    }
}

class SymbolIdTest {

    @Test
    fun `现货与合约同名交易对互不覆盖`() {
        val spot = SymbolId(MarketType.SPOT, "BTCUSDT")
        val futures = SymbolId(MarketType.FUTURES, "BTCUSDT")
        assertTrue(spot != futures)
        assertEquals("SPOT:BTCUSDT", spot.storageKey)
        assertEquals(futures, SymbolId.fromStorageKey(futures.storageKey))
        assertNull(SymbolId.fromStorageKey("nonsense"))
    }
}
