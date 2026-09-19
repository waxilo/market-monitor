package com.waxilo.marketmonitor.domain.model

import java.math.BigDecimal
import java.math.MathContext

/** 行情实体的复合身份：同一 symbol 在现货与合约是不同标的。 */
data class SymbolId(
    val market: MarketType,
    val symbol: String,
) {
    /** 本地存储用的稳定键。 */
    val storageKey: String get() = "${market.key}:$symbol"

    companion object {
        fun fromStorageKey(key: String): SymbolId? {
            val idx = key.indexOf(':')
            if (idx <= 0 || idx == key.lastIndex) return null
            return SymbolId(MarketType.fromKey(key.substring(0, idx)), key.substring(idx + 1))
        }
    }
}

/** 24h 行情快照（列表页一行数据）。 */
data class MarketTicker(
    val id: SymbolId,
    val lastPrice: BigDecimal,
    val openPrice: BigDecimal,
    val highPrice: BigDecimal,
    val lowPrice: BigDecimal,
    val volume: BigDecimal,
    val quoteVolume: BigDecimal,
    /** 数据产生时间（epoch ms），用于离线判定。 */
    val updatedAt: Long,
) {
    /** 涨跌幅统一由 24h 开盘价推算，避免现货/合约字段语义差异。 */
    val changePercent: Double
        get() = if (openPrice.signum() == 0) 0.0 else
            lastPrice.subtract(openPrice)
                .divide(openPrice, MathContext.DECIMAL128)
                .multiply(BigDecimal.valueOf(100))
                .toDouble()
}

/** 交易规则中与展示/校验相关的部分（来自 exchangeInfo）。 */
data class InstrumentMeta(
    val id: SymbolId,
    val baseAsset: String,
    val quoteAsset: String,
    /** 价格最小变动单位，决定预警目标价校验与显示位数。 */
    val priceTickSize: BigDecimal,
    /** 数量精度。 */
    val quantityStepSize: BigDecimal,
    val status: String,
) {
    val isTrading: Boolean get() = status.equals("TRADING", ignoreCase = true)
}
