package com.waxilo.marketmonitor.data.remote.dto

import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.Position
import com.waxilo.marketmonitor.domain.model.PositionSide
import com.waxilo.marketmonitor.domain.model.SpotBalance
import com.waxilo.marketmonitor.domain.model.SymbolId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * 币安公开接口 DTO（PRD 3.1）。
 * 两市场字段基本同构，这里按「字段并集 + 可空」建模，差异集中在 mapper 里处理；
 * 所有金额保持字符串，进 domain 再转 BigDecimal，避免 Double 精度损失。
 */

@Serializable
data class ExchangeInfoDto(
    val symbols: List<SymbolDto> = emptyList(),
)

@Serializable
data class SymbolDto(
    val symbol: String,
    val status: String = "",
    val baseAsset: String = "",
    val quoteAsset: String = "",
    /** 现货为 TRADING；合约另有 contractType/onboardDate/updateDate，此处只取展示需要的。 */
    val filters: List<FilterDto> = emptyList(),
)

@Serializable
data class FilterDto(
    val filterType: String,
    val tickSize: String? = null,
    val stepSize: String? = null,
)

/** `/ticker/24hr` 与 WS `24hrMiniTicker` 的公共字段集合。 */
@Serializable
data class TickerDto(
    val symbol: String = "",
    /** miniTicker 连交易对也是短字段名 `s`。 */
    val s: String? = null,
    val openPrice: String? = null,
    val highPrice: String? = null,
    val lowPrice: String? = null,
    val lastPrice: String? = null,
    /** miniTicker 用 c/o/h/l/v/q 短字段名。 */
    val c: String? = null,
    val o: String? = null,
    val h: String? = null,
    val l: String? = null,
    val v: String? = null,
    val q: String? = null,
    val volume: String? = null,
    val quoteVolume: String? = null,
    /** 事件产生时间（WS 有，REST 无）。 */
    val E: Long? = null,
)

@Serializable
data class WsKlineDto(
    /** t / T 的 getter 同为 getT()，必须显式改名，否则 JVM 签名冲突。 */
    @SerialName("t") val openTimeMs: Long = 0L,
    @SerialName("T") val closeTimeMs: Long = 0L,
    val o: String = "0",
    val c: String = "0",
    val h: String = "0",
    val l: String = "0",
    val v: String = "0",
    val q: String = "0",
    val n: Long = 0L,
    val x: Boolean = false,
)

@Serializable
data class WsKlineEventDto(
    val s: String = "",
    val E: Long? = null,
    val k: WsKlineDto = WsKlineDto(),
)

/** 最近成交价，轮询兜底用（`/ticker/price`）。 */
@Serializable
data class PriceDto(
    val symbol: String = "",
    val price: String = "0",
)

fun SymbolDto.toDomain(market: MarketType): InstrumentMeta? {
    if (symbol.isBlank()) return null
    // PRICE_FILTER 给价格精度，LOT_SIZE 给数量精度；缺任一说明该标的不完整，丢弃比兜一个 0 安全
    val priceTick = filters.firstOrNull { it.filterType == "PRICE_FILTER" }?.tickSize ?: return null
    val stepSize = filters.firstOrNull { it.filterType == "LOT_SIZE" }?.stepSize ?: priceTick
    return InstrumentMeta(
        id = SymbolId(market, symbol),
        baseAsset = baseAsset,
        quoteAsset = quoteAsset,
        priceTickSize = priceTick.toBigDecimalOrNull()?.stripTrailingZeros() ?: return null,
        quantityStepSize = stepSize.toBigDecimalOrNull() ?: BigDecimal.ONE,
        status = status,
    )
}

