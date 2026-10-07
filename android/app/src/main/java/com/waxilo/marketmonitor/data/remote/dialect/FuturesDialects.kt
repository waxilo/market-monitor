package com.waxilo.marketmonitor.data.remote.dialect

import com.waxilo.marketmonitor.data.remote.MarketJson
import com.waxilo.marketmonitor.data.remote.dto.ExchangeInfoDto
import com.waxilo.marketmonitor.data.remote.dto.TickerDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.data.remote.dto.toKline
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.FuturesDialect
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.OpenInterestPoint
import com.waxilo.marketmonitor.domain.model.SymbolId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.math.BigDecimal

/**
 * 合约行情方言适配器（PRD 3.2 多盘口接入）。
 *
 * 每个 [FuturesDialect] 一个实现：负责「请求怎么发」与「响应怎么读」，
 * 对上只暴露 App 的通用形状——Kline / MarketTicker / InstrumentMeta 与规范符号（BTCUSDT）。
 * 所有纯函数（URL 构造、解析）都放得进 JVM 单测，不需要网络。
 *
 * 约定：
 * - 解析出的 Kline 一律按 openTime 升序返回；closeTime 由周期推算（有原生字段的用原生）。
 *   是否收盘不在这判定——仓库层按 closeTime 与当前时间统一盖章。
 * - 成交量口径尽量对齐币安：volume=基础币数量、quoteVolume=计价币金额；
 *   只有单侧数据时按「quote ≈ base × close」互相折算（展示语义，不参与价格计算）。
 * - 包装体里带业务错误码的（OKX/Bybit/Bitget/MEXC），解析前先查，非 0 直接抛 IOException。
 */
sealed interface FuturesDialectAdapter {

    /** 对外请求规格：postBody 非空则以 application/json POST 发出，否则 GET。 */
    data class Request(val url: String, val postBody: String? = null)

    /** 支持的 K 线周期：分钟数 → 该家的原生周期码。仓库层聚合时只从键集里挑基础周期。 */
    val intervalLadder: Map<Long, String>

    /** 单次 K 线请求的根数上限（超出会被接口截断，聚合倍率计算要按它封顶）。 */
    val maxKlineLimit: Int

    /**
     * 历史持仓量的方言规格（副图用）。先接了四家：币安同构 / OKX / Bybit / Gate。
     * 其余五家为 null：Hyperliquid 只有当前快照没有历史；MEXC / Bitget / HTX / Bitunix 未核实。
     */
    val openInterest: OpenInterestSpec? get() = null

    /**
     * 是否支持按时间窗取数（即「往更早方向翻页」）。
     *
     * 默认 true；只有实测确认接口会**无视**时间参数的家才覆盖成 false（目前是 HTX）。
     * 不支持时必须让上层停掉翻页：这类接口对任何时间窗都返回「最新 N 根」，
     * 上层按 openTime 去重后没有新增，继续翻只是无限重复请求同一批数据。
     */
    val supportsTimeWindow: Boolean get() = true

    fun probe(base: String): Request
    fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): Request

    fun ticker(base: String, symbol: String): Request
    fun exchangeInfo(base: String): Request

    fun parseKlines(text: String, minutes: Long): List<Kline>
    fun parseTicker(text: String, symbol: String, now: Long): MarketTicker?
    fun parseExchangeInfo(text: String): List<InstrumentMeta>
}

/**
 * 历史持仓量的方言规格。**与 K 线周期表是两张表**：支持面窄得多（都以 5m 起、
 * 点数/窗口各有上限），所以不并入 [FuturesDialectAdapter.intervalLadder]。
 */
data class OpenInterestSpec(
    /** 分钟数 → 该家的原生周期码。 */
    val ladder: Map<Long, String>,
    /** true = 值为**基础币口径**（Bybit）——对齐时按 K 线收盘价折美元，与其余三家同口径。 */
    val baseCoinValue: Boolean = false,
    /** 构造请求。点数上限由各家实现自己夹（币安 500 / Bybit 200 / Gate 1000）。 */
    val call: (base: String, symbol: String, minutes: Long, limit: Int) -> FuturesDialectAdapter.Request,
    /** 解析响应为升序的持仓量点（美元名义值，或 [baseCoinValue] 口径）。 */
    val parse: (text: String) -> List<OpenInterestPoint>,
)

object FuturesDialects {
    fun of(kind: FuturesDialect): FuturesDialectAdapter = when (kind) {
        FuturesDialect.BINANCE -> BinanceCompatibleDialect
        FuturesDialect.OKX -> OkxDialect
        FuturesDialect.BYBIT -> BybitDialect
        FuturesDialect.BITGET -> BitgetDialect
        FuturesDialect.GATE -> GateDialect
        FuturesDialect.MEXC -> MexcDialect
        FuturesDialect.HYPERLIQUID -> HyperliquidDialect
        FuturesDialect.HTX -> HtxDialect
        FuturesDialect.BITUNIX -> BitunixDialect
    }
}

// —————————————————————————— JSON 小工具 ——————————————————————————

private fun parse(text: String): JsonElement = MarketJson.DEFAULT.parseToJsonElement(text)

private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonArray.str(index: Int): String? = (this.getOrNull(index) as? JsonPrimitive)?.contentOrNull

private fun String?.bd(): BigDecimal? = this?.toBigDecimalOrNull()

private fun JsonElement.checkOkx() {
    val code = asObject().str("code")
    if (code != null && code != "0") throw IOException("OKX ${code}: ${asObject().str("msg")}")
}

private fun JsonElement.checkBybit() {
    val ret = asObject().str("retCode")?.toIntOrNull() ?: 0
    if (ret != 0) throw IOException("Bybit ${ret}: ${asObject().str("retMsg")}")
}

private fun JsonElement.checkBitget() {
    val code = asObject().str("code")
    if (code != null && code != "00000") throw IOException("Bitget ${code}: ${asObject().str("msg")}")
}

