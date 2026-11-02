package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.dto.ExchangeInfoDto
import com.waxilo.marketmonitor.data.remote.dto.TickerDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.data.remote.dto.toKline
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SpotSource
import com.waxilo.marketmonitor.domain.model.SymbolId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.net.URLEncoder

/**
 * 现货方言：与合约方言（`FuturesDialects`）分开一套。
 *
 * 现货两家（币安 / Gate）的路径、字段名、K 线列序、周期码、交易对写法全不一样
 * （`klines` vs `candlesticks`、`1w/1M` vs `7d/30d`、`BTCUSDT` vs `BTC_USDT`）。
 * 出口与上层一致：**内部一律用币安写法**，Gate 写法只在本文件里换算（见 [SpotSymbols]）。
 */
internal interface SpotDialectAdapter {
    /** 该家支持的分钟周期（与 `OfficialInterval` 同一套 15 档）。 */
    val intervalMinutes: List<Long>
    val maxKlineLimit: Int

    fun pingPath(): String
    fun exchangeInfoPath(): String
    fun allTickersPath(): String
    fun tickerPath(symbol: String): String
    fun klinesPath(symbol: String, minutes: Long, limit: Int, startTime: Long?, endTime: Long?): String

    fun parseExchangeInfo(text: String): List<InstrumentMeta>
    fun parseTicker(text: String, symbol: String, now: Long): MarketTicker?
    fun parseTickers(text: String, now: Long): List<MarketTicker>
    fun parseKlines(text: String, minutes: Long): List<Kline>
}

internal object SpotDialects {
    fun of(source: SpotSource): SpotDialectAdapter = when (source) {
        SpotSource.BINANCE -> BinanceSpotDialect
        SpotSource.GATE -> GateSpotDialect
    }
}

/**
 * 内部结算符号（币安写法 `BTCUSDT`）与 Gate 写法（`BTC_USDT`）互转。
 *
 * 币安写法没有分隔符，拆回 base/quote 只能靠「最长报价币后缀」匹配；报价币清单
 * 覆盖两家当前全部交易区（Gate 只有 USDT/USDC/USD/USD1/GUSD/RLUSD/BTC/ETH，
 * 币安另有 FDUSD/TUSD/EUR 等）。按长度倒序匹配，`BTCUSDT` 才不会被读成 BTCUS / DT。
 */
internal object SpotSymbols {

    private val quotes = listOf(
        "USDT", "USDC", "FDUSD", "TUSD", "BUSD", "USDP", "USDS", "DAI", "AEUR",
        "GUSD", "RLUSD", "USD1", "USD",
        "EUR", "TRY", "BRL", "ARS", "JPY", "GBP", "AUD", "RUB", "UAH", "ZAR",
        "PLN", "RON", "NGN", "IDRT", "BVND", "BIDR",
        "BTC", "ETH", "BNB",
    ).distinct().sortedByDescending { it.length }

    /** Gate 原生写法 → 内部写法；已经带下划线的直接去掉。 */
    fun toInternal(native: String): String = native.trim().replace("_", "")

    /** 内部写法（或任意原生写法）→ Gate 写法；拆不出报价币时原样返回，由服务端拒绝。 */
    fun toGate(symbol: String): String {
        val trimmed = symbol.trim()
        if ('_' in trimmed) return trimmed
        val quote = quotes.firstOrNull { trimmed.length > it.length && trimmed.endsWith(it) }
            ?: return trimmed
        return "${trimmed.dropLast(quote.length)}_$quote"
    }
}

/**
 * 币安现货：路径与字段就是原有 `/api/v3` 那套，这里只是把 URL 构造与解析收进方言。
 */
private object BinanceSpotDialect : SpotDialectAdapter {

    private val json = MarketJson.DEFAULT

    override val intervalMinutes: List<Long> = OfficialInterval.entries.map { it.minutes }

    override val maxKlineLimit: Int = 1_000

