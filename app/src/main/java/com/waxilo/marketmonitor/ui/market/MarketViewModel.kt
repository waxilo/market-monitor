package com.waxilo.marketmonitor.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MarketTab(val label: String) {
    WATCHLIST("自选"),
    ALL("行情"),
}

/** 列表一行需要的展示数据，全部已在领域层格式化完毕，Compose 只做排版。 */
data class TickerRow(
    val id: SymbolId,
    val baseAsset: String,
    val marketLabel: String,
    val price: String,
    val changePercent: Double,
    val quoteVolume: String,
    val watched: Boolean,
)

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
)

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
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sources = combine(activeMarket, quoteAsset) { market, quote -> market to quote }
        .flatMapLatest { (market, quote) ->
            combine(
                repository.tickers(market, quote),
                watchlist.watchlist(market),
                repository.instruments(market),
            ) { tickers, watched, instruments ->
                Source(market, tickers, watched, instruments.associateBy { it.id })
            }
        }

    val state: StateFlow<MarketUiState> = combine(sources, tab, flags) { source, selectedTab, current ->
        val watchedIds = source.watched.toSet()
        val rows = when (selectedTab) {
            // 自选按用户手动排序展示，行情页按成交额排序
            MarketTab.WATCHLIST -> source.watched.mapNotNull { id -> source.tickers.firstOrNull { it.id == id } }
            MarketTab.ALL -> source.tickers.sortedByDescending { it.quoteVolume }
        }.map { ticker -> ticker.toRow(source, watchedIds) }
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
            selectMarket(settings.defaultMarket)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flags.update { it.copy(offline = true, error = e.displayMessage()) }
            } finally {
                flags.update { it.copy(refreshing = false, loading = false) }
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
        )
    }
}
