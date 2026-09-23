package com.waxilo.marketmonitor.domain.model

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.math.MathContext

/** 行情实体的复合身份：同一 symbol 在现货与合约是不同标的。 */
@Immutable
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
@Immutable
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
@Immutable
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

/** 永续仓位方向。币安用 positionAmt 的正负表达多空。 */
enum class PositionSide(val label: String) {
    LONG("多"),
    SHORT("空"),
}

/**
 * 用户当前永续仓位（币安私有接口 /fapi/v2/positionRisk）。
 * 金额为 0 的「空仓位」由仓库层直接过滤，不会流到 UI。
 */
@Immutable
data class Position(
    val id: SymbolId,
    val side: PositionSide,
    /** 持仓数量（绝对值，方向看 [side]）。 */
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val markPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    /** 名义价值（数量×标记价），汇总仓位规模用。 */
    val notional: BigDecimal,
    val leverage: Int,
    /** CROSS / ISOLATED，接口原样返回，不做枚举以免新值炸解析。 */
    val marginType: String,
    /** 0 = 全仓（该字段仅逐仓有意义）。 */
    val isolatedMargin: BigDecimal,
    /** 0 = 不展示（逐仓或无强平价时接口给 0）。 */
    val liquidationPrice: BigDecimal,
)

/**
 * 现货持仓（币安私有接口 /api/v3/account 的 balances 行）。
 * 现货没有「仓位」语义，能给出的就是持有量；估值由页面按公共行情折成 USDT，
 * 取不到价的资产只显示数量，不猜价值。
 */
@Immutable
data class SpotBalance(
    val asset: String,
    /** 可用 + 冻结的总持有量。 */
    val quantity: BigDecimal,
    val available: BigDecimal,
)
