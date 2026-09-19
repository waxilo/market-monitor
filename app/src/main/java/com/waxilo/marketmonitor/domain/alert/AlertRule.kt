package com.waxilo.marketmonitor.domain.alert

import com.waxilo.marketmonitor.domain.model.MarketType
import java.math.BigDecimal

/** 触发条件。key 为持久化与 Webhook 报文使用的稳定标识。 */
enum class AlertCondition(val key: String, val label: String) {
    ABOVE("above", "上破"),
    BELOW("below", "下破"),
    OUT_OF_RANGE("out_of_range", "区间外"),
    RISE_BY("rise_by", "涨幅达到"),
    FALL_BY("fall_by", "跌幅达到"),
    ;

    companion object {
        fun fromKey(key: String): AlertCondition? = entries.firstOrNull { it.key == key }
    }
}

enum class AlertRepeatMode(val key: String, val label: String) {
    /** 命中一次后不再提醒。 */
    ONCE("once", "单次"),

    /** 每次穿越都提醒（价格反复贴近阈值时配合冷却时间使用）。 */
    EVERY_CROSS("every_cross", "每次穿越"),

    /** 条件持续满足期间按冷却间隔重复提醒。 */
    REPEAT("repeat", "重复提醒"),
    ;

    companion object {
        fun fromKey(key: String): AlertRepeatMode = entries.firstOrNull { it.key == key } ?: ONCE
    }
}

enum class AlertDirection(val key: String) {
    ABOVE("above"),
    BELOW("below"),
    UP("up"),
    DOWN("down"),
    ;

    companion object {
        fun fromKey(key: String): AlertDirection? = entries.firstOrNull { it.key == key }
    }
}

/** 价格预警规则（PRD FR-3.1）。金额一律 BigDecimal，序列化为字符串。 */
data class AlertRule(
    val id: Long = 0L,
    val market: MarketType,
    val symbol: String,
    val name: String,
    val condition: AlertCondition,
    /** ABOVE / BELOW 的目标价。 */
    val threshold: BigDecimal? = null,
    /** OUT_OF_RANGE 的下界。 */
    val rangeLower: BigDecimal? = null,
    /** OUT_OF_RANGE 的上界。 */
    val rangeUpper: BigDecimal? = null,
    /** RISE_BY / FALL_BY 的百分数阈值，按 24h 开盘基准，正数填写。 */
    val changePercent: BigDecimal? = null,
    val repeatMode: AlertRepeatMode = AlertRepeatMode.ONCE,
    /** 防风暴冷却，单位分钟。 */
    val cooldownMinutes: Int = 5,
    val enabled: Boolean = true,
    val playSound: Boolean = true,
    val vibrate: Boolean = true,
    /** 关联的 Webhook 端点 id；为空表示不推送外部。 */
    val webhookIds: List<Long> = emptyList(),
    val createdAt: Long = 0L,
) {
    val symbolKey: String get() = "${market.key}:$symbol"
}

/** 规则的运行时状态，与规则本体分开持久化。 */
data class AlertState(
    val lastPrice: BigDecimal? = null,
    val lastTriggeredAt: Long? = null,
    /** 条件在上一轮观测中是否已满足，用于「穿越」边沿判定。 */
    val wasSatisfied: Boolean = false,
    /** ONCE 模式的一次性闸门。 */
    val fired: Boolean = false,
)

sealed interface AlertDecision {
    data object Silent : AlertDecision

    data class Triggered(
        val rule: AlertRule,
        val direction: AlertDirection,
        val price: BigDecimal,
        val changePercent: Double?,
        val atMs: Long,
    ) : AlertDecision
}
