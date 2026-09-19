package com.waxilo.marketmonitor.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 详情页默认周期：日线信息密度最低，首屏最不容易看起来空白。 */
val DEFAULT_CHART_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.D1)

data class StatItem(val label: String, val value: String)

data class DetailUiState(
    val id: SymbolId = SymbolId(MarketType.SPOT, ""),
    val title: String = "",
    val price: String = PriceFormatter.NO_DATA,
    val changePercent: Double? = null,
    val stats: List<StatItem> = emptyList(),
    val intervals: List<CandleInterval> = CandleInterval.quickPickPresets,
    val interval: CandleInterval = DEFAULT_CHART_INTERVAL,
    val candles: List<Kline> = emptyList(),
    val watched: Boolean = false,
    val origin: DataOrigin = DataOrigin.REMOTE,
    val loadingCandles: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 单个交易对详情（PRD FR-1.3 / FR-2.1）。
 * 价格头来自 WS 覆盖的快照流；蜡烛整段按周期重建——自定义周期由基础周期聚合而来，
 * 做增量合并容易在周期边界出错，重建的代价只有几百个对象。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    container: AppContainer,
    private val id: SymbolId,
) : ViewModel() {

    private val repository = container.marketRepository
    private val watchlist = container.watchlistRepository

    private val interval = MutableStateFlow(DEFAULT_CHART_INTERVAL)
    private val reloadToken = MutableStateFlow(0)
    private val candles = MutableStateFlow(CandleState())

    private data class CandleState(
        val list: List<Kline> = emptyList(),
        val origin: DataOrigin = DataOrigin.REMOTE,
        val hasMore: Boolean = true,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    )

    val state: StateFlow<DetailUiState> = combine(
        repository.ticker(id),
        repository.instrument(id),
        watchlist.watchlist(id.market),
        candles,
        interval,
    ) { price, rule, watched, series, selected ->
        DetailUiState(
            id = id,
            title = rule?.let { "${it.baseAsset}/${it.quoteAsset}" } ?: id.symbol,
            price = PriceFormatter.format(price?.lastPrice, rule?.priceTickSize),
            changePercent = price?.changePercent,
            stats = price?.let {
                listOf(
                    StatItem("24h 最高", PriceFormatter.format(it.highPrice, rule?.priceTickSize)),
                    StatItem("24h 最低", PriceFormatter.format(it.lowPrice, rule?.priceTickSize)),
                    StatItem("24h 量能", PriceFormatter.formatQuantity(it.volume)),
                    StatItem("24h 成交额", PriceFormatter.formatCompact(it.quoteVolume)),
                )
            }.orEmpty() + listOf(
                StatItem("价格精度", rule?.priceTickSize?.toPlainString() ?: PriceFormatter.NO_DATA),
                StatItem("所属市场", id.market.label),
            ),
            interval = selected,
            candles = series.list,
            watched = id in watched,
            origin = series.origin,
            loadingCandles = series.loading,
            loadingMore = series.loadingMore,
            hasMore = series.hasMore,
            error = series.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState(id = id))

    init {
        viewModelScope.launch {
            combine(interval, reloadToken) { selected, _ -> selected }
                .flatMapLatest { selected ->
                    flow {
                        // 先给一个空周期占位，避免新旧周期的蜡烛混在同一段里
                        emit(CandleState())
                        emit(load(selected))
                    }
                }
                .collect { state -> candles.value = state }
        }
        // 交易规则决定显示位数；首启时可能还没落库，失败留给刷新按钮兜底
        viewModelScope.launch { runCatching { repository.syncInstruments(id.market) } }
    }

    fun selectInterval(target: CandleInterval) {
        if (interval.value == target) return
        interval.value = target
    }

    fun toggleWatch() {
        viewModelScope.launch {
            if (watchlist.contains(id)) watchlist.remove(id) else watchlist.add(id)
        }
    }

    /** 重新拉取快照与本段 K 线；失败只记错误，已展示的序列保留。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshTickers(id.market)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                candles.update { it.copy(error = e.displayMessage()) }
            }
            reloadToken.update { it + 1 }
        }
    }

    /** 图表左缘触底时往更早方向翻页。 */
    fun loadMore() {
        val current = candles.value
        if (current.loadingMore || !current.hasMore || current.list.isEmpty()) return
        val oldest = current.list.minOf { it.openTime }
        viewModelScope.launch {
            candles.update { it.copy(loadingMore = true) }
            try {
                val page = repository.olderKlines(id, interval.value, oldest, PAGE_SIZE)
                val merged = (page.klines + current.list).distinctBy { it.openTime }.sortedBy { it.openTime }
                candles.update {
                    it.copy(
                        list = merged.takeLast(current.list.size + PAGE_SIZE),
                        hasMore = page.hasMore,
                        loadingMore = false,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                candles.update { it.copy(loadingMore = false, error = e.displayMessage()) }
            }
        }
    }

    private suspend fun load(selected: CandleInterval): CandleState = try {
        val page = repository.klines(id, selected, PAGE_SIZE)
        CandleState(
            list = page.klines,
            origin = page.origin,
            hasMore = page.hasMore,
            loading = false,
            error = if (page.origin == DataOrigin.CACHE) CACHE_HINT else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CandleState(list = emptyList(), loading = false, error = e.displayMessage())
    }

    companion object {
        const val PAGE_SIZE = 300
        const val CACHE_HINT = "网络不可用，K 线展示的是本地缓存"
    }
}