private fun JsonElement.checkMexc() {
    val obj = asObject()
    val ok = obj.str("success")?.toBooleanStrictOrNull()
    val code = obj.str("code")?.toIntOrNull() ?: 0
    if (ok == false || code != 0) throw IOException("MEXC ${code}: ${obj.str("message")}")
}

/**
 * HTX 的业务码在 `status` 里、**不在 HTTP 状态里**：非法 size 实测回的是
 * `HTTP 200 + {"status":"error","err-code":"invalid-parameter","err-msg":"invalid size"}`。
 */
private fun JsonElement.checkHtx() {
    val obj = asObject()
    val status = obj.str("status") ?: return
    if (status != "ok") {
        throw IOException("HTX ${obj.str("err-code").orEmpty()}: ${obj.str("err-msg") ?: status}")
    }
}

/** Bitunix 的业务码在 `code`（0 为成功）。 */
private fun JsonElement.checkBitunix() {
    val obj = asObject()
    val code = obj.str("code")?.toIntOrNull() ?: return
    if (code != 0) throw IOException("Bitunix $code: ${obj.str("msg")}")
}

/** 由开盘时间 + 周期推算收盘时间（没有原生收盘字段的都用它）。 */
private fun closeTimeOf(openTime: Long, minutes: Long): Long = openTime + minutes * 60_000L - 1

private fun candle(
    openTime: Long,
    minutes: Long,
    closeTime: Long = closeTimeOf(openTime, minutes),
    o: BigDecimal?,
    h: BigDecimal?,
    l: BigDecimal?,
    c: BigDecimal?,
    base: BigDecimal?,
    quote: BigDecimal?,
    trades: Long = 0,
): Kline? {
    if (o == null || h == null || l == null || c == null) return null
    return Kline(
        openTime = openTime,
        closeTime = closeTime,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = base ?: BigDecimal.ZERO,
        quoteVolume = quote ?: BigDecimal.ZERO,
        trades = trades,
    )
}

/** 只有单侧成交量时按收盘价折算另一侧（展示用近似）。 */
private fun quoteOf(base: BigDecimal?, close: BigDecimal?): BigDecimal? =
    base?.let { b -> close?.let { b.multiply(it) } }

private fun baseOf(quote: BigDecimal?, close: BigDecimal?): BigDecimal? =
    quote?.let { q -> close?.let { c -> if (c.signum() == 0) null else q.divide(c, java.math.MathContext.DECIMAL128) } }

private fun tickerOf(
    symbol: String,
    last: BigDecimal?,
    open: BigDecimal?,
    high: BigDecimal?,
    low: BigDecimal?,
    base: BigDecimal?,
    quote: BigDecimal?,
    now: Long,
): MarketTicker? {
    val lastPrice = last ?: return null
    return MarketTicker(
        id = SymbolId(MarketType.FUTURES, symbol),
        lastPrice = lastPrice,
        openPrice = open ?: lastPrice,
        highPrice = high ?: lastPrice,
        lowPrice = low ?: lastPrice,
        volume = base ?: BigDecimal.ZERO,
        quoteVolume = quote ?: BigDecimal.ZERO,
        updatedAt = now,
    )
}

private fun meta(
    symbol: String,
    base: String,
    quote: String,
    priceTick: BigDecimal?,
    qtyStep: BigDecimal?,
    trading: Boolean,
): InstrumentMeta? {
    val tick = priceTick ?: return null
    return InstrumentMeta(
        id = SymbolId(MarketType.FUTURES, symbol),
        baseAsset = base,
        quoteAsset = quote,
        priceTickSize = tick.stripTrailingZeros(),
        quantityStepSize = qtyStep?.stripTrailingZeros() ?: BigDecimal.ONE,
        status = if (trading) "TRADING" else "CLOSE",
    )
}

/** 规范符号（BTCUSDT，USDT 本位永续）→ 各家原生写法。 */
private fun stripUsdt(symbol: String): String =
    if (symbol.endsWith("USDT")) symbol.removeSuffix("USDT") else symbol

// —————————————————————————— 币安同构（Aster / 币安主域）——————————————————————————————————————

/**
 * 与 fapi.binance.com `/fapi/v1` 完全同构的接口（Aster 即此行），复用现成的 DTO 解析。
 *
 * 候选清单里只留了**币安主域 + Aster** 两家：币安主域和它那几个镜像域名在后端其实是同一批
 * 机器，一起通、一起挂，多列只会让弹窗里一片红，所以镜像已从清单移除（方言本身仍通用）。
 */
internal object BinanceCompatibleDialect : FuturesDialectAdapter {

    override val intervalLadder: Map<Long, String> =
        OfficialInterval.entries.associate { it.minutes to it.apiCode }
    override val maxKlineLimit = 1000

    /**
     * 历史持仓量：`/futures/data/openInterestHist`（与 fapi 同域、**不同前缀**，不在 /fapi/v1 下）。
     * 周期 5m/15m/30m/1h/2h/4h/6h/12h/1d，单次上限 500、深度约 30 天。
     * 取 `sumOpenInterestValue`（USDT 名义值）。
     */
    override val openInterest = OpenInterestSpec(
        ladder = mapOf(
            5L to "5m", 15L to "15m", 30L to "30m", 60L to "1h", 120L to "2h",
            240L to "4h", 360L to "6h", 720L to "12h", 1_440L to "1d",
        ),
        call = { base, symbol, minutes, limit ->
            FuturesDialectAdapter.Request(
                base.trimEnd('/') + "/futures/data/openInterestHist" +
                    "?symbol=$symbol&period=${requireNotNull(openInterest.ladder[minutes])}" +
                    "&limit=${limit.coerceIn(1, 500)}",
            )
        },
        parse = { text ->
            parse(text).asArray().mapNotNull { el ->
                val row = el.asObject()
                val time = row.str("timestamp")?.toLongOrNull() ?: return@mapNotNull null
                val value = row.str("sumOpenInterestValue")?.toDoubleOrNull() ?: return@mapNotNull null
                OpenInterestPoint(time, value)
            }.sortedBy { it.time }
        },
    )

