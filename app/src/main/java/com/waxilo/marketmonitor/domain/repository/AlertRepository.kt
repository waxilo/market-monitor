package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.alert.AlertDirection
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/** 触发记录（消息中心数据源，PRD FR-3.4 / 4.3）。 */
data class AlertMessage(
    val id: Long = 0L,
    val ruleId: Long,
    val market: MarketType,
    val symbol: String,
    val alertName: String,
    val direction: AlertDirection,
    val price: BigDecimal,
    val threshold: BigDecimal?,
    val changePercent: Double?,
    val triggeredAt: Long,
    val acknowledged: Boolean = false,
    val webhookDelivery: WebhookDelivery = WebhookDelivery.NONE,
)

enum class WebhookDelivery(val code: Int) {
    /** 规则未绑定端点，无需推送。 */
    NONE(0),
    SENT(1),
    /** 推送失败，等待重试（PRD FR-4.3）。 */
    FAILED(2),
    ;

    companion object {
        fun fromCode(code: Int): WebhookDelivery = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** 通知渠道开关（PRD FR-3.3），系统权限之外的应用内控制。 */
enum class AlertChannel { LOCAL_NOTIFICATION, SOUND, VIBRATE, WEBHOOK }

interface AlertRepository {

    fun rules(): Flow<List<AlertRule>>

    suspend fun rule(id: Long): AlertRule?

    suspend fun enabledRules(): List<AlertRule>

    /** 新增或更新规则，返回落库后的 id。 */
    suspend fun saveRule(rule: AlertRule): Long

    suspend fun deleteRule(id: Long)

    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    suspend fun state(ruleId: Long): AlertState

    suspend fun saveState(ruleId: Long, state: AlertState)

    fun messages(limit: Int = 200): Flow<List<AlertMessage>>

    fun unreadCount(): Flow<Int>

    suspend fun record(message: AlertMessage): Long

    suspend fun acknowledge(id: Long)

    suspend fun acknowledgeAll()

    /**
     * 清空全部触发记录。
     *
     * 只清历史，不动规则也不动规则的判定状态（已触发/冷却），否则单次预警会重新响一遍。
     */
    suspend fun clearMessages()

    suspend fun messagesWithDelivery(delivery: WebhookDelivery, limit: Int): List<AlertMessage>

    suspend fun setDelivery(id: Long, delivery: WebhookDelivery)

    /** 只保留近期记录，避免无限增长。 */
    suspend fun pruneMessages(olderThan: Long)
}

interface WebhookRepository {
    fun endpoints(): Flow<List<WebhookEndpoint>>
    suspend fun all(): List<WebhookEndpoint>
    suspend fun find(id: Long): WebhookEndpoint?
    /** 新增或更新，返回 id。 */
    suspend fun save(endpoint: WebhookEndpoint): Long
    suspend fun delete(id: Long)
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
