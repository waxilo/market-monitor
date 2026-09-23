package com.waxilo.marketmonitor.data.repository

import com.waxilo.marketmonitor.data.local.room.InstrumentDao
import com.waxilo.marketmonitor.data.local.room.KlineDao
import com.waxilo.marketmonitor.data.local.room.TickerDao
import com.waxilo.marketmonitor.data.local.room.toDomain
import com.waxilo.marketmonitor.data.local.room.toEntity
import com.waxilo.marketmonitor.data.remote.BinanceMarketApi
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.KlineAggregator
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.Position
import com.waxilo.marketmonitor.domain.model.SpotBalance
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.domain.repository.KlinePage
import com.waxilo.marketmonitor.domain.repository.MarketRepository
import com.waxilo.marketmonitor.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 行情仓库（PRD 3.2）。编排规则：
 * - 列表快照：REST 拉自选交易对 → Room；UI 通过 [tickerDao] 的 Room 流拿到更新。
 *   （WS 链路已整体移除：代理/弱网下 WS 长连接反而不如短请求稳。）
 * - K 线：REST 拉基础周期 → 客户端聚合成目标周期 → 结果写缓存；失败时回读缓存并标记离线。
 * - 最后一根蜡烛的实时更新由 [klineUpdate] 的 REST 轮询承担。
 */
