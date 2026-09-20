package com.waxilo.marketmonitor.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.market.TickerRow
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SearchUiState(
    val market: MarketType = MarketType.SPOT,
    val query: String = "",
    val rows: List<TickerRow> = emptyList(),
    val emptyReason: String? = null,
)

/**
 * 交易对搜索（PRD FR-1.2 / FR-3.1 的选标的入口）。
 * 在本地已知的标的集合内过滤，不请求远端搜索接口——币安也没有该接口。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.marketRepository
    private val watchlist = container.watchlistRepository

    private val market = MutableStateFlow(MarketType.SPOT)
    private val query = MutableStateFlow("")

    private data class Candidate(
        val id: SymbolId,
        val baseAsset: String,
        /** 计价币。排序时用于把 USDT 对排到法币对前面，见 [SearchRanking.quotePriority]。 */
        val quoteAsset: String,
        val ticker: MarketTicker?,
        val meta: InstrumentMeta?,
        val watched: Boolean,
    )

    private val source = market.flatMapLatest { selected ->
        combine(
            repository.tickers(selected),
            repository.instruments(selected),
            watchlist.watchlist(selected),
        ) { tickers, instruments, watched ->
            val bySymbol = tickers.associateBy { it.id }
            val metas = instruments.associateBy { it.id }
            val ids = (metas.keys + bySymbol.keys).sortedBy { it.symbol }
            ids.map { id ->
                Candidate(
                    id = id,
                    baseAsset = metas[id]?.baseAsset ?: id.symbol,
                    // 元数据缺失时退回「用交易对名剥掉币种」推断，
                    // 这样冷启动（instrument 还没同步）也不会让计价币优先级失效。
                    quoteAsset = metas[id]?.quoteAsset
                        ?: id.symbol.removePrefix(metas[id]?.baseAsset ?: ""),
                    ticker = bySymbol[id],
                    meta = metas[id],
                    watched = id in watched,
                )
            }
        }
    }

    val state: StateFlow<SearchUiState> = combine(query, market, source) { text, selected, candidates ->
        val keyword = text.trim()
        val matched = candidates
            .mapNotNull { candidate -> candidate.rank(keyword)?.let { candidate to it } }
            // 排序优先级（PRD FR-1.2 的「搜索结果要有优先级」）：
            // ① 自选置顶：已经在自选里的标的优先，方便快速回到常看的那几个。
            // ② 匹配质量分层：精确币种 > 币种前缀 > 交易对前缀 > 交易对包含。
            //    搜索 "btc" 时 BTCUSDT 必须排在 1INCHBTC / AAVEBTC 之前 ——
            //    这些是「BTC 计价」的交易对，币种本身并不是 BTC。
            // ③ 计价币优先级：同一层里 USDT 对排在法币对前面。
            //    这一条**不能省**：`ticker` 表在冷启动/同步失败时是空的，
            //    那时 quoteVolume 全是 0，按成交量排等于没排，顺序会掉回字母序 ——
            //    实测 BTCUSDT 因此掉到第 32/35 名（前面全是 BTCAEUR/BTCARS），
            //    这正是「搜 btc 搜不到比特币」的另一半原因。
            // ④ 有行情时再按成交额降序，让流动性好的标的先出现。
            // ⑤ 最后才用交易对名兜底，保证顺序稳定不跳动。
            .sortedWith(
                compareByDescending<Pair<Candidate, Int>> { it.first.watched }
                    .thenBy { it.second }
                    .thenBy { SearchRanking.quotePriority(it.first.quoteAsset) }
                    .thenByDescending { it.first.ticker?.quoteVolume ?: BigDecimal.ZERO }
                    .thenBy { it.first.id.symbol },
            )
            .take(MAX_RESULTS)
            .map { it.first.toRow() }
        SearchUiState(
            market = selected,
            query = text,
            rows = matched,
            emptyReason = when {
                candidates.isEmpty() -> "本地还没有该市场的标的，先回首页刷新一次行情"
                matched.isEmpty() && keyword.isNotEmpty() -> "没有匹配「$keyword」的交易对"
                else -> null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch { market.value = container.settings.current().defaultMarket }
    }

    fun selectMarket(target: MarketType) {
        market.value = target
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun toggleWatch(id: SymbolId) {
        viewModelScope.launch {
            if (watchlist.contains(id)) watchlist.remove(id) else watchlist.add(id)
        }
    }

    /**
     * 匹配质量分层，见 [SearchRanking.rank]（抽成纯函数以便单测覆盖）。
     */
    private fun Candidate.rank(keyword: String): Int? =
        SearchRanking.rank(symbol = id.symbol, baseAsset = baseAsset, keyword = keyword)

    private fun Candidate.toRow() = TickerRow(
        id = id,
        baseAsset = baseAsset,
        marketLabel = id.market.label,
        price = ticker?.let { PriceFormatter.format(it.lastPrice, meta?.priceTickSize) } ?: PriceFormatter.NO_DATA,
        changePercent = ticker?.changePercent ?: 0.0,
        quoteVolume = PriceFormatter.formatCompact(ticker?.quoteVolume),
        watched = watched,
    )

    private companion object {
        const val MAX_RESULTS = 80
    }
}