    override fun pingPath(): String = "/api/v3/ping"

    override fun exchangeInfoPath(): String = "/api/v3/exchangeInfo"

    override fun allTickersPath(): String = "/api/v3/ticker/24hr"

    override fun tickerPath(symbol: String): String =
        "/api/v3/ticker/24hr?symbol=${enc(symbol)}"

    override fun klinesPath(
        symbol: String,
        minutes: Long,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): String {
        val code = OfficialInterval.entries.firstOrNull { it.minutes == minutes }?.apiCode
            ?: error("币安现货不支持 $minutes 分钟周期")
        return buildString {
            append("/api/v3/klines?symbol=").append(enc(symbol))
            append("&interval=").append(code)
            append("&limit=").append(limit)
            startTime?.let { append("&startTime=").append(it) }
            endTime?.let { append("&endTime=").append(it) }
        }
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> {
        val dto = json.decodeFromJsonElement(ExchangeInfoDto.serializer(), json.parseToJsonElement(text))
        return dto.symbols.mapNotNull { it.toDomain(MarketType.SPOT) }
    }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? =
        json.decodeFromJsonElement(TickerDto.serializer(), json.parseToJsonElement(text))
            .toDomain(MarketType.SPOT, now)

    override fun parseTickers(text: String, now: Long): List<MarketTicker> =
        jsonArray(json.parseToJsonElement(text)).mapNotNull { item ->
            json.decodeFromJsonElement(TickerDto.serializer(), item).toDomain(MarketType.SPOT, now)
        }

    override fun parseKlines(text: String, minutes: Long): List<Kline> =
        jsonArray(json.parseToJsonElement(text)).mapNotNull { row ->
            (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toKline()
        }
}

/**
 * Gate 现货（v4）。
 *
 * K 线列序与币安完全不同：`[秒级开盘时间, 计价成交额, close, high, low, open, 基础成交额, 是否收盘]`。
 * 24h 快照没有开盘价，用 `change_percentage` 反推（`open = last / (1 + pct/100)`），
 * 这样 `MarketTicker.changePercent` 仍由同一个公式算出来，两家涨跌幅口径一致。
 */
private object GateSpotDialect : SpotDialectAdapter {

    private val json = MarketJson.DEFAULT

    override val intervalMinutes: List<Long> = listOf(
        1, 3, 5, 15, 30, 60, 120, 240, 360, 480, 720,
        1_440, 4_320, 10_080, 43_200,
    )

    override val maxKlineLimit: Int = 1_000

    override fun pingPath(): String = "/api/v4/spot/time"

    override fun exchangeInfoPath(): String = "/api/v4/spot/currency_pairs"

    override fun allTickersPath(): String = "/api/v4/spot/tickers"

    override fun tickerPath(symbol: String): String =
        "/api/v4/spot/tickers?currency_pair=${enc(SpotSymbols.toGate(symbol))}"

    override fun klinesPath(
        symbol: String,
        minutes: Long,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): String {
        val code = GateIntervalCodes.of(minutes)
        return buildString {
            append("/api/v4/spot/candlesticks?currency_pair=").append(enc(SpotSymbols.toGate(symbol)))
            append("&interval=").append(code)
            append("&limit=").append(limit)
            startTime?.let { append("&from=").append(it / 1_000) }
            endTime?.let { append("&to=").append(it / 1_000) }
        }
    }

    override fun parseExchangeInfo(text: String): List<InstrumentMeta> =
        jsonArray(json.parseToJsonElement(text)).mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            // 只有可交易的对进清单：Gate 会把下架/暂停的对也列出来
            if (row.str("trade_status") != "tradable") return@mapNotNull null
            val base = row.str("base") ?: return@mapNotNull null
            val quote = row.str("quote") ?: return@mapNotNull null
            val pricePrecision = (row.num("precision")?.toIntOrNull() ?: 2).coerceIn(0, 12)
            val amountPrecision = (row.num("amount_precision")?.toIntOrNull() ?: 0).coerceIn(0, 12)
            InstrumentMeta(
                id = SymbolId(MarketType.SPOT, base + quote),
                baseAsset = base,
                quoteAsset = quote,
                priceTickSize = BigDecimal.ONE.movePointLeft(pricePrecision).stripTrailingZeros(),
                quantityStepSize = BigDecimal.ONE.movePointLeft(amountPrecision).stripTrailingZeros(),
                status = "TRADING",
            )
        }

    override fun parseTicker(text: String, symbol: String, now: Long): MarketTicker? {
        val row = jsonArray(json.parseToJsonElement(text)).firstOrNull() as? JsonObject ?: return null
        return tickerOf(row, symbol, now)
    }

    override fun parseTickers(text: String, now: Long): List<MarketTicker> =
        jsonArray(json.parseToJsonElement(text)).mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val pair = row.str("currency_pair") ?: return@mapNotNull null
            tickerOf(row, SpotSymbols.toInternal(pair), now)
        }

