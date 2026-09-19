package com.waxilo.marketmonitor.ui.search

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
            .filter { keyword.isEmpty() || it.matches(keyword) }
            .sortedWith(
                compareByDescending<Candidate> { it.watched }
                    .thenByDescending { it.ticker?.quoteVolume ?: BigDecimal.ZERO }
                    .thenBy { it.id.symbol },
            )
        SearchUiState(
            market = selected,
            query = text,
            rows = matched.take(MAX_RESULTS).map { it.toRow() },
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

    private fun Candidate.matches(keyword: String): Boolean {
        val upper = keyword.uppercase()
        return id.symbol.contains(upper) || baseAsset.uppercase().contains(upper)
    }

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
