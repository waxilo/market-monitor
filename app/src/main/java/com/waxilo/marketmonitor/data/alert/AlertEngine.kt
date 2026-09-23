package com.waxilo.marketmonitor.data.alert

import android.content.Context
import android.content.Intent
import com.waxilo.marketmonitor.data.remote.WebhookSender
import com.waxilo.marketmonitor.domain.alert.AlertDecision
import com.waxilo.marketmonitor.domain.alert.AlertDirection
import com.waxilo.marketmonitor.domain.alert.AlertEvaluator
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import com.waxilo.marketmonitor.domain.repository.AlertRepository
import com.waxilo.marketmonitor.domain.repository.MarketRepository
import com.waxilo.marketmonitor.domain.repository.SettingsRepository
import com.waxilo.marketmonitor.domain.repository.tickerSnapshots
import com.waxilo.marketmonitor.domain.repository.WebhookDelivery
import com.waxilo.marketmonitor.domain.repository.WebhookRepository
import com.waxilo.marketmonitor.domain.webhook.AlertEvent
import com.waxilo.marketmonitor.domain.webhook.WebhookTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 预警检测中枢（PRD FR-3.2 / FR-3.3 / FR-4.2）。
 *
 * 三条链路分开跑：
 * - 观察：订阅启用标的的 ticker 流（Room 缓存视图，随各页轮询落库而更新），逐条规则判定并落状态。
 * - 轮询：按设置间隔主动拉单个标的快照。观察流依赖别人刷新，页面全退出时就没有源头，
 *   所以这里独立保底。
 * - 补发：推送失败的 Webhook 在下一轮重试，避免一次网络抖动就永久丢通知。
 *
 * 生命周期跟随进程：进程被系统杀死后无法提醒，UI 必须显式告知该限制（PRD FR-3.2）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertEngine(
    private val context: Context,
    private val alerts: AlertRepository,
    private val webhooks: WebhookRepository,
    private val market: MarketRepository,
    private val settings: SettingsRepository,
    private val notifier: AlertNotifier,
    private val sender: WebhookSender,
    private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)
    private val enabledRules = MutableStateFlow<List<AlertRule>>(emptyList())

    /** 常驻通知里显示的规则数。 */
    val enabledRuleCount: Int get() = enabledRules.value.size

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { observeRules() }
        scope.launch { observePrices() }
        scope.launch { pollLoop() }
        scope.launch { retryLoop() }
    }

    /**
     * 界面回到前台时重新对齐常驻通知：Android 12 起后台启动前台服务会被直接拒绝，
     * 冷启动那一轮如果失败，只有这个时刻能补上，否则监测随时可能被系统回收。
     */
    fun refreshMonitorService() {
        syncMonitorService(enabledRules.value.size)
    }

    private suspend fun observeRules() {
        alerts.rules()
            .map { list -> list.filter { it.enabled } }
            .collect { list ->
                enabledRules.value = list
                syncMonitorService(list.size)
            }
    }

    private suspend fun observePrices() {
        enabledRules
            .flatMapLatest { rules -> market.tickerSnapshots(rules.map { SymbolId(it.market, it.symbol) }) }
            .collect { prices -> evaluateAll(prices) }
    }

    private suspend fun evaluateAll(prices: Map<SymbolId, MarketTicker>) {
        val now = System.currentTimeMillis()
        enabledRules.value.forEach { rule ->
            val ticker = prices[SymbolId(rule.market, rule.symbol)] ?: return@forEach
            val previous = alerts.state(rule.id)
            val (decision, next) = AlertEvaluator.evaluate(rule, ticker.lastPrice, ticker.changePercent, now, previous)
            val retired = decision is AlertDecision.Triggered && onTriggered(rule, decision)
            // 已退场的规则不能再写状态：deleteRule 连状态一起删了，回写等于凭空造出一行孤儿
            if (!retired && changed(previous, next)) alerts.saveState(rule.id, next)
        }
    }

    /** lastPrice 每 tick 都在变，只有语义字段变化才值得写库。 */
    private fun changed(previous: AlertState, next: AlertState): Boolean =
        previous.lastPrice == null ||
            previous.wasSatisfied != next.wasSatisfied ||
            previous.lastTriggeredAt != next.lastTriggeredAt ||
            previous.fired != next.fired

    /**
     * 记录一次触发。返回 true 表示这条规则已经「用尽」并被移除。
     *
     * 单次规则命中即退场：留在列表里既不会再提醒（fired 闸门），又要用户手动清理，
     * 是纯粹的残留物。记录与通知都做完才删，避免删库把还没发出去的通知一起带走。
     */
    private suspend fun onTriggered(rule: AlertRule, decision: AlertDecision.Triggered): Boolean {
        val message = AlertMessage(
            ruleId = rule.id,
            market = rule.market,
            symbol = rule.symbol,
            alertName = rule.name,
            direction = decision.direction,
            price = decision.price,
            threshold = thresholdOf(rule, decision.direction),
            changePercent = decision.changePercent,
            triggeredAt = decision.atMs,
            webhookDelivery = WebhookDelivery.NONE,
        )
        val id = alerts.record(message)
        val stored = message.copy(id = id)
        if (settings.current().notificationEnabled) notifier.notify(stored, rule)
        val delivery = deliver(rule, stored)
        if (delivery != WebhookDelivery.NONE) alerts.setDelivery(id, delivery)
        if (rule.repeatMode != AlertRepeatMode.ONCE) return false
        alerts.deleteRule(rule.id)
        return true
    }

    /** 区间外条件下哪一侧越界由方向决定，消息里只记那一个阈值。 */
    private fun thresholdOf(rule: AlertRule, direction: AlertDirection): BigDecimal? = when {
        rule.threshold != null -> rule.threshold
        direction == AlertDirection.BELOW -> rule.rangeLower ?: rule.rangeUpper
        else -> rule.rangeUpper ?: rule.rangeLower
    }

    private suspend fun deliver(rule: AlertRule, message: AlertMessage): WebhookDelivery {
        val snapshot = settings.current()
        if (!snapshot.webhookEnabled) return WebhookDelivery.NONE
        val endpoints = webhooks.all().filter { endpoint ->
            endpoint.appliesTo(rule.id) && (rule.webhookIds.isEmpty() || endpoint.id in rule.webhookIds)
        }
        if (endpoints.isEmpty()) return WebhookDelivery.NONE
        val event = eventOf(message)
        var failures = 0
        endpoints.forEach { endpoint ->
            val urlProblem = WebhookTemplate.validateUrl(endpoint.url, snapshot.allowInsecureWebhook)
            val payload = WebhookTemplate.render(endpoint.effectiveTemplate, event)
            if (urlProblem != null || sender.post(endpoint.url, payload.text) != null) failures++
        }
        // 任一端点失败就整条记 FAILED：补发会重放全部端点，宁可重复推送也不静默漏掉
        return if (failures == 0) WebhookDelivery.SENT else WebhookDelivery.FAILED
    }

    private fun eventOf(message: AlertMessage): AlertEvent = AlertEvent(
        alertName = message.alertName,
        marketLabel = message.market.label,
        symbol = message.symbol,
        directionKey = message.direction.key,
        price = message.price,
        threshold = message.threshold,
        changePercent = message.changePercent,
        triggeredAtIso = AlertText.isoUtc(message.triggeredAt),
        timestampMs = message.triggeredAt,
    )

    /** 主动轮询：权重 1/次，远低于全量快照，且覆盖没有任何页面在刷新的标的。 */
    private suspend fun pollLoop() {
        while (true) {
            delay(pollIntervalMs())
            val targets = enabledRules.value.map { SymbolId(it.market, it.symbol) }.distinct()
            if (targets.isEmpty()) continue
            // 逐条串行：RateBudget 会排队，并发打满只会让整条链路一起等待
            targets.forEach { market.refreshTicker(it) }
        }
    }

    private suspend fun pollIntervalMs(): Long =
        settings.current().alertPollingSeconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS) * 1_000L

    private suspend fun retryLoop() {
        pruneOldMessages()
        while (true) {
            delay(RETRY_INTERVAL_MS)
            val pending = alerts.messagesWithDelivery(WebhookDelivery.FAILED, RETRY_BATCH)
            pending.forEach { message ->
                val rule = alerts.rule(message.ruleId)
                if (rule == null || !rule.enabled) {
                    // 规则已删除或停用，不再补发，避免僵尸重试
                    alerts.setDelivery(message.id, WebhookDelivery.NONE)
                    return@forEach
                }
                if (deliver(rule, message) == WebhookDelivery.SENT) alerts.setDelivery(message.id, WebhookDelivery.SENT)
            }
        }
    }

    private suspend fun pruneOldMessages() {
        runCatching { alerts.pruneMessages(System.currentTimeMillis() - MESSAGE_RETENTION_MS) }
    }

    /** 有启用规则时拉起前台服务保持进程；规则清空后立刻停掉，不长期占状态栏。 */
    private fun syncMonitorService(ruleCount: Int) {
        val intent = Intent(context, AlertMonitorService::class.java)
        if (ruleCount == 0) {
            context.stopService(intent)
            return
        }
        // 后台状态下启动前台服务会抛 IllegalStateException：规则已落库，不能因此崩掉进程
        runCatching { context.startForegroundService(intent) }
    }

    private companion object {
        /** 下限即为默认值：预警按 5 秒一轮检测。 */
        const val MIN_POLL_SECONDS = 5
        const val MAX_POLL_SECONDS = 600
        const val RETRY_INTERVAL_MS = 60_000L
        const val RETRY_BATCH = 10
        const val MESSAGE_RETENTION_MS = 30 * 24 * 60 * 60 * 1_000L
    }
}
