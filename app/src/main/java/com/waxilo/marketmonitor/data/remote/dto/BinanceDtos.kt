package com.waxilo.marketmonitor.data.remote.dto

import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
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

/** `/ticker/24hr` 的字段集合（全量数组与单标的快照共用）。 */
@Serializable
data class TickerDto(
    val symbol: String = "",
    val openPrice: String? = null,
    val highPrice: String? = null,
    val lowPrice: String? = null,
    val lastPrice: String? = null,
    val volume: String? = null,
    val quoteVolume: String? = null,
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

/** REST 24hr 快照映射。 */
fun TickerDto.toDomain(market: MarketType, fallbackUpdatedAt: Long): MarketTicker? {
    if (symbol.isBlank()) return null
    val last = lastPrice?.toBigDecimalOrNull() ?: return null
    // 缺 24h 开盘价时以最新价代位：宁可显示 0.00% 也不让整行消失
    val open = openPrice?.toBigDecimalOrNull() ?: last
    return MarketTicker(
        id = SymbolId(market, symbol),
        lastPrice = last,
        openPrice = open,
        highPrice = highPrice?.toBigDecimalOrNull() ?: last,
        lowPrice = lowPrice?.toBigDecimalOrNull() ?: last,
        volume = volume?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = quoteVolume?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        updatedAt = fallbackUpdatedAt,
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