/** REST 全量快照与 WS 增量共用一条映射：短字段优先，缺失时回落到长字段名。 */
fun TickerDto.toDomain(market: MarketType, fallbackUpdatedAt: Long): MarketTicker? {
    val code = symbol.ifBlank { s.orEmpty() }
    if (code.isBlank()) return null
    val last = (lastPrice ?: c)?.toBigDecimalOrNull() ?: return null
    // 缺 24h 开盘价时以最新价代位：宁可显示 0.00% 也不让整行消失
    val open = (openPrice ?: o)?.toBigDecimalOrNull() ?: last
    return MarketTicker(
        id = SymbolId(market, code),
        lastPrice = last,
        openPrice = open,
        highPrice = (highPrice ?: h)?.toBigDecimalOrNull() ?: last,
        lowPrice = (lowPrice ?: l)?.toBigDecimalOrNull() ?: last,
        volume = (volume ?: v)?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = (quoteVolume ?: q)?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        updatedAt = E ?: fallbackUpdatedAt,
    )
}

/**
 * `/klines` 的数组形式，官方顺序为
 * [开盘时间, open, high, low, close, volume, 收盘时间, quoteVolume, trades, takerBuyBase, takerBuyQuote, ignore]。
 */
fun List<String>.toKline(): Kline? {
    if (size < 9) return null
    val openTime = this[0].toLongOrNull() ?: return null
    val closeTime = this[6].toLongOrNull() ?: openTime
    val o = this[1].toBigDecimalOrNull() ?: return null
    val h = this[2].toBigDecimalOrNull() ?: return null
    val l = this[3].toBigDecimalOrNull() ?: return null
    val c = this[4].toBigDecimalOrNull() ?: return null
    return Kline(
        openTime = openTime,
        closeTime = closeTime,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = this[5].toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = this[7].toBigDecimalOrNull() ?: BigDecimal.ZERO,
        trades = this[8].toLongOrNull() ?: 0L,
        // 币安不标记最后一根是否收盘，由 closeTime 与当前时间关系决定，交由仓库层填充
        closed = true,
    )
}

@Serializable
data class ErrorResponseDto(
    val code: Int = 0,
    val msg: String = "",
)

/**
 * `/v2/positionRisk` 单行（币安与 Aster 同构，金额全部是字符串）。
 * 字段给默认值：接口偶尔省略 0 值字段，缺省按「空仓位」处理。
 */
@Serializable
data class PositionRiskDto(
    val symbol: String = "",
    val positionAmt: String = "0",
    val entryPrice: String = "0",
    val markPrice: String = "0",
    val unRealizedProfit: String = "0",
    val notional: String = "0",
    val leverage: String = "1",
    val marginType: String = "",
    val isolatedMargin: String = "0",
    val liquidationPrice: String = "0",
    val positionSide: String = "BOTH",
)

/** 空仓位（positionAmt 为 0）返回 null，让仓库层直接过滤。 */
fun PositionRiskDto.toDomain(market: MarketType): Position? {
    if (symbol.isBlank()) return null
    val amt = positionAmt.toBigDecimalOrNull() ?: return null
    if (amt.signum() == 0) return null
    return Position(
        id = SymbolId(market, symbol),
        side = if (amt.signum() > 0) PositionSide.LONG else PositionSide.SHORT,
        quantity = amt.abs(),
        entryPrice = entryPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        markPrice = markPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        unrealizedPnl = unRealizedProfit.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        notional = notional.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        leverage = leverage.toIntOrNull() ?: 1,
        marginType = marginType,
        isolatedMargin = isolatedMargin.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        liquidationPrice = liquidationPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    )
}

/** `/api/v3/account` 里我们关心的部分：现货余额表。 */
@Serializable
data class SpotAccountDto(
    val balances: List<SpotBalanceDto> = emptyList(),
)

@Serializable
data class SpotBalanceDto(
    val asset: String = "",
    val free: String = "0",
    val locked: String = "0",
)

/** 总量为 0 的资产行返回 null（币安会返回几百行零余额，全进 UI 没有意义）。 */
fun SpotBalanceDto.toDomain(): SpotBalance? {
    if (asset.isBlank()) return null
    val free = free.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val locked = locked.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val total = free.add(locked)
    if (total.signum() == 0) return null
    return SpotBalance(asset = asset, quantity = total, available = free)
}
