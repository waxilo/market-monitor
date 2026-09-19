package com.waxilo.marketmonitor.data.local.room

import androidx.room.Entity
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import java.math.BigDecimal

/**
 * 缓存实体（PRD 3.2）。所有金额以字符串落库，读回再转 BigDecimal，
 * 避免 Double 精度损失与 Room 类型转换器带来的隐式行为。
 * 主键一律含 market，现货与合约同名交易对互不覆盖（PRD 3.2 市场隔离）。
 */

@Entity(tableName = "ticker", primaryKeys = ["market", "symbol"])
data class TickerEntity(
    val market: String,
    val symbol: String,
    val lastPrice: String,
    val openPrice: String,
    val highPrice: String,
    val lowPrice: String,
    val volume: String,
    val quoteVolume: String,
    val updatedAt: Long,
)

@Entity(tableName = "instrument", primaryKeys = ["market", "symbol"])
data class InstrumentEntity(
    val market: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val status: String,
    val priceTickSize: String,
    val quantityStepSize: String,
    val syncedAt: Long,
)

@Entity(tableName = "kline", primaryKeys = ["market", "symbol", "intervalKey", "openTime"])
data class KlineEntity(
    val market: String,
    val symbol: String,
    /** CandleInterval.storageKey，如 `o:5m` / `c:10`。 */
    val intervalKey: String,
    val openTime: Long,
    val closeTime: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
    val quoteVolume: String,
    val trades: Long,
    val closed: Int,
)

@Entity(tableName = "watchlist", primaryKeys = ["market", "symbol"])
data class WatchlistEntity(
    val market: String,
    val symbol: String,
    val position: Int,
    val addedAt: Long,
)

fun TickerEntity.toDomain(): MarketTicker? {
    val last = lastPrice.toBigDecimalOrNull() ?: return null
    return MarketTicker(
        id = SymbolId(MarketType.fromKey(market), symbol),
        lastPrice = last,
        openPrice = openPrice.toBigDecimalOrNull() ?: last,
        highPrice = highPrice.toBigDecimalOrNull() ?: last,
        lowPrice = lowPrice.toBigDecimalOrNull() ?: last,
        volume = volume.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = quoteVolume.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        updatedAt = updatedAt,
    )
}

fun MarketTicker.toEntity(): TickerEntity = TickerEntity(
    market = id.market.key,
    symbol = id.symbol,
    lastPrice = lastPrice.toPlainString(),
    openPrice = openPrice.toPlainString(),
    highPrice = highPrice.toPlainString(),
    lowPrice = lowPrice.toPlainString(),
    volume = volume.toPlainString(),
    quoteVolume = quoteVolume.toPlainString(),
    updatedAt = updatedAt,
)

fun InstrumentEntity.toDomain(): InstrumentMeta? {
    val tick = priceTickSize.toBigDecimalOrNull() ?: return null
    return InstrumentMeta(
        id = SymbolId(MarketType.fromKey(market), symbol),
        baseAsset = baseAsset,
        quoteAsset = quoteAsset,
        priceTickSize = tick,
        quantityStepSize = quantityStepSize.toBigDecimalOrNull() ?: BigDecimal.ONE,
        status = status,
    )
}

fun InstrumentMeta.toEntity(syncedAt: Long): InstrumentEntity = InstrumentEntity(
    market = id.market.key,
    symbol = id.symbol,
    baseAsset = baseAsset,
    quoteAsset = quoteAsset,
    status = status,
    priceTickSize = priceTickSize.toPlainString(),
    quantityStepSize = quantityStepSize.toPlainString(),
    syncedAt = syncedAt,
)

fun KlineEntity.toDomain(): Kline? {
    val o = open.toBigDecimalOrNull() ?: return null
    val h = high.toBigDecimalOrNull() ?: return null
    val l = low.toBigDecimalOrNull() ?: return null
    val c = close.toBigDecimalOrNull() ?: return null
    return Kline(
        openTime = openTime,
        closeTime = closeTime,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = volume.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = quoteVolume.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        trades = trades,
        closed = closed == 1,
    )
}

fun Kline.toEntity(id: SymbolId, intervalKey: String): KlineEntity = KlineEntity(
    market = id.market.key,
    symbol = id.symbol,
    intervalKey = intervalKey,
    openTime = openTime,
    closeTime = closeTime,
    open = open.toPlainString(),
    high = high.toPlainString(),
    low = low.toPlainString(),
    close = close.toPlainString(),
    volume = volume.toPlainString(),
    quoteVolume = quoteVolume.toPlainString(),
    trades = trades,
    closed = if (closed) 1 else 0,
)

fun WatchlistEntity.toSymbolId(): SymbolId = SymbolId(MarketType.fromKey(market), symbol)
