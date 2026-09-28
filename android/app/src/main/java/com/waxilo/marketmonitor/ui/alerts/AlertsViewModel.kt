package com.waxilo.marketmonitor.ui.alerts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.data.alert.notificationsAllowed
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertRuleSource
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.alert.BandAnchorInfo
import com.waxilo.marketmonitor.domain.alert.IndicatorKind
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
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

/**
 * 规则列表一行：条件文案与现价都在领域层算好，Compose 只排版。
 *
 * 卡片要摊开的所有字段都在这里算完：距离触发（文案 + 进度条比例）、提醒方式、
 * 创建时间——这些以前只能点进编辑页才看得到。
 */
@Immutable
data class AlertRuleRow(
    val rule: AlertRule,
    /** 卡片标题：手动规则即规则名；划线条目排成「BTCUSDT 1h MA30均线」。 */
    val title: String,
    val condition: String,
    val repeat: String,
    val currentPrice: String,
    val changePercent: Double?,
    val status: String,
    /** 「距触发 x.xx%」/「已达触发位」；取不到现价时为 null。 */
    val distanceText: String?,
    /** 进度条填充比例 0..1；与 [distanceText] 同生同灭。 */
    val progress: Float?,
    /** 「响铃·振动 · 外部推送 ×2」这类提醒方式汇总。 */
    val notify: String,
    /** 创建时间（MM-dd HH:mm:ss）。 */
    val created: String,
    /** 指标划线自动挂的线：编辑没有意义（阈值跟随划线），列表里只给开关与删除。 */
    val indicator: Boolean,
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
        /** 划线按 id 索引：给划线规则把标题排成「BTCUSDT 1h MA30均线」。 */
        val lineById: Map<Long, IndicatorLine>,
        /** 均线带当前锚点（引擎发布）：把上下破条目分别标到各自挂着的成员均线上。 */
        val anchorByLine: Map<Long, BandAnchorInfo>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sources = combine(
        alerts.rules(),
        alerts.messages(MESSAGE_LIMIT),
        alerts.indicatorLines(),
        container.alertEngine.bandAnchorInfo,
    ) { ruleList, logs, lines, anchors ->
        val lastByRule = HashMap<Long, Long>(ruleList.size)
        logs.forEach { log -> lastByRule.putIfAbsent(log.ruleId, log.triggeredAt) }
        Sources(
            ruleList,
            emptyMap(),
            logs,
            lastByRule,
            lines.associateBy { it.id },
            anchors.associateBy { it.lineId },
        )
    }.flatMapLatest { base ->
        repository.tickerSnapshots(base.rules.map { SymbolId(it.market, it.symbol) })
            .map { prices -> base.copy(prices = prices) }
    }

    val state: StateFlow<AlertsUiState> = combine(
        sources,
        tab,
        alerts.unreadCount(),
        errorMessage,
    ) { source, selectedTab, unread, error ->
        AlertsUiState(
            tab = selectedTab,
            // 指标划线挂的线也进列表（带「划线」标记）：告警统一在一处看，
            // 但它们的阈值跟随划线，编辑页不让进
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

    /** 删除规则；指标划线的线被删即表示不再要这个告警，把线的告警一并置关，否则引擎下一轮会重挂。 */
    fun delete(ruleId: Long) {
        viewModelScope.launch {
            try {
                val rule = alerts.rule(ruleId)
                alerts.deleteRule(ruleId)
                rule?.takeIf { it.source == AlertRuleSource.INDICATOR }?.indicatorLineId?.let {
                    alerts.setIndicatorLineAlertMode(it, LineAlertMode.OFF)
                }
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

    /** 清空触发记录（不可撤销，确认框在 UI 侧）。 */
    fun clearMessages() {
        viewModelScope.launch {
            try {
                alerts.clearMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.displayMessage()
            }
        }
    }

    private fun AlertRule.toRow(source: Sources): AlertRuleRow {
        val ticker = source.prices[SymbolId(market, symbol)]
        val (distanceText, progress) = distanceOf(this, ticker)
        return AlertRuleRow(
            rule = this,
            title = titleOf(this, source),
            condition = AlertText.conditionLabel(this),
            repeat = AlertText.repeatLabel(this),
            currentPrice = AlertText.priceText(ticker?.lastPrice),
            changePercent = ticker?.changePercent,
            status = statusOf(this, source),
            distanceText = distanceText,
            progress = progress,
            notify = buildString {
                append(
                    when {
                        playSound && vibrate -> "响铃·振动"
                        playSound -> "仅响铃"
                        vibrate -> "仅振动"
                        else -> "静默"
                    },
                )
                if (webhookIds.isNotEmpty()) append(" · 外部推送 ×${webhookIds.size}")
            },
            created = AlertText.timeOf(createdAt),
            indicator = this.source == AlertRuleSource.INDICATOR,
        )
    }

    /**
     * 距触发还有多少：现价还要走百分之多少才碰到阈值，以及进度条填充比例。
     *
     * 进度条满格取 [DISTANCE_WINDOW] —— 再远也分不出「远」和「很远」，一律按 0 显示；
     * 涨跌幅条件换算成同一量纲（24h 涨跌幅与阈值都是百分数）。
     * 区间条件看**先碰到哪条边**，取两边距离的较小值。
     */
    private fun distanceOf(rule: AlertRule, ticker: MarketTicker?): Pair<String?, Float?> {
        if (ticker == null) return null to null
        val last = ticker.lastPrice.toDouble()
        if (last <= 0.0) return null to null
        val gap = when (rule.condition) {
            AlertCondition.ABOVE -> rule.threshold?.let { (it.toDouble() - last) / last }
            AlertCondition.BELOW -> rule.threshold?.let { (last - it.toDouble()) / last }
            AlertCondition.OUT_OF_RANGE -> listOfNotNull(
                rule.rangeLower?.let { (last - it.toDouble()) / last },
                rule.rangeUpper?.let { (it.toDouble() - last) / last },
            ).minOrNull()
            AlertCondition.RISE_BY -> rule.changePercent?.let { (it.toDouble() - ticker.changePercent) / 100.0 }
            AlertCondition.FALL_BY -> rule.changePercent?.let { (it.toDouble() + ticker.changePercent) / 100.0 }
        } ?: return null to null
        if (gap <= 0.0) return "已达触发位" to 1f
        val text = "距触发 %.2f%%".format(gap * 100)
        return text to (1f - (gap / DISTANCE_WINDOW).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 卡片标题。划线规则的 [AlertRule.name] 是引擎对号用的稳定标识（「BTCUSDT 1h MA30 上破」），
     * 直接上卡片会读成半截句子，且尾部方向词与条件行重复；这里按线重排成「BTCUSDT 1h MA30均线」。
     * 均线带的上下破各自挂在池里某条成员均线上（引擎每轮换锚），标题跟着标到成员头上。
     * 线被删但规则还没被引擎回收的短暂窗口查不到线，退回规则名。
     */
    private fun titleOf(rule: AlertRule, source: Sources): String {
        if (rule.source != AlertRuleSource.INDICATOR) return rule.name
        val line = rule.indicatorLineId?.let(source.lineById::get) ?: return rule.name
        val desc = when (line.kind) {
            IndicatorKind.MA -> "${line.interval.label} MA${line.maPeriod}均线"
            IndicatorKind.MA_BAND -> {
                val anchor = source.anchorByLine[line.id]
                val side = if (rule.condition == AlertCondition.ABOVE) anchor?.upper else anchor?.lower
                side?.let { "${it.member.interval.label} MA${it.member.maPeriod}均线" } ?: line.label
            }
        }
        return "${rule.symbol} $desc"
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

        /** 距离进度条的满量程：现价与阈值相差 10% 以内才谈「快到了」。 */
        const val DISTANCE_WINDOW = 0.10
    }
}
