package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SpotSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

class SpotSymbolsTest {

    @Test
    fun `币安写法转 Gate 按最长报价币拆分`() {
        assertEquals("BTC_USDT", SpotSymbols.toGate("BTCUSDT"))
        assertEquals("BTC_USDC", SpotSymbols.toGate("BTCUSDC"))
        // FDUSD 比 USD 长：不能被拆成 BTCF_DUSD 之类的错误结果
        assertEquals("BTC_FDUSD", SpotSymbols.toGate("BTCFDUSD"))
        assertEquals("USD1_USDT", SpotSymbols.toGate("USD1USDT"))
        assertEquals("BTC_USD1", SpotSymbols.toGate("BTCUSD1"))
        assertEquals("ETH_BTC", SpotSymbols.toGate("ETHBTC"))
    }

    @Test
    fun `Gate 原生写法转内部写法`() {
        assertEquals("BTCUSDT", SpotSymbols.toInternal("BTC_USDT"))
        assertEquals("1000SHIBUSDT", SpotSymbols.toInternal("1000SHIB_USDT"))
    }

    @Test
    fun `已经带下划线的写法保持原样`() {
        assertEquals("BTC_USDT", SpotSymbols.toGate("BTC_USDT"))
    }
}

class GateSpotDialectTest {

    private val gate = SpotDialects.of(SpotSource.GATE)

    @Test
    fun `交易对清单只收 tradable 且精度换算成 tick`() {
        val text = """
            [
              {"id":"BTC_USDT","base":"BTC","quote":"USDT","trade_status":"tradable","precision":1,"amount_precision":6},
              {"id":"ETH_USDT","base":"ETH","quote":"USDT","trade_status":"untradable","precision":2,"amount_precision":4}
            ]
        """.trimIndent()
        val list = gate.parseExchangeInfo(text)
        assertEquals(1, list.size)
        assertEquals("BTCUSDT", list[0].id.symbol)
        assertEquals("BTC", list[0].baseAsset)
        assertEquals("USDT", list[0].quoteAsset)
        assertEquals(0, BigDecimal("0.1").compareTo(list[0].priceTickSize))
        assertEquals(0, BigDecimal("0.000001").compareTo(list[0].quantityStepSize))
        assertTrue(list[0].isTrading)
    }

    @Test
    fun `K 线按 Gate 列序还原且时间戳从秒转毫秒`() {
        // [时间(s), 计价成交额, close, high, low, open, 基础成交额, 是否收盘]
        val text = """
            [["1793637420","854172.9","77497.6","77533.5","77494.3","77533.4","11.02","true"]]
        """.trimIndent()
        val candle = gate.parseKlines(text, minutes = 1).single()
        assertEquals(1_793_637_420_000L, candle.openTime)
        assertEquals(1_793_637_480_000L, candle.closeTime)
        assertEquals(0, BigDecimal("77533.4").compareTo(candle.open))
        assertEquals(0, BigDecimal("77533.5").compareTo(candle.high))
        assertEquals(0, BigDecimal("77494.3").compareTo(candle.low))
        assertEquals(0, BigDecimal("77497.6").compareTo(candle.close))
        assertEquals(0, BigDecimal("11.02").compareTo(candle.volume))
        assertEquals(0, BigDecimal("854172.9").compareTo(candle.quoteVolume))
    }

    @Test
    fun `24h 快照没有开盘价时用涨跌幅反推`() {
        val text = """
            [{"currency_pair":"BTC_USDT","last":"77445.3","change_percentage":"-0.78",
              "high_24h":"78860.7","low_24h":"77276.9",
              "base_volume":"6771.43","quote_volume":"528522183.9"}]
        """.trimIndent()
        val ticker = gate.parseTicker(text, "BTCUSDT", now = 1L)!!
        assertEquals("BTCUSDT", ticker.id.symbol)
        assertEquals(0, BigDecimal("77445.3").compareTo(ticker.lastPrice))
        assertEquals(-0.78, ticker.changePercent, 0.001)
    }

    @Test
    fun `全市场快照把 Gate 写法折回内部写法`() {
        val text = """
            [{"currency_pair":"ETH_USDT","last":"3000","change_percentage":"1.5"}]
        """.trimIndent()
        val ticker = gate.parseTickers(text, now = 1L).single()
        assertEquals("ETHUSDT", ticker.id.symbol)
    }

