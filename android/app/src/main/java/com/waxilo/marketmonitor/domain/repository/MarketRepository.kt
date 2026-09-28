package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 数据来自远端还是本地缓存，UI 据此显示「离线数据」标记（PRD 3.2）。 */
enum class DataOrigin { REMOTE, CACHE }

/** 一次 K 线读取结果。`hasMore` 表示还能往更早方向翻页。 */
data class KlinePage(
    val klines: List<Kline>,
    val hasMore: Boolean,
    val origin: DataOrigin,
)

/**
 * 行情数据入口（PRD 3.1 / 3.2）。实现负责 REST+Room 缓存的编排（WS 链路已移除），
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

    /**
     * 只刷新单个标的（预警轮询用，权重 1，远低于全量的 80）。
     * 网络失败返回 null，交给上层的 ticker 流继续回放缓存。
     */
    suspend fun refreshTicker(id: SymbolId): MarketTicker?

    /**
     * 行情快照流：Room 缓存的响应式视图，REST 轮询落库即驱动发射。
     * [quoteAsset] 为 null 时不过滤报价资产——自选列表按 SymbolId 精确取行，
     * 需要看到 BNBUSDC、XRPTUSD 这类非 USDT 报价的关注标的。
     */
    fun tickers(market: MarketType, quoteAsset: String? = "USDT"): Flow<List<MarketTicker>>

    fun ticker(id: SymbolId): Flow<MarketTicker?>

    /**
     * 列表迷你走势线的收盘价序列（最近 [limit] 根 [interval] 蜡烛，时间升序）。
     *
     * 只读本地缓存、绝不触发网络：列表滑到哪就画到哪，缺数据时返回空列表让 UI 画空白，
     * 而不是为了补走势线在首页发起 N 个 K 线请求。
     */
    suspend fun recentCloses(id: SymbolId, interval: CandleInterval, limit: Int = 24): List<Double>

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
     * 最后一根蜡烛的实时更新流（REST 轮询实现，WS 链路已移除）。
     * 自定义周期下推的是基础周期原始蜡烛，展示前需与已加载序列一起交给 KlineAggregator.aggregate 重算。
     * 冷流：收集即开始轮询，取消收集即停止。
     */
    fun klineUpdate(id: SymbolId, interval: CandleInterval): Flow<Kline>

    /** 连通性探测，供设置页与容灾切换使用。 */
    suspend fun ping(market: MarketType): Boolean

    /**
     * 清空某市场的本地行情缓存（快照/交易对清单/K 线），自选与预警规则保留。
     * 切换合约行情接口时调用：不同盘口（Aster vs 币安）的数据不可混进同一 (market, symbol)。
     */
    suspend fun clearMarketCache(market: MarketType)
}

/**
 * 一组标的的快照流（预警检测与规则列表共用）。
 * 取不到价的标的不出现在结果里，避免调用方把「没有数据」当成「价格为 0」。
 */
fun MarketRepository.tickerSnapshots(ids: List<SymbolId>): Flow<Map<SymbolId, MarketTicker>> {
    val distinct = ids.distinct()
    if (distinct.isEmpty()) return flowOf(emptyMap())
    return combine(distinct.map { id -> ticker(id).map { id to it } }) { rows ->
        rows.asSequence().mapNotNull { (id, ticker) -> ticker?.let { id to it } }.toMap()
    }
}

/** 自选列表（PRD FR-1.2），顺序即展示顺序。 */
interface WatchlistRepository {
    fun watchlist(market: MarketType): Flow<List<SymbolId>>
    suspend fun contains(id: SymbolId): Boolean
    suspend fun add(id: SymbolId)
    suspend fun remove(id: SymbolId)
    suspend fun move(id: SymbolId, toIndex: Int)
}
