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
 * 数据来自仓库层的 Room 缓存流（REST 轮询落库即驱动更新），界面本身不碰网络。
 */
class MarketViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.marketRepository
    private val watchlist = container.watchlistRepository

    private val activeMarket = MutableStateFlow(MarketType.SPOT)
    private val tab = MutableStateFlow(MarketTab.WATCHLIST)
    private val flags = MutableStateFlow(Flags())

    /**
     * 定时自动拉取 24h 快照的任务：列表价格的主要更新通道（WS 已移除）。
     * 只在首页可见时运行 —— 进详情 / 切页后继续轮询会和详情页抢同一个限频预算。
     */
    private var autoRefreshJob: Job? = null

    /** 是否已经历过一次可见：区分「首次显示」与「从详情返回」，后者要立刻补一拍。 */
    private var hasBeenVisible = false

    /** 串行化拖动排序的落库（见 [moveWatch] 的说明）。 */
    private val reorderMutex = Mutex()

    /**
     * 迷你走势线数据：自选的「最近 24 根 1h 收盘价」。
     *
     * 单独放一个 StateFlow 而不是并进 500ms 的 tickers 流：走势线来自本地缓存、
     * 变化极慢，如果每轮行情刷新都重查一遍 Room 就是纯浪费。这里只在自选集合
     * 变化或手动刷新时重算。
     */
    private val trends = MutableStateFlow<Map<SymbolId, List<Double>>>(emptyMap())

    /**
     * 行情最近值缓存：行情流对个别交易对（杠杆代币等）偶发缺报，缺报的那一拍
     * 自选行不能凭空消失——拖动排序的让位区间、滚动位置都依赖「行数恒等于自选数」。
     * combine 在主线程收集，HashMap 无需加锁。
     */
    private val lastTickers = HashMap<SymbolId, MarketTicker>()

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
    private val sources = activeMarket.flatMapLatest { market ->
        combine(
            // Room 流在每次轮询落库时发射；sample 500ms 把高频更新合并成每 0.5 秒一次重绘（PRD 4.3）。
            // 自选行按 SymbolId 精确匹配，BNBUSDC/XRPTUSD 这类非 USDT 报价的关注标的
            // 不能被按报价过滤掉（否则行永远缺数据）。
            repository.tickers(market).sample(500),
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
        source.tickers.forEach { ticker ->
            byId[ticker.id] = ticker
            lastTickers[ticker.id] = ticker
        }
        // 缺报行不能被丢弃：用最近一次行情兜底，从未有行情的用占位行，
        // 保证行数与顺序恒等于自选表（行凭空消失/回归会打乱滚动与拖动让位）
        val rows = source.watched.map { id ->
            (byId[id] ?: lastTickers[id])?.toRow(source, watchedIds)
                ?: TickerRow(
                    id = id,
                    baseAsset = source.instruments[id]?.baseAsset ?: id.symbol,
                    marketLabel = id.market.label,
                    price = "…",
                    changePercent = 0.0,
                    quoteVolume = "—",
                    watched = true,
                )
        }
        MarketUiState(
            market = source.market,
            tab = selectedTab,
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
            // 初始市场跟随设置；列表页顶部可随时切换 现货 / 永续合约
            selectMarket(settings.defaultMarket)
            // selectMarket 在市场未变化时会提前返回（默认市场 SPOT == activeMarket SPOT），
            // 导致冷启动后 never 拉数据、界面停留在初始 loading。这里补一次首次加载。
            // 后续定时轮询由页面生命周期驱动（见 onScreenVisible）。
            refresh()
        }
        // 自选集合或市场一变走势线就要重算（新增的标的还没画过线）
        viewModelScope.launch {
            activeMarket.flatMapLatest { watchlist.watchlist(it) }.collect { reloadTrends() }
        }
        // 自动兜底换了合约源：缓存已被容器清空，首页停在合约市场时立刻用新源重拉
        viewModelScope.launch {
            container.futuresSourceChanges.collect {
                // 换源后旧盘口的价格/走势不能再当「缺报兜底」显示
                // （Room 已清，内存里的 lastTickers 与 trends 也要清）
                lastTickers.clear()
                trends.value = emptyMap()
                if (activeMarket.value == MarketType.FUTURES) refresh()
            }
        }
        // 手动切换现货源（币安 / Gate）：容器清了现货缓存，首页停在同一市场时重载。
        viewModelScope.launch {
            container.spotSourceChanges.collect {
                lastTickers.clear()
                trends.value = emptyMap()
                // 清单由容器在切换时统一重同步；这里只拉报价，别把首屏价格排在清单后面。
                if (activeMarket.value == MarketType.SPOT) {
                    viewModelScope.launch {
                        repository.refreshTickers(MarketType.SPOT)
                        flags.update {
                            it.copy(offline = repository.isStale(MarketType.SPOT), error = null)
                        }
                        reloadTrends()
                    }
                }
            }
        }
        // 换网 / 开关 VPN：连接池与域名链记忆已由容器作废，立刻重来一轮。
        // 定时轮询本来也能自愈，但用户刚切完网络就盯着屏幕，等下一拍像「没恢复」。
        viewModelScope.launch {
            container.networkChanges.collect {
                // 清单若因断网一直没同步上，这里顺带补一次
                refresh()
            }
        }
    }

    fun selectMarket(target: MarketType) {
        if (activeMarket.value == target) return
        activeMarket.value = target
        // 记住本次选择：defaultMarket 即「上次使用的市场」，冷启动从这里恢复
        viewModelScope.launch {
            container.settings.edit { it.copy(defaultMarket = target) }
        }
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
     * 拖动排序落库：把 [id] 移到 [toIndex]。
     *
     * 拖动过程中的换位全部在本地完成（见 TickerList 的 orderOverride），这里
     * 只在拖动结束时收到一次最终位置，Mutex 串行化只是防御性的兜底。
     */
    fun moveWatch(id: SymbolId, toIndex: Int) {
        viewModelScope.launch {
            reorderMutex.withLock { watchlist.move(id, toIndex) }
        }
    }

    /**
     * 下拉/点击刷新：先拉 24h 快照，交易规则清单在后台补。
     *
     * 清单是现货最大的一类响应（币安解压后约 18MB），不能挡在首屏价格前面；
     * 仓库层有 TTL，命中时这次后台补只是一次本地查询，不会真的发请求。
     */
    fun refresh() {
        val market = activeMarket.value
        viewModelScope.launch {
            flags.update { it.copy(refreshing = true, error = null) }
            try {
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
        viewModelScope.launch {
            // 让首轮报价先拿到限频额度，再补清单
            delay(INSTRUMENT_SYNC_DELAY_MS)
            try {
                repository.syncInstruments(market)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 清单没拉下来不致命：报价不依赖它，下次刷新还会再试
            }
        }
    }

    /**
     * 首页可见性回调：只在页面真正可见时轮询。
     *
     * 离开首页（进详情 / 进搜索 / 切 tab）后继续 1 秒一轮，会和详情页共用同一个
     * 现货限频预算 —— Gate 只有 160 次 / 10 秒，详情页的 K 线会被自选报价挤到后面。
     * 从详情返回时先立刻补一拍，再恢复定时器。
     */
    fun onScreenVisible() {
        // 首次显示时 init 里的 refresh() 已经拉过一轮；从详情/其他 tab 返回时立刻补一拍。
        startPolling(immediate = hasBeenVisible)
        hasBeenVisible = true
    }

    fun onScreenHidden() {
        stopPolling()
    }

    /**
     * 定时自动拉取 24h 快照：列表价格的唯一更新通道（WS 已移除），
     * 让价格随定时器自动变动，无需手动刷新。
     * 不置 refreshing，避免与下拉刷新的指示器互相干扰。
     */
    private fun startPolling(refreshMs: Long = AUTO_REFRESH_MS, immediate: Boolean = false) {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            if (!immediate) delay(refreshMs)
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

    private fun stopPolling() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
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

/**
 * 首页行情自动刷新的轮询间隔（毫秒）。
 *
 * 500ms 对 REST 逐标的轮询太激进：移动网络下这个频率会持续占着射频，切到 Gate 后
 * 还会逼近它「200 次 / 10 秒」的公开限频。1 秒仍是「价格在动」的观感，负载减半。
 */
private const val AUTO_REFRESH_MS = 1_000L

/** 首屏先报价格，清单延后这么久再补（TTL 命中时只是一次本地查询，不会发请求）。 */
private const val INSTRUMENT_SYNC_DELAY_MS = 1_500L

/** 迷你走势线的取数周期与根数：24 根 1h = 最近一天，正好对应列表展示的 24h 涨跌。 */
private val SPARKLINE_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.H1)
private const val SPARKLINE_POINTS = 24
