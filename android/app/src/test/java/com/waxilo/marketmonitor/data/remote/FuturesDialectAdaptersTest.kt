package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.dialect.FuturesDialects
import com.waxilo.marketmonitor.domain.model.FuturesDialect
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * 合约行情方言适配器单测（PRD 3.2 多盘口接入）。
 *
 * 适配层的全部风险都在「URL 拼错 / 字段读错列」这类静默错误上——接口不会报错，
 * 只是画出一根假 K 线。所以这里把每个方言的请求形状和解析位置都钉住；
 * 样本取自各家公开接口的真实响应结构（字段顺序、包装层、单位口径保持一致）。
 */
class FuturesDialectAdaptersTest {

    private val binance = FuturesDialects.of(FuturesDialect.BINANCE)
    private val okx = FuturesDialects.of(FuturesDialect.OKX)
    private val bybit = FuturesDialects.of(FuturesDialect.BYBIT)
    private val bitget = FuturesDialects.of(FuturesDialect.BITGET)
    private val gate = FuturesDialects.of(FuturesDialect.GATE)
    private val mexc = FuturesDialects.of(FuturesDialect.MEXC)
    private val hyper = FuturesDialects.of(FuturesDialect.HYPERLIQUID)
    private val htx = FuturesDialects.of(FuturesDialect.HTX)
    private val bitunix = FuturesDialects.of(FuturesDialect.BITUNIX)

    private val all = listOf(binance, okx, bybit, bitget, gate, mexc, hyper, htx, bitunix)

    /* ───────────────────── 通用形状 ───────────────────── */

    @Test
    fun `每个方言都能出 1m 与 1h 基础周期，且分钟数单调`() {
        all.forEach { adapter ->
            assertTrue("缺 1m", 1L in adapter.intervalLadder.keys)
            assertTrue("缺 1h", 60L in adapter.intervalLadder.keys)
            val minutes = adapter.intervalLadder.keys.toList()
            assertEquals("周期表必须按分钟递增", minutes.sorted(), minutes)
            assertTrue("周期码不能为空", adapter.intervalLadder.values.all { it.isNotBlank() })
            // Bitunix 单次只给 200 根（实测服务端截断），是当前清单里最小的上限
            assertTrue(adapter.maxKlineLimit in 200..2000)
        }
    }

    @Test
    fun `只有实测确认会无视时间窗的方言才关掉翻页`() {
        // HTX 的 from/to 与 start/end 四种写法都试过，一律返回「最新 size 根」⇒ 不能翻页
        assertFalse("HTX 应关闭时间窗翻页", htx.supportsTimeWindow)
        // 其余家必须保持可翻页，否则「拖到最左加载更早」会静默失效
        listOf(binance, okx, bybit, bitget, gate, mexc, hyper, bitunix).forEach {
            assertTrue("${it::class} 不该关闭时间窗", it.supportsTimeWindow)
        }
    }

