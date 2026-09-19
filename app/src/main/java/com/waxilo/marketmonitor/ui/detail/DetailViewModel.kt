package com.waxilo.marketmonitor.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.KlineAggregator
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.chart.SubPaneKind
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** 详情页默认周期：日线信息密度最低，首屏最不容易看起来空白。 */
val DEFAULT_CHART_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.D1)

/** 均线可选项，PRD FR-2.1 要求 5/10/20/30/60 可调。 */
val MA_CHOICES: List<Int> = listOf(5, 10, 20, 30, 60)

val DEFAULT_MA_PERIODS: List<Int> = listOf(5, 10, 30)

data class StatItem(val label: String, val value: String)

data class DetailUiState(
    val id: SymbolId = SymbolId(MarketType.SPOT, ""),
    val title: String = "",
    val price: String = PriceFormatter.NO_DATA,
    val changePercent: Double? = null,
    /** 图表刻度与 tooltip 的小数位由它决定（PRD 已定：低价币自动放开位数）。 */
    val tickSize: BigDecimal? = null,
    val stats: List<StatItem> = emptyList(),
    val intervals: List<CandleInterval> = CandleInterval.quickPickPresets,
    val interval: CandleInterval = DEFAULT_CHART_INTERVAL,
    val candles: List<Kline> = emptyList(),
    val maPeriods: List<Int> = DEFAULT_MA_PERIODS,
    val maChoices: List<Int> = MA_CHOICES,
    val showBoll: Boolean = false,
    val subPane: SubPaneKind = SubPaneKind.VOLUME,
    val watched: Boolean = false,
    val origin: DataOrigin = DataOrigin.REMOTE,
    val loadingCandles: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 单个交易对详情（PRD FR-1.3 / FR-2.1 / FR-2.3）。
 *
 * 关键取舍：内存里保留**基础周期**的原始蜡烛，展示用的蜡烛每次整体重聚合。
 * 这样自定义周期的实时推送只需把一根基础蜡烛并进序列，不必处理「跨桶边界」与
 * 「tick 乱序」，而千根量级的聚合开销可忽略。
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
    private val chart = MutableStateFlow(ChartData())

    /** 序列与指标开关放在一起：任何一项变化都要重算展示序列。 */
    private data class ChartData(
        val raw: List<Kline> = emptyList(),
        val origin: DataOrigin = DataOrigin.REMOTE,
        val hasMore: Boolean = true,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val maPeriods: List<Int> = DEFAULT_MA_PERIODS,
        val showBoll: Boolean = false,
        val subPane: SubPaneKind = SubPaneKind.VOLUME,
    )

    val state: StateFlow<DetailUiState> = combine(
        repository.ticker(id),
        repository.instrument(id),
        watchlist.watchlist(id.market),
        chart,
        interval,
    ) { price, rule, watched, data, selected ->
        DetailUiState(
            id = id,
            title = rule?.let { "${it.baseAsset}/${it.quoteAsset}" } ?: id.symbol,
            price = PriceFormatter.format(price?.lastPrice, rule?.priceTickSize),
            changePercent = price?.changePercent,
            tickSize = rule?.priceTickSize,
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
            candles = KlineAggregator.aggregate(data.raw, selected),
            maPeriods = data.maPeriods,
            showBoll = data.showBoll,
            subPane = data.subPane,
            watched = id in watched,
            origin = data.origin,
            loadingCandles = data.loading,
            loadingMore = data.loadingMore,
            hasMore = data.hasMore,
            error = data.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState(id = id))

    init {
        viewModelScope.launch {
            combine(interval, reloadToken) { selected, token -> selected to token }
                .flatMapLatest { (selected, _) -> flow { emit(loadBase(selected)) } }
                .collect { loaded -> chart.value = loaded }
        }
        viewModelScope.launch {
            interval
                .flatMapLatest { selected -> repository.klineUpdate(id, selected) }
                .collect { candle -> mergeBaseCandle(candle) }
        }
        // 交易规则决定显示位数；首启可能还没落库，失败留给刷新兜底
        viewModelScope.launch { runCatching { repository.syncInstruments(id.market) } }
        // K 线流由详情页订阅、离开时退订，避免污染首页只需要的合并流
        viewModelScope.launch { interval.collect { selected -> repository.watchKlineUpdates(id, selected) } }
    }

    override fun onCleared() {
        repository.clearKlineUpdates()
        super.onCleared()
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

    fun toggleBoll() {
        chart.update { it.copy(showBoll = !it.showBoll) }
    }

    fun setSubPane(kind: SubPaneKind) {
        chart.update { it.copy(subPane = kind) }
    }

    fun toggleMaPeriod(period: Int) {
        chart.update { current ->
            val periods = if (period in current.maPeriods) {
                current.maPeriods - period
            } else {
                (current.maPeriods + period).sorted()
            }
            // 全关掉时保留刚点中的那条，否则图上什么都不剩
            current.copy(maPeriods = periods.ifEmpty { listOf(period) })
        }
    }

    /** 重取快照与当前周期序列。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshTickers(id.market)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chart.update { it.copy(error = e.displayMessage()) }
            }
            reloadToken.update { it + 1 }
        }
    }

    /** 图表拖到最左端时往更早方向翻页。 */
    fun loadMore() {
        val current = chart.value
        if (current.loadingMore || !current.hasMore || current.raw.isEmpty()) return
        val base = baseInterval() ?: return
        val oldest = current.raw.first().openTime
        viewModelScope.launch {
            chart.update { it.copy(loadingMore = true) }
            try {
                val page = repository.olderKlines(id, base, oldest, BASE_PAGE)
                val merged = (page.klines + current.raw).distinctBy { it.openTime }.sortedBy { it.openTime }
                chart.update {
                    it.copy(
                        raw = merged.takeLast(MAX_RAW),
                        hasMore = page.hasMore && page.klines.isNotEmpty(),
                        loadingMore = false,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chart.update { it.copy(loadingMore = false, error = e.displayMessage()) }
            }
        }
    }

    private suspend fun loadBase(selected: CandleInterval): ChartData {
        val base = selected.baseInterval?.let { CandleInterval.of(it) } ?: selected
        val previous = chart.value
        return try {
            val page = repository.klines(id, base, BASE_PAGE)
            previous.copy(
                raw = page.klines,
                origin = page.origin,
                hasMore = page.hasMore,
                loading = false,
                loadingMore = false,
                error = if (page.origin == DataOrigin.CACHE) CACHE_HINT else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previous.copy(raw = emptyList(), loading = false, loadingMore = false, error = e.displayMessage())
        }
    }

    private fun mergeBaseCandle(update: Kline) {
        chart.update { state ->
            val list = state.raw
            if (list.isEmpty()) return@update state.copy(raw = listOf(update), loading = false)
            val last = list.last()
            val merged = when {
                update.openTime == last.openTime -> list.dropLast(1) + update
                update.openTime > last.openTime -> (list + update).takeLast(MAX_RAW)
                else -> list // 重连后迟到的旧蜡烛交给刷新，不倒着改历史
            }
            if (merged === list) state else state.copy(raw = merged, loading = false)
        }
    }

    private fun baseInterval(): CandleInterval? =
        interval.value.baseInterval?.let { CandleInterval.of(it) }

    private companion object {
        /** 基础周期的请求条数；自定义周期下展示根数按聚合倍率减少。 */
        const val BASE_PAGE = 500

        /** 内存里保留的最大基础蜡烛数，翻页过多时丢弃最老的。 */
        const val MAX_RAW = 2_000
        const val CACHE_HINT = "网络不可用，K 线展示的是本地缓存"
    }
}
