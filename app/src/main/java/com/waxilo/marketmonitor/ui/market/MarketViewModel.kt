package com.waxilo.marketmonitor.ui.market

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 首页页签：仅自选关注列表（PRD 4.3 不展示全市场）。 */
enum class MarketTab(val label: String) {
    WATCHLIST("自选"),
}

/** 列表一行需要的展示数据，全部已在领域层格式化完毕，Compose 只做排版。 */
@Immutable
data class TickerRow(
    val id: SymbolId,
    val baseAsset: String,
    val marketLabel: String,
    val price: String,
    val changePercent: Double,
    val quoteVolume: String,
    val watched: Boolean,
    /** 近 N 根收盘价，供行内迷你走势线使用；长度不足 2 时列表画空白。 */
    val trend: List<Double> = emptyList(),
)

@Immutable
data class MarketUiState(
    val market: MarketType = MarketType.SPOT,
    val tab: MarketTab = MarketTab.WATCHLIST,
    val quoteAsset: String = "USDT",
    val rows: List<TickerRow> = emptyList(),
    val watchCount: Int = 0,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    /** 涨跌家数：头部概览条用。合并成一次遍历，避免在组合里反复 filter。 */
    val advancing: Int get() = rows.count { it.changePercent > 0 }
    val declining: Int get() = rows.count { it.changePercent < 0 }
}

/**
 * 行情首页状态（PRD FR-1.1 / FR-1.2）。
 * 数据来自仓库层的「Room 缓存 + WS 增量」合并流，界面本身不碰网络。
 */
class MarketViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.marketRepository
    private val watchlist = container.watchlistRepository

    private val activeMarket = MutableStateFlow(MarketType.SPOT)
    private val quoteAsset = MutableStateFlow("USDT")
    private val tab = MutableStateFlow(MarketTab.WATCHLIST)
    private val flags = MutableStateFlow(Flags())

    /** 交易规则每个市场只需同步一次，重复请求只是白耗权重。 */
    private val syncedMarkets = mutableSetOf<MarketType>()

    /** 定时自动拉取 24h 快照的任务：WS 在线时价格已实时，轮询仅兜底断线场景。 */
    private var autoRefreshJob: Job? = null

    /** 串行化拖动排序的落库（见 [moveWatch] 的说明）。 */
    private val reorderMutex = Mutex()

    /**
     * 迷你走势线数据：自选的「最近 24 根 1h 收盘价」。
     *
     * 单独放一个 StateFlow 而不是并进 500ms 的 tickers 流：走势线来自本地缓存、
     * 变化极慢，如果每次 WS 刷新都重查一遍 Room 就是纯浪费。这里只在自选集合
     * 变化或手动刷新时重算。
     */
    private val trends = MutableStateFlow<Map<SymbolId, List<Double>>>(emptyMap())

    private data class Flags(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val offline: Boolean = false,
        val error: String? = null,
    )

    private data class Source(
        val market: MarketType,
        val tickers: List<MarketTicker>,
        val watched: List<SymbolId>,
        val instruments: Map<SymbolId, InstrumentMeta>,
        val trends: Map<SymbolId, List<Double>>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sources = combine(activeMarket, quoteAsset) { market, quote -> market to quote }
        .flatMapLatest { (market, quote) ->
            combine(
                // WS 推送是实时的，sample 500ms 让 UI 每 0.5 秒合并一次最新行情（PRD 4.3）
                repository.tickers(market, quote).sample(500),
                watchlist.watchlist(market),
                repository.instruments(market),
                trends,
            ) { tickers, watched, instruments, trendMap ->
                Source(market, tickers, watched, instruments.associateBy { it.id }, trendMap)
            }
        }

    val state: StateFlow<MarketUiState> = combine(sources, tab, flags) { source, selectedTab, current ->
        val watchedIds = source.watched.toSet()
        // 首页仅自选关注列表，按用户手动排序展示。
        //
        // 旧版对每个自选 id 做一次 tickers.firstOrNull（O(n·m)，自选多时每 500ms
        // 一次刷新就要扫几十万次比较）。这里把 tickers 先索引成 Map，整体降到 O(n+m)。
        val byId = HashMap<SymbolId, MarketTicker>(source.tickers.size)
        source.tickers.forEach { byId[it.id] = it }
        val rows = source.watched.mapNotNull { id -> byId[id] }
            .map { ticker -> ticker.toRow(source, watchedIds) }
        MarketUiState(
            market = source.market,
            tab = selectedTab,
            quoteAsset = quoteAsset.value,
            rows = rows,
            watchCount = watchedIds.size,
            loading = current.loading,
            refreshing = current.refreshing,
            offline = current.offline,
            error = current.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketUiState())

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            quoteAsset.value = settings.quoteAsset
            // 初始市场跟随设置；列表页顶部可随时切换 现货 / 永续合约
            selectMarket(settings.defaultMarket)
            // selectMarket 在市场未变化时会提前返回（默认市场 SPOT == activeMarket SPOT），
            // 导致冷启动后 never 拉数据、界面停留在初始 loading。这里补一次首次加载。
            refresh()
            startAutoRefresh()
        }
        // 自选集合或市场一变走势线就要重算（新增的标的还没画过线）
        viewModelScope.launch {
            activeMarket.flatMapLatest { watchlist.watchlist(it) }.collect { reloadTrends() }
        }
    }

    fun selectMarket(target: MarketType) {
        if (activeMarket.value == target) return
        activeMarket.value = target
        repository.selectMarket(target)
        refresh()
    }

    fun selectTab(target: MarketTab) {
        tab.value = target
    }

    fun toggleWatch(id: SymbolId) {
        viewModelScope.launch {
            if (watchlist.contains(id)) watchlist.remove(id) else watchlist.add(id)
        }
    }

    /** 左滑「移除」：语义明确地只做移除，不因当前状态反转成添加。 */
    fun removeWatch(id: SymbolId) {
        viewModelScope.launch { watchlist.remove(id) }
    }

    /**
     * 拖动排序：把 [id] 落到 [toIndex]。
     *
     * 拖动过程中每越过一格就调一次，而 `move()` 是「读当前顺序 → 重排 → 整表写回」，
     * 并发的两次调用若交错，最终顺序就不是手指的意图。Mutex 是公平（FIFO）的，
     * 配合 launch 的先后顺序即可保证按手势发生的次序依次落库。
     */
    fun moveWatch(id: SymbolId, toIndex: Int) {
        viewModelScope.launch {
            reorderMutex.withLock { watchlist.move(id, toIndex) }
        }
    }

    /** 下拉/点击刷新：先补交易规则，再拉 24h 快照。 */
    fun refresh() {
        viewModelScope.launch {
            val market = activeMarket.value
            flags.update { it.copy(refreshing = true, error = null) }
            try {
                if (market !in syncedMarkets) {
                    repository.syncInstruments(market)
                    syncedMarkets += market
                }
                repository.refreshTickers(market)
                flags.update { it.copy(offline = repository.isStale(market)) }
                // K 线缓存可能刚被详情页写入，顺手刷新走势线
                reloadTrends()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flags.update { it.copy(offline = true, error = e.displayMessage()) }
            } finally {
                flags.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    /**
     * 定时自动拉取 24h 快照：让列表价格随定时器自动变动，无需手动刷新。
     * WS 在线时数据本就实时，这里主要兜底「WS 断线」后的价格更新；
     * 不置 refreshing，避免与下拉刷新的指示器互相干扰。
     */
    private fun startAutoRefresh(refreshMs: Long = AUTO_REFRESH_MS) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            delay(refreshMs)
            while (isActive) {
                val market = activeMarket.value
                try {
                    repository.refreshTickers(market)
                    flags.update {
                        it.copy(offline = repository.isStale(market), error = null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    flags.update { it.copy(offline = true) }
                }
                delay(refreshMs)
            }
        }
    }

    private fun MarketTicker.toRow(source: Source, watched: Set<SymbolId>): TickerRow {
        val meta = source.instruments[id]
        return TickerRow(
            id = id,
            baseAsset = meta?.baseAsset ?: id.symbol,
            marketLabel = id.market.label,
            price = PriceFormatter.format(lastPrice, meta?.priceTickSize),
            changePercent = changePercent,
            quoteVolume = PriceFormatter.formatCompact(quoteVolume),
            watched = id in watched,
            trend = source.trends[id].orEmpty(),
        )
    }

    /**
     * 重新读取走势线。自选变化、首次加载与手动刷新时各调一次。
     * 数据只来自本地 K 线缓存，缺数据的标的直接跳过（列表那一格留空），
     * 不为了补走势线在首页发起网络请求。
     */
    private fun reloadTrends() {
        viewModelScope.launch {
            val ids = watchlist.watchlist(activeMarket.value).first()
            val loaded = HashMap<SymbolId, List<Double>>(ids.size)
            ids.forEach { id ->
                val closes = runCatching {
                    repository.recentCloses(id, SPARKLINE_INTERVAL, SPARKLINE_POINTS)
                }.getOrDefault(emptyList())
                if (closes.size >= 2) loaded[id] = closes
            }
            trends.value = loaded
        }
    }
}

/** 首页行情自动刷新的轮询间隔（毫秒）。 */
private const val AUTO_REFRESH_MS = 500L

/** 迷你走势线的取数周期与根数：24 根 1h = 最近一天，正好对应列表展示的 24h 涨跌。 */
private val SPARKLINE_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.H1)
private const val SPARKLINE_POINTS = 24
