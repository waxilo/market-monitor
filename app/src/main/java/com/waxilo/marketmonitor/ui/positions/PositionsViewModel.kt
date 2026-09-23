package com.waxilo.marketmonitor.ui.positions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.data.remote.MarketApiException
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.Position
import com.waxilo.marketmonitor.domain.model.SpotBalance
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.ApiCredentials
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 仓位页状态。
 * 数据只有 REST 一条路（签名接口没有缓存可言，账户数据缓存了反而是误导），
 * 所以没有 origin/stale 概念：要么拉到最新，要么保留上一次结果并挂错误。
 */
@Immutable
data class PositionsUiState(
    val configured: Boolean = false,
    val loading: Boolean = false,
    val positions: List<Position> = emptyList(),
    val totalPnl: BigDecimal = BigDecimal.ZERO,
    val totalNotional: BigDecimal = BigDecimal.ZERO,
    val spotBalances: List<SpotBalance> = emptyList(),
    /** 资产 → 按公共行情折算的 USDT 价值；没有 USDT 交易对的资产不在表里。 */
    val spotValues: Map<String, BigDecimal> = emptyMap(),
    val spotTotal: BigDecimal = BigDecimal.ZERO,
    val error: String? = null,
    val updatedAt: Long? = null,
    /** 凭据表单是否展开（已配置时由顶栏「配置」入口切换）。 */
    val editing: Boolean = false,
    /** 草稿仅在内存，点「保存」才加密落盘；留空的字段沿用已存值。 */
    val keyDraft: String = "",
    val secretDraft: String = "",
)

class PositionsViewModel(private val container: AppContainer) : ViewModel() {

    private val backing = MutableStateFlow(PositionsUiState())
    val state: StateFlow<PositionsUiState> = backing.asStateFlow()

    init {
        viewModelScope.launch {
            container.binanceCredentials.credentials.collect { c ->
                backing.update { it.copy(configured = c?.isConfigured == true) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (backing.value.configured) load()
                delay(POLL_MS)
            }
        }
    }

    /** 手动刷新（错误重试 / 下拉动作共用）。 */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun toggleEditing() = backing.update { it.copy(editing = !it.editing) }

    fun setKeyDraft(v: String) = backing.update { it.copy(keyDraft = v) }

    fun setSecretDraft(v: String) = backing.update { it.copy(secretDraft = v) }

    /**
     * 保存凭据：草稿留空的字段沿用已存值 —— 想只换 key 不必重新粘 secret。
     * 两者凑不齐（新配置只填了一半）时仓库会按「清除」处理，不会存半份凭据。
     */
    fun saveCredentials() {
        val s = backing.value
        val existing = container.binanceCredentials.credentials.value
        val credentials = ApiCredentials(
            key = s.keyDraft.ifBlank { existing?.key }.orEmpty(),
            secret = s.secretDraft.ifBlank { existing?.secret }.orEmpty(),
        )
        viewModelScope.launch {
            container.binanceCredentials.save(credentials)
            backing.update { it.copy(keyDraft = "", secretDraft = "", editing = false) }
        }
    }

    fun clearCredentials() {
        viewModelScope.launch {
            container.binanceCredentials.clear()
            backing.update { PositionsUiState(configured = false) }
        }
    }

    private suspend fun load() {
        backing.update { it.copy(loading = true) }
        try {
            val list = container.marketRepository.positions()
            val balances = container.marketRepository.spotBalances()
            val values = estimateSpotValues(balances)
            backing.update {
                it.copy(
                    loading = false,
                    positions = list,
                    totalPnl = list.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.unrealizedPnl) },
                    totalNotional = list.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.notional) },
                    spotBalances = balances,
                    spotValues = values,
                    spotTotal = values.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) },
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backing.update { it.copy(loading = false, error = e.toPositionsMessage()) }
        }
    }

    /**
     * 现货估值：公共行情里查 `{asset}USDT` 最新价（权重 1/次），乘持有量折成 USDT。
     * 查不到价（无 USDT 交易对、网络失败）就不给该资产估值，UI 只显示数量——
     * 宁可少显示，不编数字。只估前 [MAX_VALUED_ASSETS] 项，控制每轮请求量。
     */
    private suspend fun estimateSpotValues(balances: List<SpotBalance>): Map<String, BigDecimal> {
        val out = LinkedHashMap<String, BigDecimal>()
        balances.take(MAX_VALUED_ASSETS).forEach { balance ->
            val price = if (balance.asset in STABLE_ASSETS) BigDecimal.ONE
            else container.marketRepository
                .refreshTicker(SymbolId(MarketType.SPOT, "${balance.asset}USDT"))
                ?.lastPrice
            if (price != null && price.signum() > 0) {
                out[balance.asset] = balance.quantity.multiply(price).setScale(2, RoundingMode.HALF_UP)
            }
        }
        return out
    }

    private fun Throwable.toPositionsMessage(): String = when (this) {
        // displayMessage 的 IO 兜底说的是「展示的是本地缓存」——仓位没有缓存，别误导
        is IOException -> if (this is MarketApiException) displayMessage() else "网络不可用，请稍后重试"
        else -> displayMessage()
    }

    private companion object {
        /** positionRisk 权重 5 + account 权重 20，8 秒一轮远低于币安额度，离开页面即随 ViewModel 停止。 */
        const val POLL_MS = 8_000L
        const val MAX_VALUED_ASSETS = 15
        /** 与 USDT 等值的稳定币，不必再花一次请求查价。 */
        val STABLE_ASSETS = setOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "DAI")
    }
}
