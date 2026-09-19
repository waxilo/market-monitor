package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.ws.WsEvent
import com.waxilo.marketmonitor.data.remote.ws.WsParser
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RateBudgetTest {

    private var now = 0L
    private val budget = RateBudget(limitPerMinute = 100, nowMs = { now })

    @Test
    fun `预算未满时可立即发出`() = runTest {
        budget.await(20)
        assertEquals(0L, budget.waitMs(cost = 80, now = now))
    }

    @Test
    fun `超预算后按最老一笔的滑出时间等待`() = runTest {
        budget.await(100)
        assertEquals(RateBudget.WINDOW_MS, budget.waitMs(cost = 1, now = 0L))
        assertEquals(1L, budget.waitMs(cost = 1, now = RateBudget.WINDOW_MS - 1))
        assertEquals(0L, budget.waitMs(cost = 1, now = RateBudget.WINDOW_MS))
    }

    @Test
    fun `非法成本按零计，超过整个预算的单笔不等待`() {
        // 等也等不出余量，直接发出，由服务端 429 兜底
        assertEquals(0L, budget.waitMs(cost = -10, now = 0L))
        assertEquals(0L, budget.waitMs(cost = 100, now = 0L))
        assertEquals(0L, budget.waitMs(cost = 101, now = 0L))
    }

    @Test
    fun `await 会真的挂起到窗口滑出`() = runTest {
        val clock = RateBudget(limitPerMinute = 100, nowMs = { testScheduler.currentTime })
        clock.await(100)
        clock.await(1)
        assertEquals(RateBudget.WINDOW_MS, testScheduler.currentTime)
    }

    /** 排队多笔时等到最老一笔滑出窗口即放行，放行后窗口内重新有余量。 */
    @Test
    fun `排队多笔时等到最老一笔滑出窗口`() = runTest {
        val clock = RateBudget(limitPerMinute = 100, nowMs = { testScheduler.currentTime })
        clock.await(30)
        delay(1_000)
        clock.await(30)
        delay(1_000)
        clock.await(30)
        clock.await(20)
        assertEquals(RateBudget.WINDOW_MS, testScheduler.currentTime)
        assertEquals(0L, clock.waitMs(cost = 20, now = testScheduler.currentTime))
    }
}

class WsParserTest {

    private val miniTicker = """
        {"stream":"btcusdt@miniTicker","data":{"e":"24hrMiniTicker","E":1789000000000,"s":"BTCUSDT",
        "c":"65000.10","o":"64000.00","h":"66000.00","l":"63000.00","v":"1234.5","q":"80000000"}}
    """.trimIndent()

    private fun tickerOf(event: WsEvent): MarketTicker = (event as WsEvent.Ticker).ticker

    @Test
    fun `解析单交易对 miniTicker`() {
        val ticker = tickerOf(WsParser.parse(miniTicker, MarketType.SPOT, 0L).single())
        assertEquals("BTCUSDT", ticker.id.symbol)
        assertEquals(MarketType.SPOT, ticker.id.market)
        assertEquals(BigDecimal("65000.10"), ticker.lastPrice)
        assertEquals(BigDecimal("64000.00"), ticker.openPrice)
        assertEquals(1789000000000L, ticker.updatedAt)
        assertEquals(1.56265625, ticker.changePercent, 1e-6)
    }

    @Test
    fun `解析合并流的全量数组`() {
        val frame = """{"stream":"!miniTicker@arr","data":[""" +
            """{"e":"24hrMiniTicker","s":"ETHUSDT","c":"3000","o":"2900","h":"3100","l":"2800","v":"10","q":"30000"},""" +
            """{"e":"24hrMiniTicker","s":"BNBUSDT","c":"600","o":"610","h":"620","l":"590","v":"5","q":"3000"}]}"""
        val tickers = WsParser.parse(frame, MarketType.FUTURES, 42L).map { tickerOf(it) }
        assertEquals(2, tickers.size)
        assertEquals("ETHUSDT", tickers[0].id.symbol)
        assertEquals(MarketType.FUTURES, tickers[1].id.market)
        assertEquals(42L, tickers[1].updatedAt)
        assertTrue(tickers[0].changePercent > 0)
        assertTrue(tickers[1].changePercent < 0)
    }

    @Test
    fun `解析 kline 流的未收盘蜡烛`() {
        val frame = """
            {"stream":"btcusdt@kline_1m","data":{"e":"kline","E":1789000001000,"s":"BTCUSDT","k":{
            "t":1789000000000,"T":1789000059999,"o":"100","c":"105","h":"110","l":"99","v":"7","q":"700","n":42,"x":false}}}
        """.trimIndent()
        val event = WsParser.parse(frame, MarketType.SPOT, 0L).single() as WsEvent.KlineUpdate
        assertEquals("BTCUSDT", event.symbol)
        assertEquals(1789000000000L, event.kline.openTime)
        assertEquals(BigDecimal("105"), event.kline.close)
        assertEquals(42L, event.kline.trades)
        assertTrue(!event.kline.closed)
    }

    @Test
    fun `缺字段的坏帧与未知事件被丢弃`() {
        assertEquals(0, WsParser.parse("not json", MarketType.SPOT, 0L).size)
        assertEquals(0, WsParser.parse("""{"data":{"e":"accountUpdate"}}""", MarketType.SPOT, 0L).size)
        assertEquals(0, WsParser.parse("""{"data":{"e":"24hrMiniTicker","s":"X"}}""", MarketType.SPOT, 0L).size)
        assertEquals(0, WsParser.parse("""{"data":{"e":"24hrMiniTicker","symbol":"X"}}""", MarketType.SPOT, 0L).size)
    }
}
