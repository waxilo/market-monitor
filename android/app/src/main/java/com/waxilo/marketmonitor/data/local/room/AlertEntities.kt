package com.waxilo.marketmonitor.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertDirection
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertRuleSource
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.alert.IndicatorKind
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
import com.waxilo.marketmonitor.domain.alert.LineMember
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
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
    val cooldownMinutes: Int,
    val enabled: Int,
    val playSound: Int,
    val vibrate: Int,
    /** 关联 Webhook 端点 id，逗号分隔。 */
    val webhookIds: String,
    val createdAt: Long,
    /** [AlertRuleSource.key]：指标划线的自动挂线要按所属线退场与跟随。 */
    val source: String = AlertRuleSource.MANUAL.key,
    /** v2 遗留列（旧的「跟随均线周期」），规则名已带完整线标识，不再使用。 */
    val maPeriod: Int? = null,
    /** v3 遗留列（旧的 indicator_alert 条目 id），划线重构后不再使用。 */
    val indicatorAlertId: Long? = null,
    /** source=indicator 时归属的 indicator_line 划线 id；手动规则为空。 */
    val indicatorLineId: Long? = null,
)

/** 指标划线（详情页划线管理）。单线 = 标的 × 类型 × K线周期 × 均线周期；均线带聚合条目用 members 列。 */
@Entity(tableName = "indicator_line")
data class IndicatorLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val market: String,
    val symbol: String,
    /** [IndicatorKind.key]：ma / ma_band。 */
    val kind: String,
    /** K 线周期 storageKey（如 "o:15m"）；均线带取第一个成员，仅展示用。 */
    val intervalKey: String,
    /** 均线周期；仅旧单周期 MA 线有意义，均线带存 0。 */
    val maPeriod: Int,
    /** [LineAlertMode.key]：off / once / every_cross。 */
    val alertMode: String,
    /** 均线带成员：`intervalStorageKey|maPeriod` 逗号连接；其他类型为空串。 */
    val members: String = "",
    /** 启用开关（0/1）：关闭后引擎与图上都把这条划线当不存在，配置保留。 */
    val enabled: Int = 1,
    val createdAt: Long,
)

/** 规则的判定状态（边沿触发与冷却需要跨进程重启保持，PRD FR-3.2）。 */
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

fun AlertRuleEntity.toDomain(): AlertRule = AlertRule(
    id = id,
    market = MarketType.fromKey(market),
    symbol = symbol,
    name = name,
    condition = AlertCondition.fromKey(condition) ?: AlertCondition.ABOVE,
    threshold = threshold?.toBigDecimalOrNull(),
    rangeLower = rangeLower?.toBigDecimalOrNull(),
    rangeUpper = rangeUpper?.toBigDecimalOrNull(),
    changePercent = changePercent?.toBigDecimalOrNull(),
    repeatMode = AlertRepeatMode.fromKey(repeatMode),
    cooldownMinutes = cooldownMinutes,
    enabled = enabled == 1,
    playSound = playSound == 1,
    vibrate = vibrate == 1,
    webhookIds = webhookIds.split(',').mapNotNull { it.trim().toLongOrNull() },
    createdAt = createdAt,
    source = AlertRuleSource.fromKey(source),
    indicatorLineId = indicatorLineId,
)

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
    cooldownMinutes = cooldownMinutes,
    enabled = if (enabled) 1 else 0,
    playSound = if (playSound) 1 else 0,
    vibrate = if (vibrate) 1 else 0,
    webhookIds = webhookIds.joinToString(","),
    createdAt = createdAt,
    source = source.key,
    indicatorLineId = indicatorLineId,
)

fun IndicatorLineEntity.toDomain(): IndicatorLine = IndicatorLine(
    id = id,
    market = MarketType.fromKey(market),
    symbol = symbol,
    kind = IndicatorKind.fromKey(kind) ?: IndicatorKind.MA,
    interval = CandleInterval.fromStorageKey(intervalKey) ?: CandleInterval.of(OfficialInterval.H1),
    maPeriod = maPeriod,
    alertMode = LineAlertMode.fromKey(alertMode),
    members = if (members.isBlank()) emptyList() else members.split(',').mapNotNull(LineMember::parse),
    enabled = enabled == 1,
    createdAt = createdAt,
)

fun IndicatorLine.toEntity(): IndicatorLineEntity = IndicatorLineEntity(
    id = id,
    market = market.key,
    symbol = symbol,
    kind = kind.key,
    intervalKey = interval.storageKey,
    maPeriod = maPeriod,
    alertMode = alertMode.key,
    members = members.joinToString(",") { "${it.interval.storageKey}|${it.maPeriod}" },
    enabled = if (enabled) 1 else 0,
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