    override fun probe(base: String) = req(base, "/ping")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("symbol=$symbol")
            add("interval=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            startMs?.let { add("startTime=$it") }
            endMs?.let { add("endTime=$it") }
        }
        return req(base, "/klines", query)
    }

    override fun ticker(base: String, symbol: String) =
        req(base, "/ticker/24hr", listOf("symbol=$symbol"))

    override fun exchangeInfo(base: String) = req(base, "/exchangeInfo")

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        parse(text).asArray().mapNotNull { row ->
            (row as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toKline()
        }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? =
        MarketJson.DEFAULT.decodeFromJsonElement(TickerDto.serializer(), parse(text))
            .toDomain(MarketType.FUTURES, now)

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        MarketJson.DEFAULT.decodeFromJsonElement(ExchangeInfoDto.serializer(), parse(text))
            .symbols.mapNotNull { it.toDomain(MarketType.FUTURES) }

    private fun req(
        base: String,
        path: String,
        query: List<String> = emptyList(),
    ): FuturesDialectAdapter.Request {
        val url = base.trimEnd('/') + MarketType.FUTURES.apiPrefix + path +
            (if (query.isEmpty()) "" else "?" + query.joinToString("&"))
        return FuturesDialectAdapter.Request(url)
    }
}

// —————————————————————————— OKX v5 ——————————————————————————

/**
 * OKX（www.okx.com）。SWAP 行的 K 线 vol=张数、volCcy=基础币、volCcyQuote=计价币；
 * 响应 newest-first，统一排序后返回。翻页语义是 after=「只要比该 ms 更早的」，
 * 与币安 endTime 语义一致。limit 上限 300，比币安小，深历史靠 hasMore 续翻。
 */
internal object OkxDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "1m", 3L to "3m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1H", 120L to "2H", 240L to "4H", 360L to "6H", 720L to "12H",
        1_440L to "1D", 4_320L to "3D", 10_080L to "1W", 43_200L to "1M",
    )
    override val maxKlineLimit = 300

    /**
     * 历史持仓量走 Rubik 统计：`/rubik/stat/contracts/open-interest-volume?ccy=BTC&period=5m`
     * —— **按币种聚合所有合约**（不是单合约），行 `[ts, oi, vol]`，oi 即美元口径。
     * ⚠️ 实测 `limit` / `begin` 都改不动返回条数：窗口固定在 ~575 点（5m 时约 2 天）。
     */
    override val openInterest = OpenInterestSpec(
        ladder = mapOf(5L to "5m", 60L to "1H", 1_440L to "1D"),
        call = { base, symbol, minutes, _ ->
            FuturesDialectAdapter.Request(
                base.trimEnd('/') + "/api/v5/rubik/stat/contracts/open-interest-volume" +
                    "?ccy=${stripUsdt(symbol)}&period=${requireNotNull(openInterest.ladder[minutes])}",
            )
        },
        parse = { text ->
            parse(text).also { it.checkOkx() }.asObject()["data"].asArray().mapNotNull { row ->
                val a = row as? JsonArray ?: return@mapNotNull null
                val time = a.str(0)?.toLongOrNull() ?: return@mapNotNull null
                val value = a.str(1)?.toDoubleOrNull() ?: return@mapNotNull null
                OpenInterestPoint(time, value)
            }.sortedBy { it.time }
        },
    )

    private fun inst(symbol: String) = stripUsdt(symbol) + "-USDT-SWAP"

    override fun probe(base: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/api/v5/public/time")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("instId=${inst(symbol)}")
            add("bar=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            // OKX 没有起止参数：after=「只要更早的」等价币安 endTime，before=「只要更新的」等价 startTime
            endMs?.let { add("after=$it") }
            startMs?.let { add("before=$it") }
        }
        return FuturesDialectAdapter.Request(
            base.trimEnd('/') + "/api/v5/market/candles?" + query.joinToString("&"),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v5/market/ticker?instId=${inst(symbol)}",
    )

    override fun exchangeInfo(base: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v5/public/instruments?instType=SWAP",
    )

    override fun parseKlines(text: String, minutes: Long): List<Kline> {
        val root = parse(text).also { it.checkOkx() }.asObject()
        return root["data"].asArray().mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            candle(
                openTime = a.str(0)?.toLongOrNull() ?: return@mapNotNull null,
                minutes = minutes,
                o = a.str(1).bd(),
                h = a.str(2).bd(),
                l = a.str(3).bd(),
                c = a.str(4).bd(),
                base = a.str(6).bd(),      // volCcy：衍生品口径为基础币
                quote = a.str(7).bd(),     // volCcyQuote
            )
        }.sortedBy { it.openTime }
    }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = parse(text).also { it.checkOkx() }.asObject()["data"].asArray()
            .firstOrNull()?.asObject() ?: return null
        val last = row.str("last").bd() ?: return null
        val baseVol = row.str("volCcy24h").bd()
        return tickerOf(
            symbol = symbol,
            last = last,
            open = row.str("open24h").bd(),
            high = row.str("high24h").bd(),
            low = row.str("low24h").bd(),
            base = baseVol,
            quote = quoteOf(baseVol, last), // 衍生品口径无现成计价币量，按最新价折算
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).also { it.checkOkx() }.asObject()["data"].asArray().mapNotNull { el ->
            val row = el.asObject()
            val instId = row.str("instId").orEmpty()
            if (!instId.endsWith("-USDT-SWAP")) return@mapNotNull null
            val base = row.str("baseCcy")?.takeIf { it.isNotBlank() } ?: instId.substringBefore('-')
            meta(
                symbol = base + "USDT",
                base = base,
                quote = "USDT",
                priceTick = row.str("tickSz").bd(),
                qtyStep = row.str("lotSz").bd(),
                trading = row.str("state") == "live",
            )
        }
}

// —————————————————————————— Bybit v5 ——————————————————————————

