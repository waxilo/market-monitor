package com.waxilo.marketmonitor.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertDirection
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import com.waxilo.marketmonitor.domain.repository.WebhookDelivery
import java.math.BigDecimal

/** 预警规则（PRD FR-3.1）。金额字符串存储，枚举以稳定的 key 存储。 */
@Entity(tableName = "alert_rule")
data class AlertRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val market: String,
    val symbol: String,
    val name: String,
    val condition: String,
    val threshold: String?,
    val rangeLower: String?,
    val rangeUpper: String?,
    val changePercent: String?,
    val repeatMode: String,
    val enabled: Int,
    val playSound: Int,
    val vibrate: Int,
    /** 关联 Webhook 端点 id，逗号分隔。 */
    val webhookIds: String,
    /**
     * 图上这条线自己的备注。**与 [name] 是两件事**：name 回答「这条线是谁画的」
     * （自动画的线 `8时EMA50` 还是 EMA 面板的认领键），note 才是用户随手写的一句话。
     */
    val note: String? = null,
    val createdAt: Long,
)

/** 规则的判定状态（边沿触发与 ONCE 闸门需要跨进程重启保持，PRD FR-3.2）。 */
@Entity(tableName = "alert_state")
data class AlertStateEntity(
    @PrimaryKey val ruleId: Long,
    val lastPrice: String?,
    val lastTriggeredAt: Long?,
    val wasSatisfied: Int,
    val fired: Int,
)

/** 触发记录，消息中心的数据源（PRD FR-3.4 / FR-4.3）。 */
@Entity(
    tableName = "alert_log",
    indices = [Index("triggeredAt"), Index(value = ["ruleId", "triggeredAt"])],
)
data class AlertLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ruleId: Long,
    val market: String,
    val symbol: String,
    val alertName: String,
    val direction: String,
    val price: String,
    val threshold: String?,
    val changePercent: Double?,
    val triggeredAt: Long,
    val acknowledged: Int,
    /** 0 未推送 / 1 已推送 / 2 推送失败待重试。 */
    val webhookStatus: Int,
)

fun AlertRuleEntity.toDomain(): AlertRule {
    val mapped = AlertCondition.fromKey(condition)
    // 旧数据迁移：历史上破/下破的规则一律按「达到」对待（双向到达，方向在触发时按实际穿越判定）
    val migrated = if (mapped == null || mapped == AlertCondition.ABOVE || mapped == AlertCondition.BELOW) {
        AlertCondition.REACH
    } else {
        mapped
    }
    return AlertRule(
        id = id,
        market = MarketType.fromKey(market),
        symbol = symbol,
        name = name,
        condition = migrated,
        threshold = threshold?.toBigDecimalOrNull(),
        rangeLower = rangeLower?.toBigDecimalOrNull(),
        rangeUpper = rangeUpper?.toBigDecimalOrNull(),
        changePercent = changePercent?.toBigDecimalOrNull(),
        repeatMode = AlertRepeatMode.fromKey(repeatMode),
        enabled = enabled == 1,
        playSound = playSound == 1,
        vibrate = vibrate == 1,
        webhookIds = webhookIds.split(',').mapNotNull { it.trim().toLongOrNull() },
        note = note?.takeIf { it.isNotBlank() },
        createdAt = createdAt,
    )
}

fun AlertRule.toEntity(): AlertRuleEntity = AlertRuleEntity(
    id = id,
    market = market.key,
    symbol = symbol,
    name = name,
    condition = condition.key,
    threshold = threshold?.toPlainString(),
    rangeLower = rangeLower?.toPlainString(),
    rangeUpper = rangeUpper?.toPlainString(),
    changePercent = changePercent?.toPlainString(),
    repeatMode = repeatMode.key,
    enabled = if (enabled) 1 else 0,
    playSound = if (playSound) 1 else 0,
    vibrate = if (vibrate) 1 else 0,
    webhookIds = webhookIds.joinToString(","),
    note = note,
    createdAt = createdAt,
)

fun AlertStateEntity.toDomain(): AlertState = AlertState(
    lastPrice = lastPrice?.toBigDecimalOrNull(),
    lastTriggeredAt = lastTriggeredAt,
    wasSatisfied = wasSatisfied == 1,
    fired = fired == 1,
)

fun AlertState.toEntity(ruleId: Long): AlertStateEntity = AlertStateEntity(
    ruleId = ruleId,
    lastPrice = lastPrice?.toPlainString(),
    lastTriggeredAt = lastTriggeredAt,
    wasSatisfied = if (wasSatisfied) 1 else 0,
    fired = if (fired) 1 else 0,
)

fun AlertLogEntity.toDomain(): AlertMessage = AlertMessage(
    id = id,
    ruleId = ruleId,
    market = MarketType.fromKey(market),
    symbol = symbol,
    alertName = alertName,
    direction = AlertDirection.fromKey(direction) ?: AlertDirection.ABOVE,
    price = price.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    threshold = threshold?.toBigDecimalOrNull(),
    changePercent = changePercent,
    triggeredAt = triggeredAt,
    acknowledged = acknowledged == 1,
    webhookDelivery = WebhookDelivery.fromCode(webhookStatus),
)

fun AlertMessage.toEntity(): AlertLogEntity = AlertLogEntity(
    id = id,
    ruleId = ruleId,
    market = market.key,
    symbol = symbol,
    alertName = alertName,
    direction = direction.key,
    price = price.toPlainString(),
    threshold = threshold?.toPlainString(),
    changePercent = changePercent,
    triggeredAt = triggeredAt,
    acknowledged = if (acknowledged) 1 else 0,
    webhookStatus = webhookDelivery.code,
)
