package com.waxilo.marketmonitor.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertRuleValidator
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class AlertEditorState(
    val ruleId: Long? = null,
    val createdAt: Long = 0L,
    val market: MarketType = MarketType.SPOT,
    val symbol: String = "",
    val name: String = "",
    val condition: AlertCondition = AlertCondition.ABOVE,
    val threshold: String = "",
    val rangeLower: String = "",
    val rangeUpper: String = "",
    val percent: String = "",
    val repeatMode: AlertRepeatMode = AlertRepeatMode.ONCE,
    val cooldownMinutes: String = "5",
    val enabled: Boolean = true,
    val playSound: Boolean = true,
    val vibrate: Boolean = true,
    val webhookIds: List<Long> = emptyList(),
    val error: String? = null,
    /** 保存成功后置位，由页面监听并退出。 */
    val saved: Boolean = false,
    val saving: Boolean = false,
) {
    val needsThreshold: Boolean get() = condition == AlertCondition.ABOVE || condition == AlertCondition.BELOW
    val needsRange: Boolean get() = condition == AlertCondition.OUT_OF_RANGE
    val needsPercent: Boolean get() = condition == AlertCondition.RISE_BY || condition == AlertCondition.FALL_BY
    val usesCooldown: Boolean get() = repeatMode != AlertRepeatMode.ONCE
}

/** 编辑器右侧的动态提示：来自本地交易规则与自选，不额外消耗权重。 */
data class EditorHints(
    val tickSize: BigDecimal? = null,
    val currentPrice: String = PriceFormatter.NO_DATA,
    val quickPicks: List<String> = emptyList(),
    val endpoints: List<WebhookEndpoint> = emptyList(),
)

/**
 * 规则编辑器（PRD FR-3.1）。
 *
 * 表单只在内存里，点保存才落库；目标价按交易规则的 tickSize 校验——币安侧成交不到那个
 * 价位的话，提醒永远不会命中，比报错更糟的是让用户以为设置成功了。
 */
class AlertEditorViewModel(
    container: AppContainer,
    private val ruleId: Long?,
    presetMarket: MarketType,
    presetSymbol: String,
) : ViewModel() {

    private val alerts = container.alertRepository
    private val repository = container.marketRepository

    private val form = MutableStateFlow(
        AlertEditorState(market = presetMarket, symbol = presetSymbol.uppercase()),
    )

    val state: StateFlow<AlertEditorState> = form

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeId = form.map { SymbolId(it.market, it.symbol.trim().uppercase()) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val instrument = activeId.flatMapLatest { id ->
        if (id.symbol.length < 2) flowOf(null) else repository.instrument(id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val price = activeId.flatMapLatest { id ->
        if (id.symbol.length < 2) flowOf(null) else repository.ticker(id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val marketWatchlist = form.map { it.market }.distinctUntilChanged()
        .flatMapLatest { container.watchlistRepository.watchlist(it) }

    val hints: StateFlow<EditorHints> = combine(
        instrument,
        price,
        marketWatchlist,
        container.webhookRepository.endpoints(),
    ) { meta, ticker, watched, endpoints ->
        EditorHints(
            tickSize = meta?.priceTickSize,
            currentPrice = AlertText.priceText(ticker?.lastPrice),
            quickPicks = watched.take(QUICK_PICKS).map { it.symbol },
            endpoints = endpoints.filter { it.enabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorHints())

    val tickSize: BigDecimal? get() = hints.value.tickSize

    init {
        viewModelScope.launch {
            val id = ruleId ?: return@launch
            alerts.rule(id)?.let { form.value = it.toForm() }
        }
    }

    fun on(change: (AlertEditorState) -> AlertEditorState) {
        form.update { change(it).copy(error = null, saved = false) }
    }

    fun save(onSaved: () -> Unit = {}) {
        val current = form.value
        val rule = current.toRule()
        val problem = parseProblem(current) ?: AlertRuleValidator.validate(rule, tickSize)
        if (problem != null) {
            form.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            form.update { it.copy(saving = true) }
            try {
                alerts.saveRule(rule)
                form.update { it.copy(saved = true, saving = false) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.update { it.copy(saving = false, error = e.displayMessage()) }
            }
        }
    }

    /** 编辑既有规则时才可用；新建规则直接退出即可。 */
    fun delete(onDeleted: () -> Unit = {}) {
        val id = ruleId ?: return
        viewModelScope.launch {
            alerts.deleteRule(id)
            onDeleted()
        }
    }

    /** 冷却时间是整数，不在 [AlertRuleValidator] 的射程内（它只管价格与条件）。 */
    private fun parseProblem(state: AlertEditorState): String? {
        if (state.symbol.isBlank()) return "请填写交易对"
        if (state.usesCooldown && state.cooldownMinutes.trim().toIntOrNull() == null) {
            return "冷却时间需为整数分钟"
        }
        if (state.usesCooldown && (state.cooldownMinutes.trim().toIntOrNull() ?: 0) <= 0) {
            return "重复提醒需要大于 0 的冷却分钟数"
        }
        return null
    }

    private fun AlertEditorState.toRule(): AlertRule = AlertRule(
        id = ruleId ?: 0L,
        market = market,
        symbol = symbol.trim().uppercase(),
        name = name.trim().ifBlank {
            if (symbol.isBlank()) "未命名预警" else "${symbol.trim().uppercase()} 价格预警"
        },
        condition = condition,
        threshold = decimal(threshold),
        rangeLower = decimal(rangeLower),
        rangeUpper = decimal(rangeUpper),
        changePercent = decimal(percent),
        repeatMode = repeatMode,
        cooldownMinutes = cooldownMinutes.trim().toIntOrNull() ?: 0,
        enabled = enabled,
        playSound = playSound,
        vibrate = vibrate,
        webhookIds = webhookIds,
        // 列表按 createdAt 倒序，新建规则必须拿到当前时间才排在最前
        createdAt = if (ruleId == null) System.currentTimeMillis() else createdAt,
    )

    private fun AlertRule.toForm(): AlertEditorState = AlertEditorState(
        ruleId = id,
        createdAt = createdAt,
        market = market,
        symbol = symbol,
        name = name,
        condition = condition,
        threshold = threshold?.toPlainString().orEmpty(),
        rangeLower = rangeLower?.toPlainString().orEmpty(),
        rangeUpper = rangeUpper?.toPlainString().orEmpty(),
        percent = changePercent?.toPlainString().orEmpty(),
        repeatMode = repeatMode,
        cooldownMinutes = cooldownMinutes.toString(),
        enabled = enabled,
        playSound = playSound,
        vibrate = vibrate,
        webhookIds = webhookIds,
    )

    private fun decimal(raw: String): BigDecimal? = raw.trim().toBigDecimalOrNull()

    private companion object {
        const val QUICK_PICKS = 8
    }
}