/** Bybit（api.bybit.com）。linear 即 USDT 永续，volume=基础币、turnover=USDT。 */
internal object BybitDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "1", 3L to "3", 5L to "5", 15L to "15", 30L to "30",
        60L to "60", 120L to "120", 240L to "240", 360L to "360", 720L to "720",
        1_440L to "D", 10_080L to "W", 43_200L to "M",
    )
    override val maxKlineLimit = 1000

    /**
     * 历史持仓量（`/market/open-interest`）。`result.list` **降序**返回，统一排序；
     * `openInterest` 是**基础币口径**（BTC ≈ 5.8 万；与 tickers 的 openInterestValue 4.9e9
     * 对照 ≈ ×现价）⇒ [OpenInterestSpec.baseCoinValue]，对齐时折美元。
     * 周期 5min/15min/30min/1h/4h/1d，上限 200。
     */
    override val openInterest = OpenInterestSpec(
        ladder = mapOf(
            5L to "5min", 15L to "15min", 30L to "30min",
            60L to "1h", 240L to "4h", 1_440L to "1d",
        ),
        baseCoinValue = true,
        call = { base, symbol, minutes, limit ->
            FuturesDialectAdapter.Request(
                base.trimEnd('/') + "/v5/market/open-interest" +
                    "?category=linear&symbol=$symbol" +
                    "&intervalTime=${requireNotNull(openInterest.ladder[minutes])}" +
                    "&limit=${limit.coerceIn(1, 200)}",
            )
        },
        parse = { text ->
            parse(text).also { it.checkBybit() }.asObject()["result"].asObject()["list"].asArray()
                .mapNotNull { el ->
                    val row = el.asObject()
                    val time = row.str("timestamp")?.toLongOrNull() ?: return@mapNotNull null
                    val value = row.str("openInterest")?.toDoubleOrNull() ?: return@mapNotNull null
                    OpenInterestPoint(time, value)
                }.sortedBy { it.time }
        },
    )

    override fun probe(base: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/v5/market/time")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("category=linear")
            add("symbol=$symbol")
            add("interval=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            startMs?.let { add("start=$it") }
            endMs?.let { add("end=$it") }
        }
        return FuturesDialectAdapter.Request(
            base.trimEnd('/') + "/v5/market/kline?" + query.joinToString("&"),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/v5/market/tickers?category=linear&symbol=$symbol",
    )

    override fun exchangeInfo(base: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/v5/market/instruments-info?category=linear&limit=1000",
    )

    override fun parseKlines(text: String, minutes: Long): List<Kline> {
        val root = parse(text).also { it.checkBybit() }.asObject()
        return root["result"].asObject()["list"].asArray().mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            candle(
                openTime = a.str(0)?.toLongOrNull() ?: return@mapNotNull null,
                minutes = minutes,
                o = a.str(1).bd(),
                h = a.str(2).bd(),
                l = a.str(3).bd(),
                c = a.str(4).bd(),
                base = a.str(5).bd(),
                quote = a.str(6).bd(),
            )
        }.sortedBy { it.openTime }
    }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = parse(text).also { it.checkBybit() }.asObject()["result"].asObject()["list"].asArray()
            .firstOrNull()?.asObject() ?: return null
        return tickerOf(
            symbol = symbol,
            last = row.str("lastPrice").bd(),
            // Bybit 没有 24h 开盘价字段，prevPrice24h 是「24h 前的最新价」——行业通用的涨跌幅基准，够用
            open = row.str("prevPrice24h").bd(),
            high = row.str("highPrice24h").bd(),
            low = row.str("lowPrice24h").bd(),
            base = row.str("volume24h").bd(),
            quote = row.str("turnover24h").bd(),
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).also { it.checkBybit() }.asObject()["result"].asObject()["list"].asArray().mapNotNull { el ->
            val row = el.asObject()
            if (row.str("contractType") != "LinearPerpetual" || row.str("quoteCoin") != "USDT") return@mapNotNull null
            val tick = row["priceFilter"].asObject().str("tickSize").bd()
                ?: row.str("priceScale")?.toIntOrNull()?.let { BigDecimal.ONE.movePointLeft(it) }
            meta(
                symbol = row.str("symbol").orEmpty(),
                base = row.str("baseCoin").orEmpty(),
                quote = "USDT",
                priceTick = tick,
                qtyStep = row["lotSizeFilter"].asObject().str("qtyStep").bd(),
                trading = row.str("status") == "Trading",
            )
        }
}

// —————————————————————————— Bitget v2 mix ——————————————————————————

/** Bitget（api.bitget.com）USDT-FUTURES。K 线行内第 6/7 列即基础/计价币量，升序返回。 */
internal object BitgetDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "1m", 3L to "3m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1h", 120L to "2h", 240L to "4h", 360L to "6h", 720L to "12h",
        1_440L to "1d", 4_320L to "3d", 10_080L to "1w", 43_200L to "1M",
    )
    override val maxKlineLimit = 1000

    override fun probe(base: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/api/v2/public/time")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("symbol=$symbol")
            add("productType=usdt-futures")
            add("granularity=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            startMs?.let { add("startTime=$it") }
            endMs?.let { add("endTime=$it") }
        }
        return FuturesDialectAdapter.Request(
            base.trimEnd('/') + "/api/v2/mix/market/candles?" + query.joinToString("&"),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v2/mix/market/ticker?symbol=$symbol&productType=usdt-futures",
    )

    override fun exchangeInfo(base: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v2/mix/market/contracts?productType=usdt-futures",
    )

    override fun parseKlines(text: String, minutes: Long): List<Kline> {
        val root = parse(text).also { it.checkBitget() }.asObject()
        return root["data"].asArray().mapNotNull { row ->
            val a = row as? JsonArray ?: return@mapNotNull null
            candle(
                openTime = a.str(0)?.toLongOrNull() ?: return@mapNotNull null,
                minutes = minutes,
                o = a.str(1).bd(),
                h = a.str(2).bd(),
                l = a.str(3).bd(),
                c = a.str(4).bd(),
                base = a.str(5).bd(),
                quote = a.str(6).bd(),
            )
        }.sortedBy { it.openTime }
    }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val data = parse(text).also { it.checkBitget() }.asObject()["data"]
        // 单标的文档示例是 1 元素数组，历史版本给对象——两种都认
        val row = (data.asArray().firstOrNull() ?: data).asObject()
        return tickerOf(
            symbol = symbol,
            last = row.str("lastPr").bd(),
            open = row.str("open24h").bd(),
            high = row.str("high24h").bd(),
            low = row.str("low24h").bd(),
            base = row.str("baseVolume").bd(),
            quote = row.str("quoteVolume").bd(),
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).also { it.checkBitget() }.asObject()["data"].asArray().mapNotNull { el ->
            val row = el.asObject()
            if (row.str("quoteCoin") != "USDT") return@mapNotNull null
            // 价格精度 = priceEndStep × 10^-pricePlace（如 step=1、place=1 → tick 0.1）
            val place = row.str("pricePlace")?.toIntOrNull()
            val endStep = row.str("priceEndStep")?.toIntOrNull() ?: 1
            val tick = place?.takeIf { it in 0..12 }?.let { BigDecimal(endStep.toString()).movePointLeft(it) }
            meta(
                symbol = row.str("symbol").orEmpty(),
                base = row.str("baseCoin").orEmpty(),
                quote = "USDT",
                priceTick = tick,
                qtyStep = row.str("sizeMultiplier").bd(),
                trading = row.str("symbolStatus") in setOf("normal", "listed"),
            )
        }
}

