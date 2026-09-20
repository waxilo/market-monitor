package com.waxilo.marketmonitor.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** 详情页默认周期：日线信息密度最低，首屏最不容易看起来空白。 */
val DEFAULT_CHART_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.D1)

/** 均线可选项，PRD FR-2.1 要求 5/10/20/30/60 可调。 */
val MA_CHOICES: List<Int> = listOf(5, 10, 20, 30, 60)

val DEFAULT_MA_PERIODS: List<Int> = listOf(5, 10, 30)

/**
 * 详情页的一条统计。
 * [raw] 保留未格式化的原始数值，供进度条之类的可视化使用；
 * [value] 是给用户看的格式化结果。两者分开避免在组合里反解析字符串。
 */
@Immutable
data class StatItem(
    val label: String,
    val value: String,
)

@Immutable
data class DetailUiState(
    val id: SymbolId = SymbolId(MarketType.SPOT, ""),
    val title: String = "",
    val price: String = PriceFormatter.NO_DATA,
    val changePercent: Double? = null,
    /** 图表刻度与 tooltip 的小数位由它决定（PRD 已定：低价币自动放开位数）。 */
    val tickSize: BigDecimal? = null,
    val stats: List<StatItem> = emptyList(),
    /** 24h 高低与当前价（原始值），Hero 卡片的高低区间条使用。 */
    val low24h: Double? = null,
    val high24h: Double? = null,
    val lastPrice: Double? = null,
    val intervals: List<CandleInterval> = CandleInterval.quickPickPresets,
    val interval: CandleInterval = DEFAULT_CHART_INTERVAL,
    val candles: List<Kline> = emptyList(),
    val maPeriods: List<Int> = DEFAULT_MA_PERIODS,
    val maChoices: List<Int> = MA_CHOICES,
    val showBoll: Boolean = false,
    /** 选中的副图。空列表 = 不显示副图；支持多选。 */
    val subPanes: List<SubPaneKind> = listOf(SubPaneKind.VOLUME),
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
    private val settings = container.settings
    private val alerts = container.alertRepository

    private val interval = MutableStateFlow(DEFAULT_CHART_INTERVAL)
    private val reloadToken = MutableStateFlow(0)
    private val chart = MutableStateFlow(ChartData())

    /**
     * 划在图表上的告警线（价格原始值）。
     *
     * 放在 ViewModel 而不是 `rememberSaveable`：全屏要横屏，旋屏会让 Activity 重建，
     * 而 ViewModel 实例是保留的 —— 线不会因为转个屏就消失。
     */
    private val alertLine = MutableStateFlow<BigDecimal?>(null)

    /** 松手后待确认的价位。非空即弹确认框；确认或取消都清空。 */
    private val alertDraft = MutableStateFlow<BigDecimal?>(null)

    /** 一次性提示（创建成功之类）。3 秒后自动消失。 */
    private val noticeText = MutableStateFlow<String?>(null)
    private var noticeJob: Job? = null

    val alertLinePrice: StateFlow<BigDecimal?> = alertLine.asStateFlow()
    val alertDraftPrice: StateFlow<BigDecimal?> = alertDraft.asStateFlow()
    val notice: StateFlow<String?> = noticeText.asStateFlow()

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
        /** 选中的副图集合（按枚举声明顺序归一化）。 */
        val subPanes: List<SubPaneKind> = listOf(SubPaneKind.VOLUME),
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
            low24h = price?.lowPrice?.toDouble(),
            high24h = price?.highPrice?.toDouble(),
            lastPrice = price?.lastPrice?.toDouble(),
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
            subPanes = data.subPanes,
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
            // 先恢复上次使用的周期与指标开关，再开始加载：顺序反了会被首次加载的 copy 覆盖
            restoreChartPreferences()
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
        viewModelScope.launch { settings.edit { it.copy(lastIntervalKey = target.storageKey) } }
    }

    /** 详情页的图表偏好跨会话保留（PRD 8 设置：周期、均线、副图、布林）。 */
    private suspend fun restoreChartPreferences() {
        val saved = settings.current()
        CandleInterval.fromStorageKey(saved.lastIntervalKey)?.let { interval.value = it }
        chart.update {
            it.copy(
                // 空集是合法偏好（裸 K 图），所以这里不做 ifEmpty 回填默认值
                maPeriods = saved.maPeriods.sorted(),
                showBoll = saved.bollEnabled,
                subPanes = saved.subPaneKeys.toSubPaneKinds(),
            )
        }
    }

    fun toggleWatch() {
        viewModelScope.launch {
            if (watchlist.contains(id)) watchlist.remove(id) else watchlist.add(id)
        }
    }

    fun toggleBoll() {
        val next = !chart.value.showBoll
        chart.update { it.copy(showBoll = next) }
        viewModelScope.launch { settings.edit { it.copy(bollEnabled = next) } }
    }

    /** 副图多选：点中即切换该块的显隐，全不选 = 只留主图。 */
    fun toggleSubPane(kind: SubPaneKind) {
        chart.update {
            val next = if (kind in it.subPanes) it.subPanes - kind else it.subPanes + kind
            it.copy(subPanes = next.sortedBy { k -> SubPaneKind.entries.indexOf(k) })
        }
        val saved = chart.value.subPanes.toSubPaneKeys()
        viewModelScope.launch { settings.edit { it.copy(subPaneKeys = saved) } }
    }

    /**
     * 主图均线：允许全关。关光后就是**裸 K 图**——
     * 这是看图的基本需求（判断形态时叠着均线反而看不清），因此不做「至少留一条」的兜底。
     */
    fun toggleMaPeriod(period: Int) {
        chart.update { current ->
            val periods = if (period in current.maPeriods) {
                current.maPeriods - period
            } else {
                current.maPeriods + period
            }
            current.copy(maPeriods = periods.sorted())
        }
        val saved = chart.value.maPeriods
        viewModelScope.launch { settings.edit { it.copy(maPeriods = saved) } }
    }

    /** 划线过程中每帧回调：只更新要画的那条线，不落库。 */
    fun dragAlertLine(price: Double) {
        if (!price.isFinite()) return
        alertLine.value = BigDecimal(price.toString())
    }

    /**
     * 手指离开：把落点对齐到 tickSize 再弹确认框。
     *
     * 对齐是必须的 —— 校验器要求目标价是 tickSize 的整数倍，而手指落点换算出来的价格
     * 几乎不可能正好落在刻度上，不处理的话用户每次划线都会收到「需为 tickSize 的整数倍」。
     */
    fun commitAlertLine() {
        val raw = alertLine.value ?: return
        val aligned = alignToTick(raw)
        alertLine.value = aligned
        alertDraft.value = aligned
    }

    fun dismissAlertDraft() {
        alertDraft.value = null
    }

    fun clearAlertLine() {
        alertLine.value = null
        alertDraft.value = null
    }

    /**
     * 用划线价位建一条预警规则（PRD FR-3.1）。
     * [above] 为 true 走上破，false 走下破 —— 划线的意义就是「到这个价提醒我」，
     * 方向必须让用户当场选，猜错方向的规则比没有规则更危险。
     */
    fun createAlertFromDraft(above: Boolean) {
        val price = alertDraft.value ?: return
        alertDraft.value = null
        val label = PriceFormatter.format(price, state.value.tickSize)
        viewModelScope.launch {
            try {
                alerts.saveRule(
                    AlertRule(
                        market = id.market,
                        symbol = id.symbol,
                        name = "${id.symbol} ${if (above) "上破" else "下破"} $label",
                        condition = if (above) AlertCondition.ABOVE else AlertCondition.BELOW,
                        threshold = price,
                        // 列表按 createdAt 倒序，新建的必须拿到当前时间才排在最前
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                showNotice("已创建${if (above) "上破" else "下破"}预警 · $label")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("预警创建失败：${e.displayMessage()}")
            }
        }
    }

    /** 把价格对齐到交易规则的最小变动单位，避免建规则时被精度校验挡下。 */
    private fun alignToTick(price: BigDecimal): BigDecimal {
        val tick = state.value.tickSize ?: return price
        if (tick.signum() == 0) return price
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick)
    }

    private fun showNotice(text: String) {
        noticeText.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            noticeText.value = null
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

        /** 一次性提示的停留时长。 */
        const val NOTICE_MS = 3_000L
    }
}

/**
 * 副图选择与持久化串之间的转换。
 *
 * 存成逗号分隔的名字（而不是序号）是为了：① 枚举增删不影响历史数据；
 * ② 空串天然表达「不显示副图」，不需要额外的哨兵值。
 */
private fun String.toSubPaneKinds(): List<SubPaneKind> {
    if (isBlank()) return emptyList()
    return split(',').mapNotNull { name -> SubPaneKind.entries.firstOrNull { it.name == name.trim() } }
}

private fun List<SubPaneKind>.toSubPaneKeys(): String = joinToString(",") { it.name }