    @Test
    fun `解析出的符号一律是规范形状（USDT 本位、无分隔符）`() {
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), binanceExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), okxExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT", "SOLUSDT"), bybitExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT"), bitgetExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), gateExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), mexcExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT", "HYPEUSDT"), hyperExchangeInfo().map { it.id.symbol })
        assertEquals(listOf("BTCUSDT"), htxExchangeInfo().map { it.id.symbol })
        // BTCUSDC 与 status=CLOSE 的那条都应被滤掉，只剩三个 USDT 且 OPEN 的
        assertEquals(listOf("BTCUSDT", "DOGEUSDT", "PEPEUSDT"), bitunixExchangeInfo().map { it.id.symbol })
    }

    @Test
    fun `K 线解析结果一律升序，与各家原生顺序无关`() {
        listOf(
            okxKlinesText to okx,
            bybitKlinesText to bybit,
            bitgetKlinesText to bitget,
            gateKlinesText to gate,
            mexcKlinesText to mexc,
            hyperKlinesText to hyper,
            binanceKlinesText to binance,
            htxKlinesText to htx,
            bitunixKlinesText to bitunix,
        ).forEach { (text, adapter) ->
            val parsed = adapter.parseKlines(text, 60)
            assertTrue("解析不出数据：${adapter::class}", parsed.isNotEmpty())
            assertEquals(
                adapter::class.toString(),
                parsed.map { it.openTime }.sorted(),
                parsed.map { it.openTime },
            )
        }
    }

    @Test
    fun `每个方言的每根蜡烛都带正价格与收盘时间`() {
        listOf(
            okxKlinesText to okx,
            bybitKlinesText to bybit,
            bitgetKlinesText to bitget,
            gateKlinesText to gate,
            mexcKlinesText to mexc,
            hyperKlinesText to hyper,
            binanceKlinesText to binance,
            htxKlinesText to htx,
            bitunixKlinesText to bitunix,
        ).forEach { (text, adapter) ->
            adapter.parseKlines(text, 60).forEach { k ->
                assertBdPositive(k.high)
                assertBdPositive(k.low)
                assertTrue("high >= low", k.high >= k.low)
                assertTrue("closeTime > openTime", k.closeTime > k.openTime)
            }
        }
    }

    /* ───────────────────── 币安同构 ───────────────────── */

    @Test
    fun `币安同构沿用 fapi 路径与官方周期码`() {
        assertEquals(
            "https://fapi.asterdex.com/fapi/v1/ping",
            binance.probe("https://fapi.asterdex.com").url,
        )
        assertEquals(
            "https://x/fapi/v1/klines?symbol=BTCUSDT&interval=4h&limit=500&startTime=1000&endTime=2000",
            binance.klines("https://x", "BTCUSDT", 240, 500, 1_000L, 2_000L).url,
        )
        assertEquals("https://x/fapi/v1/ticker/24hr?symbol=BTCUSDT", binance.ticker("https://x", "BTCUSDT").url)
        assertEquals("https://x/fapi/v1/exchangeInfo", binance.exchangeInfo("https://x").url)
        assertNull(binance.klines("https://x", "BTCUSDT", 240, 500, null, null).postBody)
    }

    @Test
    fun `币安同构的 limit 会被压到单次上限`() {
        val url = binance.klines("https://x", "BTCUSDT", 1, 99_999, null, null).url
        assertTrue(url, url.contains("limit=${binance.maxKlineLimit}"))
    }

    @Test
    fun `币安同构解析复用现成 DTO`() {
        val klines = binance.parseKlines(binanceKlinesText, 1)
        assertEquals(2, klines.size)
        assertBd("100.5", klines[0].open)
        assertBd("20.12", klines[0].volume)
        // 币安原生 closeTime 是「收盘时刻」而非 openTime+周期-1，直接采用
        assertEquals(1_700_000_059_999L, klines[0].closeTime)

        val ticker = binance.parseTicker(binanceTickerText, "BTCUSDT", 9_999L)!!
        assertBd("85000.1", ticker.lastPrice)
        assertBd("84000.0", ticker.openPrice)
        assertEquals(9_999L, ticker.updatedAt)
        assertEquals(SymbolId(MarketType.FUTURES, "BTCUSDT"), ticker.id)
        assertTrue(ticker.changePercent > 1.0)
    }

    /* ───────────────────── OKX ───────────────────── */

    @Test
    fun `OKX 用 -USDT-SWAP 后缀，翻页方向与币安 endTime 对齐`() {
        assertEquals(
            "https://www.okx.com/api/v5/public/time",
            okx.probe("https://www.okx.com").url,
        )
        val url = okx.klines("https://x", "BTCUSDT", 240, 500, 1_000L, 2_000L).url
        assertTrue(url, url.startsWith("https://x/api/v5/market/candles?"))
        assertTrue(url, url.contains("instId=BTC-USDT-SWAP"))
        assertTrue(url, url.contains("bar=4H"))
        // after=「只要更早的」对应 endTime，before=「只要更新的」对应 startTime
        assertTrue(url, url.contains("after=2000"))
        assertTrue(url, url.contains("before=1000"))
        assertTrue(url, url.contains("limit=${okx.maxKlineLimit}"))
        assertTrue(url, !url.contains("limit=500&"))
    }

    @Test
    fun `OKX 蜡烛：倒序响应转升序，成交量取基础币与计价币两列`() {
        val klines = okx.parseKlines(okxKlinesText, 60)
        assertEquals(listOf(1_700_000_000_000L, 1_700_003_600_000L), klines.map { it.openTime })
        assertBd("85000", klines.first().open)
        assertBd("86000", klines.first().close)
        assertBd("12.5", klines.first().volume)       // volCcy（基础币）
        assertBd("1062500", klines.first().quoteVolume) // volCcyQuote
        assertEquals(1_700_003_599_999L, klines.first().closeTime) // 无原生收盘字段 → 按周期推算
    }

    @Test
    fun `OKX 业务错误码要抛异常，不能当成空数据`() {
        val e = assertThrowsIo {
            okx.parseKlines("""{"code":"51001","msg":"param bar invalid","data":[]}""", 60)
        }
        assertTrue(e.message ?: "", e.message!!.contains("51001"))
    }

    @Test
    fun `OKX ticker：只有基础币量时按最新价折出计价量`() {
        val t = okx.parseTicker(okxTickerText, "BTCUSDT", 7L)!!
        assertBd("85500.5", t.lastPrice)
        assertBd("84000.0", t.openPrice)
        assertBd("71000", t.volume)
        assertBd(t.quoteVolume, BigDecimal("71000").multiply(BigDecimal("85500.5")))
        assertEquals(7L, t.updatedAt)
    }

    @Test
    fun `OKX 交易对：只收 USDT 永续，state 非 live 记为 CLOSE`() {
        val metas = okxExchangeInfo()
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), metas.map { it.id.symbol })
        assertBd("0.1", metas.first().priceTickSize)
        assertBd("0.001", metas.first().quantityStepSize)
        assertEquals("TRADING", metas.first().status)
        assertEquals("CLOSE", metas.last().status)
    }

    /* ───────────────────── Bybit ───────────────────── */

    @Test
    fun `Bybit 走 linear 分类，周期用分钟数原样表达`() {
        val url = bybit.klines("https://x", "BTCUSDT", 240, 500, 1_000L, 2_000L).url
        assertTrue(url, url.startsWith("https://x/v5/market/kline?category=linear&symbol=BTCUSDT"))
        assertTrue(url, url.contains("interval=240"))
        assertTrue(url, url.contains("start=1000") && url.contains("end=2000"))
        assertTrue(bybit.ticker("https://x", "BTCUSDT").url.contains("category=linear"))
        assertTrue(bybit.exchangeInfo("https://x").url.contains("limit=1000"))
    }

    @Test
    fun `Bybit 蜡烛：第 6、7 列是基础量与成交额`() {
        val klines = bybit.parseKlines(bybitKlinesText, 60)
        assertEquals(2, klines.size)
        assertBd("85000", klines.first().open)
        assertBd("12.5", klines.first().volume)
        assertBd("1062500", klines.first().quoteVolume)
    }

    @Test
    fun `Bybit 无 24h 开盘字段，用 prevPrice24h 作涨跌幅基准`() {
        val t = bybit.parseTicker(bybitTickerText, "BTCUSDT", 3L)!!
        assertBd("85500.5", t.lastPrice)
        assertBd("84000.0", t.openPrice)
        assertBd("1700000", t.quoteVolume)
    }

    @Test
    fun `Bybit 交易对：过滤非 USDT 与非永续，tick 缺失时回落 priceScale`() {
        val metas = bybitExchangeInfo()
        assertEquals(listOf("BTCUSDT", "SOLUSDT"), metas.map { it.id.symbol })
        assertBd("0.1", metas.first().priceTickSize)
        // SOL 无 priceFilter，用 priceScale=2 → 0.01
        assertBd("0.01", metas.last().priceTickSize)
        assertBd("0.001", metas.first().quantityStepSize)
    }

    /* ───────────────────── Bitget ───────────────────── */

    @Test
    fun `Bitget 用 productType 与 1h 小写周期码`() {
        val url = bitget.klines("https://x", "BTCUSDT", 60, 500, null, null).url
        assertTrue(url, url.startsWith("https://x/api/v2/mix/market/candles?symbol=BTCUSDT"))
        assertTrue(url, url.contains("productType=usdt-futures"))
        assertTrue(url, url.contains("granularity=1h"))
        assertTrue(url, !url.contains("startTime"))
    }

    @Test
    fun `Bitget 价格精度 = priceEndStep × 10^-pricePlace`() {
        val metas = bitgetExchangeInfo()
        // place=1、endStep=1 → 0.1
        assertBd("0.1", metas.first().priceTickSize)
        assertBd("0.001", metas.first().quantityStepSize)
        assertEquals("TRADING", metas.first().status)
    }

    @Test
    fun `Bitget 单标的 ticker 数组与对象两种包装都认`() {
        val array = """{"code":"00000","data":[{"lastPr":"85500.5","open24h":"84000.0","baseVolume":"71000","quoteVolume":"1700000"}]}"""
        val obj = """{"code":"00000","data":{"lastPr":"85500.5","open24h":"84000.0","baseVolume":"71000","quoteVolume":"1700000"}}"""
        listOf(array, obj).forEach {
            val t = bitget.parseTicker(it, "BTCUSDT", 5L)!!
            assertBd("85500.5", t.lastPrice)
            assertBd("84000.0", t.openPrice)
        }
    }

    /* ───────────────────── Gate ───────────────────── */

    @Test
    fun `Gate 毫秒与秒换算，探测只取单个合约`() {
        assertEquals(
            "https://api.gateio.ws/api/v4/futures/usdt/contracts/BTC_USDT",
            gate.probe("https://api.gateio.ws").url,
        )
        val url = gate.klines("https://x", "BTCUSDT", 480, 500, 2_000_000L, 3_000_000L).url
        assertTrue(url, url.startsWith("https://x/api/v4/futures/usdt/candlesticks?contract=BTC_USDT"))
        assertTrue(url, url.contains("interval=8h"))
        assertTrue(url, url.contains("from=2000") && url.contains("to=3000"))
    }

    @Test
    fun `Gate 蜡烛：时间是秒、sum 是计价额，基础量按收盘价折算`() {
        val klines = gate.parseKlines(gateKlinesText, 60)
        assertEquals(2, klines.size)
        assertEquals(listOf(1_700_000_000_000L, 1_700_003_600_000L), klines.map { it.openTime })
        assertBd("200", klines.first().quoteVolume)
        assertBd("2", klines.first().volume) // sum/close = 200/100
    }

    @Test
    fun `Gate 无 24h 开盘字段，用 last - change_price 还原`() {
        val t = gate.parseTicker(gateTickerText, "BTCUSDT", 11L)!!
        assertBd("85521.1", t.lastPrice)
        assertBd("85742.3", t.openPrice) // 85521.1 - (-221.2)
        assertBd("87237", t.highPrice)
        assertBd("71039", t.volume)
        assertBd("6124904252", t.quoteVolume)
    }

    @Test
    fun `Gate 合约名去下划线，in_delisting 记为 CLOSE`() {
        val metas = gateExchangeInfo()
        assertEquals("BTCUSDT", metas.first().id.symbol)
        assertEquals("BTC", metas.first().baseAsset)
        assertBd("0.1", metas.first().priceTickSize)   // order_price_round
        assertBd("1", metas.first().quantityStepSize)  // order_size_round
        assertEquals("CLOSE", metas.last().status)
    }

    /* ───────────────────── MEXC ───────────────────── */

    @Test
    fun `MEXC 无 limit 参数，用 limit×周期 倒推时间窗`() {
        assertEquals(
            "https://contract.mexc.com/api/v1/contract/ping",
            mexc.probe("https://contract.mexc.com").url,
        )
        val url = mexc.klines("https://x", "BTCUSDT", 60, 100, null, 1_800_000_000_000L).url
        assertTrue(url, url.startsWith("https://x/api/v1/contract/kline/BTC_USDT?"))
        assertTrue(url, url.contains("interval=Min60"))
        assertTrue(url, !url.contains("limit="))
        // end=1.8e12s，窗口 = 100 × 60min
        assertTrue(url, url.contains("end=1800000000"))
        assertTrue(url, url.contains("start=${1_800_000_000L - 100 * 3_600L}"))
    }

    @Test
    fun `MEXC 蜡烛是列存平行数组，按行号对齐`() {
        val klines = mexc.parseKlines(mexcKlinesText, 60)
        assertEquals(2, klines.size)
        assertBd("85000", klines.first().open)
        assertBd("86200", klines.last().close)
        assertBd("172000", klines.first().quoteVolume)
        assertBd("2", klines.first().volume) // amount/close = 172000/86000
        assertEquals(listOf(1_700_000_000_000L, 1_700_003_600_000L), klines.map { it.openTime })
    }

    @Test
    fun `MEXC 业务码非 0 或 success=false 要抛异常`() {
        assertThrowsIo { mexc.parseKlines("""{"code":7,"success":false,"message":"bad"}""", 60) }
    }

    @Test
    fun `MEXC ticker：amount24 是计价额，开盘用 last - riseFallValue`() {
        val t = mexc.parseTicker(mexcTickerText, "BTCUSDT", 13L)!!
        assertBd("85500", t.lastPrice)
        assertBd("85000", t.openPrice) // 85500 - 500
        assertBd("86000", t.highPrice)
        assertBd("84000", t.lowPrice)
        assertBd("171000", t.quoteVolume)
    }

    @Test
    fun `MEXC 交易对：只留 USDT 结算，state 非 0 记为 CLOSE`() {
        val metas = mexcExchangeInfo()
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), metas.map { it.id.symbol })
        assertBd("0.1", metas.first().priceTickSize)     // priceUnit
        assertBd("0.01", metas.first().quantityStepSize) // volUnit
        assertEquals("TRADING", metas.first().status)
        assertEquals("CLOSE", metas.last().status)       // state=3 已下架
    }

    /* ───────────────────── Hyperliquid ───────────────────── */

    @Test
    fun `Hyperliquid 一切走 POST info，GET 路径不存在`() {
        val probe = hyper.probe("https://api.hyperliquid.xyz")
        assertEquals("https://api.hyperliquid.xyz/info", probe.url)
        assertEquals("""{"type":"meta"}""", probe.postBody)

        val req = hyper.klines("https://x", "BTCUSDT", 60, 500, 1_000L, 2_000L)
        assertEquals("https://x/info", req.url)
        assertTrue(req.postBody ?: "", req.postBody!!.contains(""""coin":"BTC""""))
        assertTrue(req.postBody ?: "", req.postBody!!.contains(""""interval":"1h""""))
        assertTrue(req.postBody ?: "", req.postBody!!.contains(""""startTime":1000"""))
        assertTrue(req.postBody ?: "", req.postBody!!.contains(""""endTime":2000"""))
    }

    @Test
    fun `Hyperliquid 蜡烛只有基础量，计价量按收盘价折`() {
        val klines = hyper.parseKlines(hyperKlinesText, 60)
        assertEquals(3, klines.size)
        assertBd("1837.9494", klines.first().volume)
        assertBd(klines.first().quoteVolume, BigDecimal("1837.9494").multiply(BigDecimal("86396.0")))
        assertEquals(21_111L, klines.first().trades)
        // 原生 T 优先于按周期推算
        assertEquals(1_790_085_599_999L, klines.first().closeTime)
    }

    @Test
    fun `Hyperliquid 的 24h 快照由 1h 蜡烛折叠：首根开盘、逐根累加量`() {
        val t = hyper.parseTicker(hyperKlinesText, "BTCUSDT", 17L)!!
        assertBd("86453.0", t.lastPrice)          // 最后一根收盘
        assertBd("86085.0", t.openPrice)          // 最早一根开盘
        assertBd("86762.0", t.highPrice)
        assertBd("85483.0", t.lowPrice)
        assertBd(
            t.volume,
            BigDecimal("1837.9494").add(BigDecimal("2852.14151")).add(BigDecimal("1940.5")),
        )
        assertEquals(17L, t.updatedAt)
    }

    @Test
    fun `Hyperliquid 无 24h 数据时给出空而不是假快照`() {
        assertNull(hyper.parseTicker("[]", "BTCUSDT", 1L))
    }

    @Test
    fun `Hyperliquid 交易对按 szDecimals 推 tick，下架合约记为 CLOSE`() {
        val metas = hyperExchangeInfo()
        assertEquals(listOf("BTCUSDT", "HYPEUSDT"), metas.map { it.id.symbol })
        // szDecimals=5 → 最多 1 位小数
        assertBd("0.1", metas.first().priceTickSize)
        assertBd("0.00001", metas.first().quantityStepSize)
        assertEquals("TRADING", metas.first().status)
        assertEquals("CLOSE", metas.last().status)
    }

    /* ───────────────────── HTX（火币） ───────────────────── */

    @Test
    fun `HTX 行情走 linear-swap-ex，元数据走 linear-swap-api，合约名带连字符`() {
        assertEquals(
            "https://api.hbdm.com/linear-swap-api/v1/swap_contract_info?contract_code=BTC-USDT",
            htx.probe("https://api.hbdm.com").url,
        )
        val kline = htx.klines("https://x", "BTCUSDT", 240, 500, null, null).url
        assertEquals(
            "https://x/linear-swap-ex/market/history/kline?contract_code=BTC-USDT&period=4hour&size=500",
            kline,
        )
        assertEquals(
            "https://x/linear-swap-ex/market/detail/merged?contract_code=BTC-USDT",
            htx.ticker("https://x", "BTCUSDT").url,
        )
        assertEquals(
            "https://x/linear-swap-api/v1/swap_contract_info",
            htx.exchangeInfo("https://x").url,
        )
    }

    @Test
    fun `HTX 不传时间窗参数（实测被忽略，传了等于假装支持翻页）`() {
        val url = htx.klines("https://x", "BTCUSDT", 60, 300, 1_000L, 2_000L).url
        assertTrue(url, !url.contains("from="))
        assertTrue(url, !url.contains("to="))
        assertTrue(url, !url.contains("start="))
        assertTrue(url, !url.contains("end="))
    }

    @Test
    fun `HTX 蜡烛：秒转毫秒，amount 是基础量、trade_turnover 是计价额`() {
        val klines = htx.parseKlines(htxKlinesText, 60)
        assertEquals(3, klines.size)
        assertEquals(
            listOf(1_790_677_380_000L, 1_790_677_440_000L, 1_790_677_500_000L),
            klines.map { it.openTime },
        )
        assertBd("84049.9", klines.first().open)
        assertBd("84053.1", klines.first().close)
        assertBd("84053.2", klines.first().high)
        assertBd("84036.3", klines.first().low)
        assertBd("2.736", klines.first().volume)          // amount
        assertBd("229963.474", klines.first().quoteVolume) // trade_turnover
        assertEquals(34L, klines.first().trades)           // count
        // 未完成的那根（全零）照收，是否收盘由仓库层盖章
        assertBd("84066.9", klines.last().close)
    }

    @Test
    fun `HTX 单标的 24h 用原生 open，不以最新价代位`() {
        val t = htx.parseTicker(htxTickerText, "BTCUSDT", 7L)!!
        assertBd("84066.9", t.lastPrice)
        assertBd("83351.7", t.openPrice)      // 原生 open，若缺失会退化成 =last，这里必须是真值
        assertBd("84331.5", t.highPrice)
        assertBd("82743.8", t.lowPrice)
        assertBd("6755.042", t.volume)
        assertBd("563758895.416", t.quoteVolume)
        assertEquals(7L, t.updatedAt)
    }

    @Test
    fun `HTX 业务码在 status 里，非 ok 要抛异常`() {
        val e = assertThrowsIo { htx.parseKlines(htxBadSizeText, 60) }
        assertTrue(e.message ?: "", e.message!!.contains("invalid-parameter"))
        assertThrowsIo { htx.parseTicker(htxBadSizeText, "BTCUSDT", 1L) }
    }

    @Test
    fun `HTX 交易对：contract_code 转规范符号，price_tick 去尾零`() {
        val metas = htxExchangeInfo()
        assertEquals(1, metas.size)
        assertEquals("BTCUSDT", metas.first().id.symbol)
        assertEquals("BTC", metas.first().baseAsset)     // 取自 symbol 字段而不是 contract_code
        assertBd("0.1", metas.first().priceTickSize)     // 0.100000000000000000 → 0.1
        assertEquals("TRADING", metas.first().status)    // contract_status=1
    }

    @Test
    fun `HTX 周期表是 14 个实测白名单，不含 1year`() {
        assertEquals(14, htx.intervalLadder.size)
        assertEquals("1min", htx.intervalLadder[1L])
        assertEquals("60min", htx.intervalLadder[60L])
        assertEquals("1day", htx.intervalLadder[1_440L])
        assertTrue("1year 实测被拒", htx.intervalLadder.values.none { it == "1year" })
        assertEquals(2000, htx.maxKlineLimit)
        // limit 越界由方言自己压回上限
        assertTrue(htx.klines("https://x", "BTCUSDT", 1, 99_999, null, null).url.contains("size=2000"))
    }

    /* ───────────────────── Bitunix ───────────────────── */

    @Test
    fun `Bitunix 单标的快照走 tickers 的复数参数`() {
        assertEquals(
            "https://fapi.bitunix.com/api/v1/futures/market/time",
            bitunix.probe("https://fapi.bitunix.com").url,
        )
        // 实测 `ticker?symbol=` 是 404，必须用 `tickers?symbols=`
        assertEquals(
            "https://x/api/v1/futures/market/tickers?symbols=BTCUSDT",
            bitunix.ticker("https://x", "BTCUSDT").url,
        )
        assertEquals(
            "https://x/api/v1/futures/market/trading_pairs",
            bitunix.exchangeInfo("https://x").url,
        )
    }

    @Test
    fun `Bitunix 蜡烛原生降序，解析后转升序；量取 baseVol 与 quoteVol`() {
        val klines = bitunix.parseKlines(bitunixKlinesText, 60)
        assertEquals(3, klines.size)
        assertEquals(
            listOf(1_790_675_220_000L, 1_790_675_280_000L, 1_790_675_340_000L),
            klines.map { it.openTime },
        )
        assertBd("83992.8", klines.first().open)
        assertBd("84095.3", klines.first().close)
        assertBd("64.761", klines.first().volume)          // baseVol
        assertBd("5443056.23713", klines.first().quoteVolume) // quoteVol
    }

    @Test
    fun `Bitunix limit 上限 200，越界会被压回`() {
        assertEquals(200, bitunix.maxKlineLimit)
        val url = bitunix.klines("https://x", "BTCUSDT", 60, 1000, null, null).url
        assertTrue(url, url.contains("limit=200"))
    }

    @Test
    fun `Bitunix 非法周期返 code 0 加空数组——解析结果是空而不是异常`() {
        // 这是这家最容易误判的地方：接口不报错，只是给个空数组，
        // 所以「没抛异常」不能当成「周期被支持」，白名单由 intervalLadder 负责
        val parsed = bitunix.parseKlines(bitunixEmptyDataText, 60)
        assertEquals(0, parsed.size)
        assertNull(bitunix.parseTicker(bitunixEmptyDataText, "BTCUSDT", 1L))
    }

    @Test
    fun `Bitunix 单标的 24h：lastPrice 与原生 open`() {
        val t = bitunix.parseTicker(bitunixTickerText, "BTCUSDT", 3L)!!
        assertBd("84221.9", t.lastPrice)
        assertBd("82693.9", t.openPrice)
        assertBd("84349.7", t.highPrice)
        assertBd("82500.5", t.lowPrice)
        assertBd("26573.4995", t.volume)
        assertBd("2217493295.14546", t.quoteVolume)
    }

    @Test
    fun `Bitunix 精度取 quotePrecision，非 USDT 与非 OPEN 都滤掉`() {
        val metas = bitunixExchangeInfo()
        assertEquals(listOf("BTCUSDT", "DOGEUSDT", "PEPEUSDT"), metas.map { it.id.symbol })
        assertBd("0.1", metas[0].priceTickSize)        // quotePrecision=1
        assertBd("0.00001", metas[1].priceTickSize)    // 5
        assertBd("0.0000001", metas[2].priceTickSize)  // 7
        assertEquals("TRADING", metas.first().status)
    }

    @Test
    fun `Bitunix 周期表 15 个，1M 是日历月`() {
        assertEquals(15, bitunix.intervalLadder.size)
        assertEquals("1m", bitunix.intervalLadder[1L])
        assertEquals("1h", bitunix.intervalLadder[60L])
        assertEquals("8h", bitunix.intervalLadder[480L])   // 币安系也有的 8h
        assertEquals("1M", bitunix.intervalLadder[43_200L])
    }

    /* ───────────────────── 样本 ───────────────────── */

    /** BigDecimal 的 equals 把标度也算进去（"1" != "1.0"），断言一律用 compareTo。 */
    private fun assertBd(expected: String, actual: BigDecimal) {
        val want = BigDecimal(expected)
        assertTrue("期望 $want 实得 $actual", actual.compareTo(want) == 0)
    }

    private fun assertBd(actual: BigDecimal, expected: BigDecimal) {
        assertTrue("期望 $expected 实得 $actual", actual.compareTo(expected) == 0)
    }

    private fun assertBdPositive(value: BigDecimal) {
        assertTrue("应为正数，实得 $value", value.signum() > 0)
    }

    private inline fun assertThrowsIo(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError(" expected IOException")
    }

    private val binanceKlinesText = """
        [
          [1700000000000,"100.5","101.2","99.8","100.9","20.12",1700000059999,"2030.5",321,"10.1","1020.3","0"],
          [1700003600000,"100.9","102.0","100.1","101.5","18.4",1700003659999,"1860",220,"9.9","999","0"]
        ]
    """.trimIndent()

    private val binanceTickerText = """
        {"symbol":"BTCUSDT","lastPrice":"85000.1","openPrice":"84000.0","highPrice":"86000",
         "lowPrice":"83000","volume":"71000","quoteVolume":"6000000000","closeTime":1700000000000}
    """.trimIndent()

    private val binanceExchangeInfoText = """
        {"symbols":[
          {"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","status":"TRADING",
           "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.001"}]},
          {"symbol":"ETHUSDT","baseAsset":"ETH","quoteAsset":"USDT","status":"TRADING",
           "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.01"},{"filterType":"LOT_SIZE","stepSize":"0.01"}]}
        ]}
    """.trimIndent()

    private fun binanceExchangeInfo() = binance.parseExchangeInfo(binanceExchangeInfoText)

    private val okxKlinesText = """
        {"code":"0","msg":"","data":[
          ["1700003600000","86000","86500","85800","86200","14","14","1207000","1"],
          ["1700000000000","85000","86100","84900","86000","12.5","12.5","1062500","0"]
        ]}
    """.trimIndent()

    private val okxTickerText = """
        {"code":"0","data":[
          {"instType":"SWAP","instId":"BTC-USDT-SWAP","last":"85500.5","open24h":"84000.0",
           "high24h":"86500","low24h":"83900","vol24h":"71","volCcy24h":"71000"}
        ]}
    """.trimIndent()

    private val okxInstrumentsText = """
        {"code":"0","data":[
          {"instId":"BTC-USDT-SWAP","baseCcy":"BTC","quoteCcy":"USDT","tickSz":"0.1","lotSz":"0.001","state":"live"},
          {"instId":"ETH-USDT-SWAP","baseCcy":"ETH","quoteCcy":"USDT","tickSz":"0.01","lotSz":"0.01","state":"fill"},
          {"instId":"BTC-USD-SWAP","baseCcy":"BTC","quoteCcy":"USD","tickSz":"0.1","lotSz":"1","state":"live"},
          {"instId":"BTC-USDT","baseCcy":"BTC","quoteCcy":"USDT","tickSz":"0.1","lotSz":"0.001","state":"live"}
        ]}
    """.trimIndent()

    private fun okxExchangeInfo() = okx.parseExchangeInfo(okxInstrumentsText)

    private val bybitKlinesText = """
        {"retCode":0,"retMsg":"OK","result":{"category":"linear","symbol":"BTCUSDT","list":[
          ["1700003600000","86000","86500","85800","86200","14","1207000"],
          ["1700000000000","85000","86100","84900","86000","12.5","1062500"]
        ]}}
    """.trimIndent()

    private val bybitTickerText = """
        {"retCode":0,"result":{"category":"linear","list":[
          {"symbol":"BTCUSDT","lastPrice":"85500.5","highPrice24h":"86500","lowPrice24h":"83900",
           "prevPrice24h":"84000.0","volume24h":"71000","turnover24h":"1700000"}
        ]}}
    """.trimIndent()

    private val bybitInstrumentsText = """
        {"retCode":0,"result":{"list":[
          {"symbol":"BTCUSDT","baseCoin":"BTC","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"Trading",
           "priceFilter":{"tickSize":"0.10"},"lotSizeFilter":{"qtyStep":"0.001"}},
          {"symbol":"SOLUSDT","baseCoin":"SOL","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"Trading",
           "priceScale":"2","lotSizeFilter":{"qtyStep":"0.1"}},
          {"symbol":"BTCUSDT-26SEP26","baseCoin":"BTC","quoteCoin":"USDT","contractType":"FlipContract","status":"Trading"},
          {"symbol":"ETHUSD","baseCoin":"ETH","quoteCoin":"USD","contractType":"LinearPerpetual","status":"Trading"}
        ]}}
    """.trimIndent()

    private fun bybitExchangeInfo() = bybit.parseExchangeInfo(bybitInstrumentsText)

    private val bitgetKlinesText = """
        {"code":"00000","data":[
          ["1700000000000","85000","86100","84900","86000","12.5","1062500"],
          ["1700003600000","86000","86500","85800","86200","14","1207000"]
        ]}
    """.trimIndent()

    private val bitgetContractsText = """
        {"code":"00000","data":[
          {"symbol":"BTCUSDT","baseCoin":"BTC","quoteCoin":"USDT","pricePlace":"1","priceEndStep":"1",
           "sizeMultiplier":"0.001","symbolStatus":"listed","takerFeeRate":"0.0006"},
          {"symbol":"ETHUSD","baseCoin":"ETH","quoteCoin":"USD","pricePlace":"2","priceEndStep":"1",
           "sizeMultiplier":"0.01","symbolStatus":"listed"}
        ]}
    """.trimIndent()

    private fun bitgetExchangeInfo() = bitget.parseExchangeInfo(bitgetContractsText)

    private val gateKlinesText = """
        [
          {"t":1700000000,"o":"100","h":"101","l":"99","c":"100","v":33382723,"sum":"200"},
          {"t":1700003600,"o":"100","h":"102","l":"100","c":"101","v":38297796,"sum":"386.8"}
        ]
    """.trimIndent()

    private val gateTickerText = """
        [{"contract":"BTC_USDT","last":"85521.1","change_price":"-221.2","high_24h":"87237","low_24h":"85232",
          "volume_24h_base":"71039","volume_24h_quote":"6124904252","mark_price":"85537.95"}]
    """.trimIndent()

    private val gateContractsText = """
        [
          {"name":"BTC_USDT","baseCoin":"BTC","order_price_round":"0.1","order_size_round":"1",
           "in_delisting":false,"status":"trading","quanto_multiplier":"0.0001"},
          {"name":"ETH_USDT","baseCoin":"ETH","order_price_round":"0.01","order_size_round":"0.01",
           "in_delisting":true,"status":"trading"}
        ]
    """.trimIndent()

    private fun gateExchangeInfo() = gate.parseExchangeInfo(gateContractsText)

    private val mexcKlinesText = """
        {"code":0,"success":true,"message":"ok","data":{
          "time":[1700000000,1700003600],
          "open":[85000,86000],
          "close":[86000,86200],
          "high":[86100,86500],
          "low":[84900,85800],
          "vol":[2000000,2100000],
          "amount":[172000,386.8]
        }}
    """.trimIndent()

    private val mexcTickerText = """
        {"code":0,"success":true,"data":{"symbol":"BTC_USDT","lastPrice":85500,"riseFallValue":500,
         "high24Price":86000,"lower24Price":84000,"amount24":171000,"volume24":2000000}}
    """.trimIndent()

    private val mexcDetailText = """
        {"code":0,"success":true,"data":[
          {"symbol":"BTC_USDT","baseCoin":"BTC","settleCoin":"USDT","priceUnit":0.1,"volUnit":0.01,"state":0,"isActive":1},
          {"symbol":"BTC_BTC","baseCoin":"BTC","settleCoin":"BTC","priceUnit":0.000001,"volUnit":1,"state":0},
          {"symbol":"ETH_USDT","baseCoin":"ETH","settleCoin":"USDT","priceUnit":0.01,"volUnit":0.01,"state":3}
        ]}
    """.trimIndent()

    private fun mexcExchangeInfo() = mexc.parseExchangeInfo(mexcDetailText)

    private val hyperKlinesText = """
        [
          {"t":1790082000000,"T":1790085599999,"s":"BTC","i":"1h","o":"86085.0","c":"86396.0","h":"86435.0","l":"85600.0","v":"1837.9494","n":21111},
          {"t":1790085600000,"T":1790089199999,"s":"BTC","i":"1h","o":"86396.0","c":"86316.0","h":"86499.0","l":"85483.0","v":"2852.14151","n":28603},
          {"t":1790089200000,"T":1790092799999,"s":"BTC","i":"1h","o":"86316.0","c":"86453.0","h":"86762.0","l":"86163.0","v":"1940.5","n":19000}
        ]
    """.trimIndent()

    private val hyperMetaText = """
        {"universe":[
          {"name":"BTC","szDecimals":5,"maxLeverage":50},
          {"name":"HYPE","szDecimals":2,"maxLeverage":10,"isDelisted":true}
        ]}
    """.trimIndent()

    private fun hyperExchangeInfo() = hyper.parseExchangeInfo(hyperMetaText)

    /* —— HTX：以下均为 2026-09-29 的**真实响应原文**（本机域名被 DNS 污染，经中转取回）—— */

    private val htxKlinesText = """
        {"ch":"market.BTC-USDT.kline.1min","ts":1790677523089,"status":"ok","data":[
          {"id":1790677380,"open":84049.9,"close":84053.1,"high":84053.2,"low":84036.3,"amount":2.736,"vol":2736,"trade_turnover":229963.474,"count":34},
          {"id":1790677440,"open":84053.1,"close":84066.9,"high":84067.1,"low":84053.1,"amount":1.508,"vol":1508,"trade_turnover":126770.8556,"count":24},
          {"id":1790677500,"open":84066.9,"close":84066.9,"high":84066.9,"low":84066.9,"amount":0.0,"vol":0,"trade_turnover":0.0,"count":0}
        ]}
    """.trimIndent()

    private val htxTickerText = """
        {"ch":"market.BTC-USDT.detail.merged","status":"ok","tick":
          {"amount":"6755.042","ask":[84058.3,4130],"bid":[84058.2,575],"close":"84066.9","count":57923,
           "high":"84331.5","id":1790677503,"low":"82743.8","open":"83351.7",
           "trade_turnover":"563758895.416","ts":1790677503392,"vol":"6755042"},"ts":1790677503392}
    """.trimIndent()

    private val htxContractText = """
        {"status":"ok","data":[
          {"symbol":"BTC","contract_code":"BTC-USDT","contract_size":0.001000000000000000,
           "price_tick":0.100000000000000000,"delivery_date":"","delivery_time":"","create_date":"20201021",
           "contract_status":1,"adjust":[],"price_estimated":[],"settlement_date":"1790697600000",
           "support_margin_mode":"all","open_type":0,"settlement_period":"8","labels":["hot","common"],
           "tradfi_labels":[],"enable_rpi":true,"business_type":"swap","pair":"BTC-USDT",
           "contract_type":"swap","trade_partition":"USDT"}
        ],"ts":1790677524306}
    """.trimIndent()

    /** 请求 size=5000 时的真实回包：`invalid size`（业务码在 status 里，HTTP 仍是 200）。 */
    private val htxBadSizeText =
        """{"ts":1790677541023,"status":"error","err-code":"invalid-parameter","err-msg":"invalid size"}"""

    private fun htxExchangeInfo() = htx.parseExchangeInfo(htxContractText)

    /* —— Bitunix：以下均为 2026-09-29 的真实响应原文 —— */

    /** 原生 **降序**（newest-first），故意保持这个顺序来验证适配器会倒过来。 */
    private val bitunixKlinesText = """
        {"code":0,"msg":"Success","data":[
          {"open":"84101.7","high":"84167.8","low":"84071.8","close":"84166.7","quoteVol":"3226465.06406","baseVol":"38.3557","time":"1790675340000"},
          {"open":"84095.3","high":"84120","low":"84073.2","close":"84101.7","quoteVol":"1845214.46034","baseVol":"21.9425","time":"1790675280000"},
          {"open":"83992.8","high":"84115.9","low":"83992.7","close":"84095.3","quoteVol":"5443056.23713","baseVol":"64.761","time":"1790675220000"}
        ]}
    """.trimIndent()

    private val bitunixTickerText = """
        {"code":0,"msg":"Success","data":[
          {"symbol":"BTCUSDT","markPrice":"84222","lastPrice":"84221.9","open":"82693.9","last":"84209.9",
           "quoteVol":"2217493295.14546","baseVol":"26573.4995","high":"84349.7","low":"82500.5"}
        ]}
    """.trimIndent()

    private val bitunixPairsText = """
        {"code":0,"msg":"Success","data":[
          {"symbol":"BTCUSDT","base":"BTC","quote":"USDT","basePrecision":4,"quotePrecision":1,"symbolStatus":"OPEN","minTradeVolume":"0.0001"},
          {"symbol":"DOGEUSDT","base":"DOGE","quote":"USDT","basePrecision":0,"quotePrecision":5,"symbolStatus":"OPEN","minTradeVolume":"53"},
          {"symbol":"PEPEUSDT","base":"PEPE","quote":"USDT","basePrecision":0,"quotePrecision":7,"symbolStatus":"OPEN"},
          {"symbol":"BTCUSDC","base":"BTC","quote":"USDC","basePrecision":4,"quotePrecision":1,"symbolStatus":"OPEN"},
          {"symbol":"OLDUSDT","base":"OLD","quote":"USDT","basePrecision":2,"quotePrecision":3,"symbolStatus":"CLOSE"}
        ]}
    """.trimIndent()

    /** 非法 interval 的真实形态：**code 仍是 0**，只是 data 为空 —— 不报错、也不回落。 */
    private val bitunixEmptyDataText = """{"code":0,"msg":"Success","data":[]}"""

    private fun bitunixExchangeInfo() = bitunix.parseExchangeInfo(bitunixPairsText)
}