// —————————————————————————— Gate v4 futures ——————————————————————————

/**
 * Gate（api.gateio.ws）。K 线是对象数组且时间单位为秒；v 以「张」计（quanto_multiplier），
 * 基础币量按 sum/close 折算。无 24h 开盘字段，用 last - change_price 还原。
 */
internal object GateDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "1m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1h", 120L to "2h", 240L to "4h", 360L to "6h", 480L to "8h",
        1_440L to "1d", 10_080L to "7d",
    )
    override val maxKlineLimit = 2000

    /**
     * 历史持仓量：`/api/v4/futures/usdt/contract_stats?contract=BTC_USDT&interval=5m`，
     * 行内 `open_interest_usd` 即美元名义值；时间是**秒**。周期含 1m，深度充足（上限 1000）。
     */
    override val openInterest = OpenInterestSpec(
        ladder = mapOf(
            1L to "1m", 5L to "5m", 15L to "15m", 30L to "30m", 60L to "1h",
            240L to "4h", 480L to "8h", 720L to "12h", 1_440L to "1d",
        ),
        call = { base, symbol, minutes, limit ->
            FuturesDialectAdapter.Request(
                base.trimEnd('/') + "/api/v4/futures/usdt/contract_stats" +
                    "?contract=${contract(symbol)}" +
                    "&interval=${requireNotNull(openInterest.ladder[minutes])}" +
                    "&limit=${limit.coerceIn(1, 1000)}",
            )
        },
        parse = { text ->
            parse(text).asArray().mapNotNull { el ->
                val row = el.asObject()
                val seconds = row.str("time")?.toLongOrNull() ?: return@mapNotNull null
                val value = row.str("open_interest_usd")?.toDoubleOrNull() ?: return@mapNotNull null
                OpenInterestPoint(seconds * 1000, value)
            }.sortedBy { it.time }
        },
    )

    private fun contract(symbol: String) = stripUsdt(symbol) + "_USDT"

    override fun probe(base: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v4/futures/usdt/contracts/BTC_USDT",
    )

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("contract=${contract(symbol)}")
            add("interval=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            startMs?.let { add("from=${it / 1000}") }
            endMs?.let { add("to=${it / 1000}") }
        }
        return FuturesDialectAdapter.Request(
            base.trimEnd('/') + "/api/v4/futures/usdt/candlesticks?" + query.joinToString("&"),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v4/futures/usdt/tickers?contract=${contract(symbol)}",
    )

    override fun exchangeInfo(base: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v4/futures/usdt/contracts",
    )

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        parse(text).asArray().mapNotNull { el ->
            val row = el.asObject()
            val openTime = row.str("t")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val close = row.str("c").bd()
            val sum = row.str("sum").bd()
            candle(
                openTime = openTime,
                minutes = minutes,
                o = row.str("o").bd(),
                h = row.str("h").bd(),
                l = row.str("l").bd(),
                c = close,
                base = baseOf(sum, close),
                quote = sum,
            )
        }.sortedBy { it.openTime }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = parse(text).asArray().firstOrNull()?.asObject() ?: return null
        val last = row.str("last").bd() ?: return null
        val open = row.str("change_price").bd()?.let { last.subtract(it) }
        val quote = row.str("volume_24h_quote").bd()
        return tickerOf(
            symbol = symbol,
            last = last,
            open = open,
            high = row.str("high_24h").bd(),
            low = row.str("low_24h").bd(),
            base = row.str("volume_24h_base").bd() ?: baseOf(quote, last),
            quote = quote,
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).asArray().mapNotNull { el ->
            val row = el.asObject()
            val name = row.str("name").orEmpty()
            if (!name.endsWith("_USDT")) return@mapNotNull null
            meta(
                symbol = name.replace("_", ""),
                base = row.str("baseCoin")?.takeIf { it.isNotBlank() } ?: name.substringBefore('_'),
                quote = "USDT",
                priceTick = row.str("order_price_round").bd(),
                qtyStep = row.str("order_size_round").bd(),
                trading = row.str("in_delisting") != "true",
            )
        }
}

// —————————————————————————— MEXC contract v1 ——————————————————————————

/**
 * MEXC（contract.mexc.com）。K 线是「列存」平行数组且时间单位为秒、无 limit 参数
 * （固定返回窗口内至多 2000 根，窗口按 limit×周期倒推）。量以「张」计，
 * 基础币量按 amount/close 折算。24h 开盘用 lastPrice - riseFallValue 还原。
 */
