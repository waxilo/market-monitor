package com.waxilo.marketmonitor.data.repository

import com.waxilo.marketmonitor.data.local.room.InstrumentDao
import com.waxilo.marketmonitor.data.local.room.KlineDao
import com.waxilo.marketmonitor.data.local.room.TickerDao
import com.waxilo.marketmonitor.data.local.room.toDomain
import com.waxilo.marketmonitor.data.local.room.toEntity
import com.waxilo.marketmonitor.data.remote.BinanceMarketApi
import com.waxilo.marketmonitor.data.remote.ws.MarketWebSocket
import com.waxilo.marketmonitor.data.remote.ws.Streams
import com.waxilo.marketmonitor.data.remote.ws.WsEvent
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.KlineAggregator
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.domain.repository.KlinePage
import com.waxilo.marketmonitor.domain.repository.MarketRepository
import com.waxilo.marketmonitor.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 行情仓库（PRD 3.2）。编排规则：
 * - 列表快照：REST 全量 → 内存 + Room；WS `!miniTicker@arr` 只更新内存，
 *   每秒 1000 行不写库（会持续触发 Room 失效重查），冷启动用上次快照兜底。
 * - K 线：REST 拉基础周期 → 客户端聚合成目标周期 → 结果写缓存；失败时回读缓存并标记离线。
 * - 同一时刻只维持一个市场的一条 WS 连接，订阅集合按引用计数合并。
 */
class MarketRepositoryImpl(
    private val api: BinanceMarketApi,
    private val socket: MarketWebSocket,
    private val tickerDao: TickerDao,
    private val instrumentDao: InstrumentDao,
    private val klineDao: KlineDao,
    private val watchlist: WatchlistRepository,
    private val scope: CoroutineScope,
    initialMarket: MarketType = MarketType.SPOT,
) : MarketRepository {

    private val liveTickers = MutableStateFlow<Map<SymbolId, MarketTicker>>(emptyMap())
    private val klineStream = MutableSharedFlow<Pair<SymbolId, Kline>>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val market = MutableStateFlow(initialMarket)
    private val extraStreams = MutableStateFlow<List<String>>(emptyList())

    /** 详情页订阅的 K 线流与列表页的合并流共用一条连接；市场切换时自动重连。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            market.flatMapLatest { target ->
                // 只订阅当前市场自选交易对的 miniTicker 流，不再订阅全市场 !miniTicker@arr
                val streams = combine(watchlist.watchlist(target), extraStreams) { watched, extras ->
                    buildList {
                        watched.forEach { add(Streams.miniTicker(it.symbol)) }
                        addAll(extras)
                    }.distinct()
                }
                socket.events(target, streams)
            }.collect { event -> handle(market.value, event) }
        }
    }

    /** 切换当前市场（首页 Tab）。K 线订阅由详情页自行增删。 */
    fun selectMarket(target: MarketType) {
        market.value = target
    }

    fun setWatchedStreams(names: List<String>) {
        extraStreams.update { names.distinct() }
    }

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
        if (ticker == null) {
            null
        } else {
            tickerDao.upsertAll(listOf(ticker.toEntity()))
            liveTickers.update { it + (id to ticker) }
            ticker
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    }

    override fun tickers(market: MarketType, quoteAsset: String): Flow<List<MarketTicker>> =
        combine(tickerDao.observeMarket(market.key), liveTickers) { cached, live ->
            val merged = cached.mapNotNull { it.toDomain() }.associateBy { it.id }
                .toMutableMap()
            live.forEach { (id, ticker) -> if (id.market.key == market.key) merged[id] = ticker }
            merged.values
                .filter { it.id.symbol.endsWith(quoteAsset) }
                .sortedByDescending { it.quoteVolume }
        }.distinctUntilChanged()

    override fun ticker(id: SymbolId): Flow<MarketTicker?> =
        combine(
            tickerDao.observeOne(id.market.key, id.symbol),
            liveTickers.map { it[id] }.distinctUntilChanged(),
        ) { cached, live -> live ?: cached?.toDomain() }

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

    override fun klineUpdate(id: SymbolId, interval: CandleInterval): Flow<Kline> =
        klineStream.filter { it.first == id }.map { it.second }

    override fun watchKlineUpdates(id: SymbolId, interval: CandleInterval) {
        val code = interval.apiCode
        if (code.isEmpty()) return
        // 连接是按市场的，订阅现货的合约标的收不到推送，所以先切连接
        if (market.value != id.market) market.value = id.market
        setWatchedStreams(listOf(Streams.kline(id.symbol, code)))
    }

    override fun clearKlineUpdates() {
        setWatchedStreams(emptyList())
    }

    override suspend fun ping(market: MarketType): Boolean = api.ping(market)

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

    private fun handle(target: MarketType, event: WsEvent) {
        when (event) {
            is WsEvent.Ticker -> liveTickers.update { it + (event.ticker.id to event.ticker) }
            is WsEvent.KlineUpdate -> klineStream.tryEmit(SymbolId(target, event.symbol) to event.kline)
            WsEvent.Reconnecting -> Unit
        }
    }

    /** 供 UI 标注「离线数据」：缓存最新一条与当前时间的差值。 */
    suspend fun isStale(market: MarketType, thresholdMs: Long = STALE_AFTER_MS): Boolean {
        val last = tickerDao.lastUpdatedAt(market.key) ?: return true
        return System.currentTimeMillis() - last > thresholdMs
    }

    companion object {
        /** 每个「标的 × 周期」最多缓存的蜡烛根数。 */
        const val MAX_CACHED_KLINES = 1500
        const val STALE_AFTER_MS = 30_000L
    }
}
