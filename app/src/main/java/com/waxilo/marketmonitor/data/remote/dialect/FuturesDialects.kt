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

object FuturesDialects {
    fun of(kind: FuturesDialect): FuturesDialectAdapter = when (kind) {
        FuturesDialect.BINANCE -> BinanceCompatibleDialect
        FuturesDialect.OKX -> OkxDialect
        FuturesDialect.BYBIT -> BybitDialect
        FuturesDialect.BITGET -> BitgetDialect
        FuturesDialect.GATE -> GateDialect
        FuturesDialect.MEXC -> MexcDialect
        FuturesDialect.HYPERLIQUID -> HyperliquidDialect
    }
}

// —————————————————————————— JSON 小工具 ——————————————————————————

private fun parse(text: String): JsonElement = MarketJson.DEFAULT.parseToJsonElement(text)

private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonElement.asArray(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

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

// —————————————————————————— 币安同构（Aster / 币安主域与镜像）——————————————————————————————————————

/** 与 fapi.binance.com /fapi/v1 完全同构的接口（Aster 即此列），复用现成的 DTO 解析。 */
internal object BinanceCompatibleDialect : FuturesDialectAdapter {

    override val intervalLadder: Map<Long, String> =
        OfficialInterval.entries.associate { it.minutes to it.apiCode }
    override val maxKlineLimit = 1000

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