    @Test
    fun `请求路径使用 Gate 写法与 Gate 周期码`() {
        assertTrue(gate.tickerPath("BTCUSDT").contains("currency_pair=BTC_USDT"))
        val kline = gate.klinesPath("BTCUSDT", minutes = 10_080, limit = 200, startTime = null, endTime = null)
        assertTrue(kline.contains("interval=7d"))
        assertTrue(kline.contains("currency_pair=BTC_USDT"))
    }
}

class SpotSourceSelectionTest {

    @Test
    fun `缺省与脏值回落到币安`() {
        assertEquals(SpotSource.BINANCE, SpotSource.normalize(null))
        assertEquals(SpotSource.BINANCE, SpotSource.normalize("gatex"))
        assertEquals(SpotSource.GATE, SpotSource.normalize(" gate "))
    }

    @Test
    fun `现货域名链跟随当前源`() {
        assertEquals(
            listOf("https://api.gateio.ws"),
            DefaultRestHosts(spotSource = { SpotSource.GATE }).hostsFor(MarketType.SPOT),
        )
        val binance = DefaultRestHosts(spotSource = { SpotSource.BINANCE }).hostsFor(MarketType.SPOT)
        assertEquals("https://data-api.binance.vision", binance.first())
        assertTrue("https://api4.binance.com" in binance)
    }
}

class RateBudgetWindowTest {

    @Test
    fun `Gate 的 10 秒窗口不会把一分钟额度提前放完`() {
        var now = 0L
        val budget = RateBudget(limitPerWindow = 160, windowMs = 10_000L, nowMs = { now })
        // 前 160 次立刻放行；第 161 次必须等到最早一笔滑出 10 秒窗口
        kotlinx.coroutines.test.runTest {
            repeat(160) { budget.await(1) }
            assertEquals(10_000L, budget.waitMs(cost = 1, now = now))
            now = 9_999L
            assertEquals(1L, budget.waitMs(cost = 1, now = now))
            now = 10_000L
            assertEquals(0L, budget.waitMs(cost = 1, now = now))
        }
    }
}

class SpotHostHealthTest {

    private var now = 0L
    private val health = SpotHostHealth(
        now = { now },
        hostCooldownMs = 180_000L,
        allFailedCooldownMs = 15_000L,
    )

    @Test
    fun `最近成功的主机置顶而失败主机沉底`() {
        assertEquals(listOf("a", "b"), health.order(listOf("a", "b")))
        health.noteOk("b")
        assertEquals(listOf("b", "a"), health.order(listOf("a", "b")))
        health.noteFailed("b")
        assertEquals(listOf("a", "b"), health.order(listOf("a", "b")))
    }

    @Test
    fun `失败主机冷却结束后重新参与排序`() {
        health.noteFailed("b")
        now = 179_999L
        assertEquals(listOf("a", "b"), health.order(listOf("a", "b")))
        now = 180_000L
        health.noteOk("b")
        assertEquals(listOf("b", "a"), health.order(listOf("a", "b")))
    }

    @Test
    fun `整条链失败后短暂停试并保留上次错误`() {
        val error = IOException("all down")
        health.noteAllFailed(SpotSource.BINANCE.key, error)
        assertTrue(health.unavailable(SpotSource.BINANCE.key))
        // 换成 Gate 不受币安那条链的停试影响
        assertFalse(health.unavailable(SpotSource.GATE.key))
        now = 15_000L
        assertFalse(health.unavailable(SpotSource.BINANCE.key))
    }

    @Test
    fun `任一主机成功后清除停试`() {
        health.noteAllFailed(SpotSource.BINANCE.key, IOException("all down"))
        health.noteOk("a")
        assertFalse(health.unavailable(SpotSource.BINANCE.key))
    }

    @Test
    fun `换网后首选与冷却全部作废`() {
        health.noteOk("b")
        health.noteFailed("a")
        health.noteAllFailed(SpotSource.BINANCE.key, IOException("all down"))

        health.reset()

        // 回到链的原顺序：旧路径上被记成「冷却中」的主机不再被压在链尾
        assertEquals(listOf("a", "b"), health.order(listOf("a", "b")))
        // 停试窗与上次错误一起清掉，下一次请求真的会发出去
        assertFalse(health.unavailable(SpotSource.BINANCE.key))
    }
}
