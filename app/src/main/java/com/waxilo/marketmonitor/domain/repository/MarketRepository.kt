package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import kotlinx.coroutines.flow.Flow

/** 数据来自远端还是本地缓存，UI 据此显示「离线数据」标记（PRD 3.2）。 */
enum class DataOrigin { REMOTE, CACHE }

/** 一次 K 线读取结果。`hasMore` 表示还能往更早方向翻页。 */
data class KlinePage(
    val klines: List<Kline>,
    val hasMore: Boolean,
    val origin: DataOrigin,
)

/**
 * 行情数据入口（PRD 3.1 / 3.2）。实现负责 REST+WS+缓存三者的优先级编排，
 * 上层只看到统一模型，不感知现货/合约差异。
 */
interface MarketRepository {

    /** 同步交易规则（exchangeInfo）。返回可交易标的数量。 */
    suspend fun syncInstruments(market: MarketType): Int

    /** 本地已缓存的交易规则，不触发网络。 */
    fun instruments(market: MarketType): Flow<List<InstrumentMeta>>

    fun instrument(id: SymbolId): Flow<InstrumentMeta?>

    /** 主动拉取 24h 行情并落库；返回本次更新的行数。 */
    suspend fun refreshTickers(market: MarketType): Int

    /** 行情快照流：先回放缓存，随后由 WS 增量覆盖。 */
    fun tickers(market: MarketType, quoteAsset: String = "USDT"): Flow<List<MarketTicker>>

    fun ticker(id: SymbolId): Flow<MarketTicker?>

    /** 历史 K 线（最近 limit 根，含自定义周期聚合）。 */
    suspend fun klines(id: SymbolId, interval: CandleInterval, limit: Int = 300): KlinePage

    /** 往更早翻页，`beforeOpenTime` 为当前最早一根的开盘时间。 */
    suspend fun olderKlines(
        id: SymbolId,
        interval: CandleInterval,
        beforeOpenTime: Long,
        limit: Int = 300,
    ): KlinePage

    /**
     * 最后一根蜡烛的实时更新流（WS `@kline`）。
     * 自定义周期下推的是基础周期原始蜡烛，展示前需与已加载序列一起交给 KlineAggregator.aggregate 重算。
     */
    fun klineUpdate(id: SymbolId, interval: CandleInterval): Flow<Kline>

    /**
     * 让 WS 开始推送该交易对的 K 线（按 [CandleInterval.apiCode] 即基础周期）。
     * 与 [klineUpdate] 配对：进入详情页订阅、离开时 [clearKlineUpdates]。
     */
    fun watchKlineUpdates(id: SymbolId, interval: CandleInterval)

    /** 退订全部 K 线流，列表页只保留合并快照流。 */
    fun clearKlineUpdates()

    /** 连通性探测，供设置页与容灾切换使用。 */
    suspend fun ping(market: MarketType): Boolean
}

/** 自选列表（PRD FR-1.2），顺序即展示顺序。 */
interface WatchlistRepository {
    fun watchlist(market: MarketType): Flow<List<SymbolId>>
    suspend fun contains(id: SymbolId): Boolean
    suspend fun add(id: SymbolId)
    suspend fun remove(id: SymbolId)
    suspend fun move(id: SymbolId, toIndex: Int)
}
