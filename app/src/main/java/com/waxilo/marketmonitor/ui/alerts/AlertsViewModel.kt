package com.waxilo.marketmonitor.ui.alerts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.data.alert.notificationsAllowed
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import com.waxilo.marketmonitor.domain.repository.WebhookDelivery
import com.waxilo.marketmonitor.domain.repository.tickerSnapshots
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AlertsTab(val label: String) {
    RULES("规则"),
    MESSAGES("消息"),
}

/** 规则列表一行：条件文案与现价都在领域层算好，Compose 只排版。 */
@Immutable
data class AlertRuleRow(
    val rule: AlertRule,
    val condition: String,
    val repeat: String,
    val currentPrice: String,
    val changePercent: Double?,
    val status: String,
)

@Immutable
data class AlertMessageRow(
    val message: AlertMessage,
    val title: String,
    val summary: String,
    val time: String,
    val delivery: String?,
)

@Immutable
data class AlertsUiState(
    val tab: AlertsTab = AlertsTab.RULES,
    val rules: List<AlertRuleRow> = emptyList(),
    val messages: List<AlertMessageRow> = emptyList(),
    val unread: Int = 0,
    /** 系统通知权限未授予时页面顶部给出说明（PRD FR-3.3 前置条件）。 */
    val notificationsBlocked: Boolean = false,
    val error: String? = null,
)

/**
 * 预警页状态（PRD FR-3.1 / FR-3.4）。
 * 现价来自仓库层的合并流；检测不在这里做，那是 [com.waxilo.marketmonitor.data.alert.AlertEngine] 的职责，
 * 所以离开本页后提醒照常工作。
 */
class AlertsViewModel(private val container: AppContainer) : ViewModel() {

    private val alerts = container.alertRepository
    private val repository = container.marketRepository

    private val tab = MutableStateFlow(AlertsTab.RULES)
    private val errorMessage = MutableStateFlow<String?>(null)

    private data class Sources(
        val rules: List<AlertRule>,
        val prices: Map<SymbolId, MarketTicker>,
        val messages: List<AlertMessage>,
        /**
         * 每条规则最近一次触发（messages 已按 triggeredAt 倒序，取首次命中即最近）。
         * 旧实现是在 [statusOf] 里对每条规则扫一遍全部消息（最多 200 条 × N 条规则），
         * 而该函数在每次价格推送时都会随 state 重算——预索引成 Map 后降为 O(1) 查表。
         */
        val lastTriggeredAt: Map<Long, Long>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sources = combine(alerts.rules(), alerts.messages(MESSAGE_LIMIT)) { ruleList, logs ->
        ruleList to logs
    }.flatMapLatest { (ruleList, logs) ->
        val lastByRule = HashMap<Long, Long>(ruleList.size)
        logs.forEach { log -> lastByRule.putIfAbsent(log.ruleId, log.triggeredAt) }
        repository.tickerSnapshots(ruleList.map { SymbolId(it.market, it.symbol) })
            .map { prices -> Sources(ruleList, prices, logs, lastByRule) }
    }

    val state: StateFlow<AlertsUiState> = combine(
        sources,
        tab,
        alerts.unreadCount(),
        errorMessage,
    ) { source, selectedTab, unread, error ->
        AlertsUiState(
            tab = selectedTab,
            rules = source.rules.map { it.toRow(source) },
            messages = source.messages.map { it.toRow() },
            unread = unread,
            notificationsBlocked = !container.appContext.notificationsAllowed(),
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun selectTab(target: AlertsTab) {
        tab.value = target
    }

    fun setEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch { alerts.setRuleEnabled(ruleId, enabled) }
    }

    fun delete(ruleId: Long) {
        viewModelScope.launch {
            try {
                alerts.deleteRule(ruleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.displayMessage()
            }
        }
    }

    fun acknowledge(messageId: Long) {
        viewModelScope.launch { alerts.acknowledge(messageId) }
    }

    fun acknowledgeAll() {
        viewModelScope.launch { alerts.acknowledgeAll() }
    }

    private fun AlertRule.toRow(source: Sources): AlertRuleRow {
        val ticker = source.prices[SymbolId(this.market, this.symbol)]
        return AlertRuleRow(
            rule = this,
            condition = AlertText.conditionLabel(this),
            repeat = AlertText.repeatLabel(this),
            currentPrice = AlertText.priceText(ticker?.lastPrice),
            changePercent = ticker?.changePercent,
            status = statusOf(this, source),
        )
    }

    /** 最近触发时间从预索引的 Map 取，避免对每条规则扫一遍消息列表。 */
    private fun statusOf(rule: AlertRule, source: Sources): String {
        if (!rule.enabled) return "已停用"
        val last = source.lastTriggeredAt[rule.id] ?: return "等待触发"
        return "最近触发 ${AlertText.timeOf(last)}"
    }

    private fun AlertMessage.toRow(): AlertMessageRow = AlertMessageRow(
        message = this,
        title = alertName.ifBlank { symbol },
        summary = AlertText.summary(this),
        time = AlertText.timeOf(triggeredAt),
        delivery = when (webhookDelivery) {
            WebhookDelivery.NONE -> null
            WebhookDelivery.SENT -> "已推送"
            WebhookDelivery.FAILED -> "推送失败，待重试"
        },
    )

    private companion object {
        const val MESSAGE_LIMIT = 200
    }
}