class MarketRepositoryImpl(
    private val api: BinanceMarketApi,
    private val tickerDao: TickerDao,
    private val instrumentDao: InstrumentDao,
    private val klineDao: KlineDao,
    private val watchlist: WatchlistRepository,
) : MarketRepository {

    override suspend fun syncInstruments(market: MarketType): Int {
        val syncedAt = System.currentTimeMillis()
        val instruments = api.exchangeInfo(market)
        instrumentDao.upsertAll(instruments.map { it.toEntity(syncedAt) })
        // 本轮未返回的标的（下架）必须清掉，否则搜索页仍能选到已停止交易的交易对
        instrumentDao.deleteStale(market.key, syncedAt)
        return instruments.size
    }

    override fun instruments(market: MarketType): Flow<List<InstrumentMeta>> =
        instrumentDao.observeMarket(market.key).map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun instrument(id: SymbolId): Flow<InstrumentMeta?> =
        instrumentDao.observeMarket(id.market.key).map { rows ->
            rows.firstOrNull { it.symbol == id.symbol }?.toDomain()
        }

    override suspend fun refreshTickers(market: MarketType): Int {
        // 只刷新关注（自选）交易对，不再全量拉取（PRD 4.3：仅刷新关注 + 控制权重）
        val watched = watchlist.watchlist(market).first().filter { it.market == market }
        if (watched.isEmpty()) return 0
        var count = 0
        watched.forEach { id ->
            if (refreshTicker(id) != null) count++
        }
        return count
    }

    override suspend fun refreshTicker(id: SymbolId): MarketTicker? = try {
        val ticker = api.ticker(id.market, id.symbol)
        if (ticker != null) tickerDao.upsertAll(listOf(ticker.toEntity()))
        ticker
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    }

    /** 快照流即 Room 流：REST 轮询每次落库都会驱动一次发射，UI 侧自带合并节流。 */
    override fun tickers(market: MarketType, quoteAsset: String): Flow<List<MarketTicker>> =
        tickerDao.observeMarket(market.key).map { rows ->
            rows.mapNotNull { it.toDomain() }
                .filter { it.id.symbol.endsWith(quoteAsset) }
                .sortedByDescending { it.quoteVolume }
        }.distinctUntilChanged()

    override fun ticker(id: SymbolId): Flow<MarketTicker?> =
        tickerDao.observeOne(id.market.key, id.symbol).map { it?.toDomain() }

    override suspend fun recentCloses(id: SymbolId, interval: CandleInterval, limit: Int): List<Double> {
        val marketKey = id.market.key
        // 首选周期直接命中
        readCloses(marketKey, id.symbol, interval, limit)?.let { return it }
        // 降级：用户可能从没在首选周期上停留过（缓存里只有 1m/15m），
        // 这时退到「已缓存周期里最粗的那个」画走势线。
        // 挑最粗的是因为走势线要表达「最近一段的趋势」，越细的周期趋势越吵。
        val fallback = klineDao.cachedIntervalCounts(marketKey, id.symbol)
            .mapNotNull { CandleInterval.fromStorageKey(it.intervalKey) }
            .maxByOrNull { it.minutes }
            ?: return emptyList()
        return readCloses(marketKey, id.symbol, fallback, limit).orEmpty()
    }

    /** 读到数据才返回非 null；根数不足 2 根画不出线，等同于没数据。 */
    private suspend fun readCloses(
        marketKey: String,
        symbol: String,
        interval: CandleInterval,
        limit: Int,
    ): List<Double>? {
        val key = interval.storageKey
        // 先看有没有数据：没有就早退，省掉一次必然返回空的时间段查询
        if (klineDao.countFor(marketKey, symbol, key) == 0) return null
        // 以「当前时间往前推 limit 个周期」为下界，避免对每个自选都扫全表
        val from = System.currentTimeMillis() - interval.durationMs * limit
        val closes = klineDao.recentCloses(marketKey, symbol, key, from, limit)
            .mapNotNull { it.toDoubleOrNull() }
        return closes.takeIf { it.size >= 2 }
    }

    override suspend fun klines(id: SymbolId, interval: CandleInterval, limit: Int): KlinePage =
        fetch(id, interval, limit, startTime = null, endTime = null)
            ?: KlinePage(
                klines = readCache(id, interval, limit, before = null),
                hasMore = true,
                origin = DataOrigin.CACHE,
            )

    override suspend fun olderKlines(
        id: SymbolId,
        interval: CandleInterval,
        beforeOpenTime: Long,
        limit: Int,
    ): KlinePage = fetch(id, interval, limit, startTime = null, endTime = beforeOpenTime - 1)
        ?: KlinePage(
            klines = readCache(id, interval, limit, before = beforeOpenTime),
            hasMore = true,
            origin = DataOrigin.CACHE,
        )

    /** 网络异常降级为读缓存（PRD 3.2「断网展示缓存」）；协程取消必须原样上抛。 */
    private suspend fun fetch(
        id: SymbolId,
        interval: CandleInterval,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): KlinePage? = try {
        fetchAndCache(id, interval, limit, startTime, endTime)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    }

    /**
     * 最后一根蜡烛的实时更新流：REST 轮询（原 WS `@kline` 推送已移除）。
     * 冷流，由详情页 flatMapLatest 驱动——换周期自动停旧起新，离开页面取消收集即停。
     * 只取最近 2 根、不落缓存：这是「盯当前这一根」的轻量流，历史序列另走 klines()。
     * 自定义周期下 [CandleInterval.apiCode] 即基础周期码，推的是原始蜡烛，
     * 展示前由上层与已加载序列一起交给 KlineAggregator 重聚合。
     */
    override fun klineUpdate(id: SymbolId, interval: CandleInterval): Flow<Kline> = flow {
        val code = interval.apiCode
        if (code.isEmpty()) return@flow
        var emitted: Kline? = null
        while (true) {
            val latest = try {
                api.klines(id.market, id.symbol, code, limit = 2).lastOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // 网络抖动跳过本轮，下一轮再试；断线兜底由 UI 的离线标记负责
            }
            if (latest != null && latest != emitted) {
                emitted = latest
                emit(latest)
            }
            delay(KLINE_POLL_MS)
        }
    }

    override suspend fun ping(market: MarketType): Boolean = api.ping(market)

    override suspend fun clearMarketCache(market: MarketType) {
        tickerDao.clear(market.key)
        instrumentDao.clear(market.key)
        klineDao.clearMarket(market.key)
    }

    override suspend fun positions(): List<Position> = api.positions()

    override suspend fun spotBalances(): List<SpotBalance> = api.spotBalances()

    /**
     * 拉取 + 聚合 + 落缓存。自定义周期请求基础周期数据后合并，
     * 缓存里存的是聚合结果，因此离线时也能按用户选的周期回读。
     */
    private suspend fun fetchAndCache(
        id: SymbolId,
        interval: CandleInterval,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): KlinePage {
        val base = interval.baseInterval ?: error("周期缺少基础周期")
        // 聚合需要整数倍根数，多取一点避免最后一桶总是不够
        val rawLimit = (limit.toLong() * interval.aggregateRatio)
            .coerceAtMost(BinanceMarketApi.MAX_KLINE_LIMIT.toLong())
            .toInt()
        val raw = api.klines(id.market, id.symbol, base.apiCode, rawLimit, startTime, endTime)
        val now = System.currentTimeMillis()
        val stamped = raw.map { if (it.closeTime > now) it.copy(closed = false) else it }
        val aggregated = KlineAggregator.dedupeByOpenTime(
            KlineAggregator.aggregate(stamped, interval),
        )
        klineDao.upsertAll(aggregated.map { it.toEntity(id, interval.storageKey) })
        klineDao.trim(id.market.key, id.symbol, interval.storageKey, MAX_CACHED_KLINES)
        return KlinePage(
            klines = aggregated.takeLast(limit),
            hasMore = raw.size >= rawLimit,
            origin = DataOrigin.REMOTE,
        )
    }

    private suspend fun readCache(
        id: SymbolId,
        interval: CandleInterval,
        limit: Int,
        before: Long?,
    ): List<Kline> {
        val rows = if (before == null) {
            klineDao.latest(id.market.key, id.symbol, interval.storageKey, limit)
        } else {
            klineDao.before(id.market.key, id.symbol, interval.storageKey, before, limit)
        }
        return rows.sortedBy { it.openTime }.mapNotNull { it.toDomain() }
    }

    /** 供 UI 标注「离线数据」：缓存最新一条与当前时间的差值。 */
    suspend fun isStale(market: MarketType, thresholdMs: Long = STALE_AFTER_MS): Boolean {
        val last = tickerDao.lastUpdatedAt(market.key)
        if (last == null) {
            // 该市场一条缓存都没有有两种可能：网络从没通过（自选非空，该报离线），
            // 或自选本来就空着（没东西可拉，不该吓唬用户「网络中断」）
            return watchlist.watchlist(market).first().isNotEmpty()
        }
        return System.currentTimeMillis() - last > thresholdMs
    }

    companion object {
        /** 每个「标的 × 周期」最多缓存的蜡烛根数。 */
        const val MAX_CACHED_KLINES = 1500
        const val STALE_AFTER_MS = 30_000L

        /** 详情页最后一根蜡烛的 REST 轮询间隔。 */
        const val KLINE_POLL_MS = 2_000L
    }
}