    override fun parseKlines(text: String, minutes: Long): List<Kline> {
        val step = minutes * 60_000L
        return jsonArray(json.parseToJsonElement(text)).mapNotNull { item ->
            val a = item as? JsonArray ?: return@mapNotNull null
            if (a.size < 7) return@mapNotNull null
            val seconds = (a[0] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val openTime = seconds * 1_000L
            val open = a[5].toBigDecimalOrNull() ?: return@mapNotNull null
            val high = a[3].toBigDecimalOrNull() ?: return@mapNotNull null
            val low = a[4].toBigDecimalOrNull() ?: return@mapNotNull null
            val close = a[2].toBigDecimalOrNull() ?: return@mapNotNull null
            Kline(
                openTime = openTime,
                closeTime = openTime + step,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = a[6].toBigDecimalOrNull() ?: BigDecimal.ZERO,
                quoteVolume = a[1].toBigDecimalOrNull() ?: BigDecimal.ZERO,
                trades = 0L,
                // 仓库层按「收盘时间是否已过」统一改写当前这根
                closed = true,
            )
        }
    }

    private fun tickerOf(row: JsonObject, symbol: String, now: Long): MarketTicker? {
        val last = row.str("last")?.toBigDecimalOrNull() ?: return null
        val pct = row.str("change_percentage")?.toDoubleOrNull()
        val open = when {
            pct == null || pct <= -100.0 -> last
            else -> last.multiply(
                BigDecimal.valueOf(100.0)
                    .divide(BigDecimal.valueOf(100.0 + pct), MathContext.DECIMAL128),
            )
        }
        return MarketTicker(
            id = SymbolId(MarketType.SPOT, symbol),
            lastPrice = last,
            openPrice = open,
            highPrice = row.str("high_24h")?.toBigDecimalOrNull() ?: last,
            lowPrice = row.str("low_24h")?.toBigDecimalOrNull() ?: last,
            volume = row.str("base_volume")?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            quoteVolume = row.str("quote_volume")?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            updatedAt = now,
        )
    }
}

/** Gate 现货周期码：粗周期是 `7d`/`30d`，与币安的 `1w`/`1M` 只是名字不同。 */
private object GateIntervalCodes {
    private val byMinutes = mapOf(
        1L to "1m", 3L to "3m", 5L to "5m", 15L to "15m", 30L to "30m",
        60L to "1h", 120L to "2h", 240L to "4h", 360L to "6h", 480L to "8h", 720L to "12h",
        1_440L to "1d", 4_320L to "3d", 10_080L to "7d", 43_200L to "30d",
    )

    fun of(minutes: Long): String = byMinutes.getValue(minutes)
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun jsonArray(element: JsonElement): List<JsonElement> =
    (element as? JsonArray)?.toList().orEmpty()

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.num(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement.toBigDecimalOrNull(): BigDecimal? =
    (this as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