internal object MexcDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "Min1", 5L to "Min5", 15L to "Min15", 30L to "Min30", 60L to "Min60",
        240L to "Hour4", 480L to "Hour8", 1_440L to "Day1", 10_080L to "Week1", 43_200L to "Month1",
    )
    override val maxKlineLimit = 2000

    private fun contract(symbol: String) = stripUsdt(symbol) + "_USDT"

    override fun probe(base: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/api/v1/contract/ping")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val capped = limit.coerceIn(1, maxKlineLimit)
        val endSec = (endMs ?: startMs?.plus(capped * minutes * 60_000L) ?: System.currentTimeMillis()) / 1000
        val startSec = (startMs?.let { it / 1000 } ?: endSec - capped * minutes * 60L).coerceAtMost(endSec)
        val query = listOf(
            "interval=${requireNotNull(intervalLadder[minutes])}",
            "start=$startSec",
            "end=$endSec",
        )
        return FuturesDialectAdapter.Request(
            base.trimEnd('/') + "/api/v1/contract/kline/" + contract(symbol) + "?" + query.joinToString("&"),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        base.trimEnd('/') + "/api/v1/contract/ticker?symbol=${contract(symbol)}",
    )

    override fun exchangeInfo(base: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/api/v1/contract/detail")

    override fun parseKlines(text: String, minutes: Long): List<Kline> {
        val data = parse(text).also { it.checkMexc() }.asObject()["data"].asObject()
        fun col(key: String): List<JsonElement> = data[key].asArray()
        val times = col("time")
        val rows = ArrayList<Kline>(times.size)
        for (i in times.indices) {
            val openTime = (times[i] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: continue
            val close = col("close").getOrNull(i).numBd()
            val amount = col("amount").getOrNull(i).numBd()
            candle(
                openTime = openTime * 1000,
                minutes = minutes,
                o = col("open").getOrNull(i).numBd(),
                h = col("high").getOrNull(i).numBd(),
                l = col("low").getOrNull(i).numBd(),
                c = close,
                base = baseOf(amount, close),
                quote = amount,
            )?.let { rows += it }
        }
        return rows.sortedBy { it.openTime }
    }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val data = parse(text).also { it.checkMexc() }.asObject()["data"]
        // 带 symbol 时是对象，个别版本给单元素数组——两种都认
        val row = (data as? JsonArray)?.let { it.firstOrNull() } ?: data
        val obj = row.asObject()
        val last = obj.str("lastPrice").bd() ?: return null
        val open = obj.str("riseFallValue").bd()?.let { last.subtract(it) }
        val quote = obj.str("amount24").bd()
        return tickerOf(
            symbol = symbol,
            last = last,
            open = open,
            high = obj.str("high24Price").bd(),
            low = obj.str("lower24Price").bd(),
            base = baseOf(quote, last),
            quote = quote,
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).also { it.checkMexc() }.asObject()["data"].asArray().mapNotNull { el ->
            val row = el.asObject()
            val symbol = row.str("symbol").orEmpty()
            if (!symbol.endsWith("_USDT") || row.str("settleCoin") != "USDT") return@mapNotNull null
            meta(
                symbol = symbol.replace("_", ""),
                base = row.str("baseCoin").orEmpty(),
                quote = "USDT",
                priceTick = row.str("priceUnit").bd(),
                qtyStep = row.str("volUnit").bd(),
                trading = row.str("state") == "0",
            )
        }

    private fun JsonElement?.numBd(): BigDecimal? = (this as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
}

// —————————————————————————— Hyperliquid ——————————————————————————

/**
 * Hyperliquid（api.hyperliquid.xyz）：唯一的 POST 方言，一切走 /info。
 * 无 24h ticker，单标的快照用 1h×26 根蜡烛折叠（open=24h 前那根、量=逐根累加），
 * 与详情页/预警「每标的一次请求」的既有节奏一致。
 * 蜡烛只有基础币量（v），计价币量按收盘价折算。
 */
internal object HyperliquidDialect : FuturesDialectAdapter {

    override val intervalLadder = mapOf(
        1L to "1m", 3L to "3m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1h", 120L to "2h", 240L to "4h", 360L to "6h", 480L to "8h",
        720L to "12h", 1_440L to "1d", 4_320L to "3d", 10_080L to "1w", 43_200L to "1M",
    )
    override val maxKlineLimit = 500

    private fun coin(symbol: String) = stripUsdt(symbol)

    private fun post(base: String, body: String) =
        FuturesDialectAdapter.Request(base.trimEnd('/') + "/info", body)

    override fun probe(base: String) = post(base, """{"type":"meta"}""")

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val capped = limit.coerceIn(1, maxKlineLimit)
        val end = endMs ?: System.currentTimeMillis()
        // candleSnapshot 只认时间窗：按根数倒推起点，多给 20% 容忍缺数
        val start = startMs ?: (end - capped * minutes * 60_000L * 6 / 5)
        val body = """{"type":"candleSnapshot","req":{"coin":"${coin(symbol)}",""" +
            """"interval":"${requireNotNull(intervalLadder[minutes])}","startTime":$start,"endTime":$end}}"""
        return post(base, body)
    }

    override fun ticker(base: String, symbol: String): FuturesDialectAdapter.Request {
        val end = System.currentTimeMillis()
        val start = end - 26 * 3_600_000L
        val body = """{"type":"candleSnapshot","req":{"coin":"${coin(symbol)}",""" +
            """"interval":"1h","startTime":$start,"endTime":$end}}"""
        return post(base, body)
    }

    override fun exchangeInfo(base: String) = post(base, """{"type":"meta"}""")

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        parse(text).asArray().mapNotNull { el ->
            val row = el.asObject()
            val openTime = row.str("t")?.toLongOrNull() ?: return@mapNotNull null
            val close = row.str("c").bd()
            val base = row.str("v").bd()
            candle(
                openTime = openTime,
                minutes = minutes,
                closeTime = row.str("T")?.toLongOrNull() ?: closeTimeOf(openTime, minutes),
                o = row.str("o").bd(),
                h = row.str("h").bd(),
                l = row.str("l").bd(),
                c = close,
                base = base,
                quote = quoteOf(base, close),
                trades = row.str("n")?.toLongOrNull() ?: 0,
            )
        }.sortedBy { it.openTime }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val candles = parseKlines(text, 60)
        if (candles.isEmpty()) return null
        val last = candles.last()
        var high = candles.first().high
        var low = candles.first().low
        var baseVol = BigDecimal.ZERO
        var quoteVol = BigDecimal.ZERO
        candles.forEach { c ->
            if (c.high > high) high = c.high
            if (c.low < low) low = c.low
            baseVol = baseVol.add(c.volume)
            quoteVol = quoteVol.add(c.quoteVolume)
        }
        return tickerOf(
            symbol = symbol,
            last = last.close,
            open = candles.first().open,
            high = high,
            low = low,
            base = baseVol,
            quote = quoteVol,
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        parse(text).asObject()["universe"].asArray().mapNotNull { el ->
            val row = el.asObject()
            val coin = row.str("name").orEmpty()
            if (coin.isEmpty()) return@mapNotNull null
            // 永续价格规则：最多 5 位有效数字且不超过 6 - szDecimals 位小数；
            // 拿不到现价，按小数位上限给 tick（只影响显示位数，宁多勿少）。
            val decimals = (6 - (row.str("szDecimals")?.toIntOrNull() ?: 4)).coerceIn(1, 8)
            meta(
                symbol = coin + "USDT",
                base = coin,
                quote = "USDT",
                priceTick = BigDecimal.ONE.movePointLeft(decimals),
                qtyStep = row.str("szDecimals")?.toIntOrNull()?.let { BigDecimal.ONE.movePointLeft(it) },
                trading = row.str("isDelisted") != "true",
            )
        }
}

// —————————————————————————— HTX（火币）USDT 本位永续 ——————————————————————————

/**
 * HTX / 火币（api.hbdm.com）。两套路径混用，别搞混：
 * - `linear-swap-ex` 下的 `market` 一脉是**行情**（K 线、detail/merged、batch_merged），裸 JSON 带 `status`；
 * - `linear-swap-api/v1` 一脉是**合约元数据**（swap_contract_info）与探测。
 *
 * ⚠️ **可达性弱于其他家**：实测期间 `api.hbdm.com` / `api.htx.com` 的解析会落到 Meta 的 IP 段
 * （`157.240.10.32` / `108.160.170.52`，典型 DNS 污染），表现为间歇性连不上，而同网络下
 * Gate / Bitunix / Hyperliquid 解析正常。它进候选是因为「接口最完整、通的时候最好用」，
 * 不是因为它稳 —— 探测弹窗里时通时不通属于真实情况。
 *
 * K 线行 = `{id(秒), open, close, high, low, amount, vol, trade_turnover, count}`，原生**升序**；
 * `amount` 是基础币数量、`vol` 是张数、`trade_turnover` 是计价币金额（三者实测自洽：
 * `amount × close ≈ trade_turnover`）。24h 快照自带 `open`，无需像 Gate / Bybit / MEXC 那样还原。
 */
internal object HtxDialect : FuturesDialectAdapter {

    /**
     * 逐周期实测白名单：`1min 3 5 15 30 60min 2 4 6 12hour 1day 3day 1week 1mon`；
     * `1year` 被拒，对照用的 `bogus` 同样被拒（所以 200 是真支持，不是宽容回落）。
     */
    override val intervalLadder = mapOf(
        1L to "1min", 3L to "3min", 5L to "5min", 15L to "15min", 30L to "30min",
        60L to "60min", 120L to "2hour", 240L to "4hour", 360L to "6hour", 720L to "12hour",
        1_440L to "1day", 4_320L to "3day", 10_080L to "1week", 43_200L to "1mon",
    )

    /** 实测：size=2000 通过、size=5000 被拒（`invalid size`），故上限为 2000。 */
    override val maxKlineLimit = 2000

    /** 实测 `from`/`to` 与 `start`/`end` 四种写法全被忽略，永远返回最新 size 根 ⇒ 不能翻页。 */
    override val supportsTimeWindow = false

    private fun contract(symbol: String) = stripUsdt(symbol) + "-USDT"

    private fun market(base: String, path: String) = base.trimEnd('/') + "/linear-swap-ex" + path

    private fun api(base: String, path: String) = base.trimEnd('/') + "/linear-swap-api/v1" + path

    /** 先查业务码，再取指定键的数组（K 线在 `data`、全量快照在 `ticks`）。 */
    private fun rows(text: String, key: String): List<JsonElement> =
        parse(text).also { it.checkHtx() }.asObject()[key].asArray()

    override fun probe(base: String) =
        FuturesDialectAdapter.Request(api(base, "/swap_contract_info?contract_code=BTC-USDT"))

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        // ❗**时间窗参数实测无效**：`from`/`to` 与 `start`/`end` 四种写法都试过，
        // 传与不传返回的都是「最近 size 根」（用一个明显过期的窗口请求，回来的仍是当下最新的
        // 那几根）。所以这里不传 —— 传了会让人以为支持翻页，实际拿到的是重复数据。
        // 代价是往更早方向翻页会退化成重复取最新 N 根（上层按 openTime 去重后无新增）。
        val query = listOf(
            "contract_code=${contract(symbol)}",
            "period=${requireNotNull(intervalLadder[minutes])}",
            "size=${limit.coerceIn(1, maxKlineLimit)}",
        )
        return FuturesDialectAdapter.Request(
            market(base, "/market/history/kline?" + query.joinToString("&")),
        )
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        market(base, "/market/detail/merged?contract_code=${contract(symbol)}"),
    )

    override fun exchangeInfo(base: String) =
        FuturesDialectAdapter.Request(api(base, "/swap_contract_info"))

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        rows(text, "data").mapNotNull { el ->
            val row = el.asObject()
            val seconds = row.str("id")?.toLongOrNull() ?: return@mapNotNull null
            candle(
                openTime = seconds * 1000,
                minutes = minutes,
                o = row.str("open").bd(),
                h = row.str("high").bd(),
                l = row.str("low").bd(),
                c = row.str("close").bd(),
                base = row.str("amount").bd(),
                quote = row.str("trade_turnover").bd(),
                trades = row.str("count")?.toLongOrNull() ?: 0,
            )
        }.sortedBy { it.openTime }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = parse(text).also { it.checkHtx() }.asObject()["tick"].asObject()
        val last = row.str("close").bd() ?: return null
        return tickerOf(
            symbol = symbol,
            last = last,
            open = row.str("open").bd(),
            high = row.str("high").bd(),
            low = row.str("low").bd(),
            base = row.str("amount").bd(),
            quote = row.str("trade_turnover").bd(),
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        rows(text, "data").mapNotNull { el ->
            val row = el.asObject()
            val code = row.str("contract_code").orEmpty()
            if (!code.endsWith("-USDT")) return@mapNotNull null
            // 元数据行的币名在 `symbol`（如 BTC），合约名在 `contract_code`（如 BTC-USDT）
            val base = row.str("symbol")?.takeIf { it.isNotBlank() }
                ?: code.substringBefore('-').takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            meta(
                symbol = base + "USDT",
                base = base,
                quote = "USDT",
                priceTick = row.str("price_tick").bd(),
                qtyStep = row.str("contract_size").bd(),
                trading = row.str("contract_status") == "1",
            )
        }
}

// —————————————————————————— Bitunix ——————————————————————————

/**
 * Bitunix（fapi.bitunix.com）。全部走 `api/v1/futures/market` 这一脉，裸 JSON 带 `code`。
 *
 * 本次实测里最「干净」的一家：直连稳定（Cloudflare）、四件套齐全、响应体积小。
 *
 * 三处必须留意：
 * - **K 线是降序返回**（newest-first），时间是毫秒 —— 统一排序后再交给上层；
 * - **单标的快照走 `tickers?symbols=`（复数）**：`ticker?symbol=` 返回 `code:404`，
 *   不带参数的 `/tickers` 则是全量；
 * - ❗**非法 interval 返 `code:0` + `data:[]`** —— 不报错、也不回落，只是给个空数组。
 *   所以「请求没报错」在这里不等于「周期被支持」；适配器靠 [intervalLadder] 白名单
 *   把非法周期挡在请求之前，不能依赖接口报错。
 *
 * 价格精度取 `quotePrecision`（价格小数位）：实测 BTCUSDT(1)→0.1、DOGEUSDT(5)→0.00001、
 * 1000PEPEUSDT(7)→0.0000001 均对得上。
 */
internal object BitunixDialect : FuturesDialectAdapter {

    /** 实测 15 个周期全支持；`1M` 走**日历月**（相邻间隔实测 28~31 天）。 */
    override val intervalLadder = mapOf(
        1L to "1m", 3L to "3m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1h", 120L to "2h", 240L to "4h", 360L to "6h", 480L to "8h", 720L to "12h",
        1_440L to "1d", 4_320L to "3d", 10_080L to "1w", 43_200L to "1M",
    )

    /** 实测上限：请求 500 / 1000 / 1500 一律只回 200 根（服务端截断）。 */
    override val maxKlineLimit = 200

    private fun native(symbol: String) = stripUsdt(symbol) + "USDT"

    private fun url(base: String, path: String) = base.trimEnd('/') + "/api/v1/futures/market" + path

    private fun rows(text: String): List<JsonElement> =
        parse(text).also { it.checkBitunix() }.asObject()["data"].asArray()

    override fun probe(base: String) = FuturesDialectAdapter.Request(url(base, "/time"))

    override fun klines(
        base: String,
        symbol: String,
        minutes: Long,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): FuturesDialectAdapter.Request {
        val query = buildList {
            add("symbol=${native(symbol)}")
            add("interval=${requireNotNull(intervalLadder[minutes])}")
            add("limit=${limit.coerceIn(1, maxKlineLimit)}")
            startMs?.let { add("startTime=$it") }
            endMs?.let { add("endTime=$it") }
        }
        return FuturesDialectAdapter.Request(url(base, "/kline?" + query.joinToString("&")))
    }

    override fun ticker(base: String, symbol: String) = FuturesDialectAdapter.Request(
        url(base, "/tickers?symbols=${native(symbol)}"),
    )

    override fun exchangeInfo(base: String) =
        FuturesDialectAdapter.Request(url(base, "/trading_pairs"))

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        rows(text).mapNotNull { el ->
            val row = el.asObject()
            val ms = row.str("time")?.toLongOrNull() ?: return@mapNotNull null
            candle(
                openTime = ms,
                minutes = minutes,
                o = row.str("open").bd(),
                h = row.str("high").bd(),
                l = row.str("low").bd(),
                c = row.str("close").bd(),
                base = row.str("baseVol").bd(),
                quote = row.str("quoteVol").bd(),
            )
        }.sortedBy { it.openTime }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = rows(text).firstOrNull()?.asObject() ?: return null
        val last = row.str("lastPrice").bd() ?: return null
        return tickerOf(
            symbol = symbol,
            last = last,
            open = row.str("open").bd(),
            high = row.str("high").bd(),
            low = row.str("low").bd(),
            base = row.str("baseVol").bd(),
            quote = row.str("quoteVol").bd(),
            now = now,
        )
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        rows(text).mapNotNull { el ->
            val row = el.asObject()
            if (row.str("quote") != "USDT" || row.str("symbolStatus") != "OPEN") return@mapNotNull null
            val base = row.str("base")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val decimals = row.str("quotePrecision")?.toIntOrNull()
            val tick = decimals?.takeIf { it in 0..12 }?.let { BigDecimal.ONE.movePointLeft(it) }
            meta(
                symbol = base + "USDT",
                base = base,
                quote = "USDT",
                priceTick = tick,
                qtyStep = row.str("basePrecision")?.toIntOrNull()?.let { BigDecimal.ONE.movePointLeft(it) },
                trading = true,
            )
        }
}
