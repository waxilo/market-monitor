package com.waxilo.marketmonitor.domain.alert

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.MarketType

/** 划线是哪一族指标线。[MA_BAND] 是聚合条目：成员均线集合共同维护上破/下破两条线。 */
enum class IndicatorKind(val key: String, val label: String) {
    MA("ma", "均线"),
    MA_BAND("ma_band", "均线带"),
    ;

    companion object {
        fun fromKey(key: String): IndicatorKind? = entries.firstOrNull { it.key == key }
    }
}

/** 均线带聚合条目里的一个成员：某周期的某条均线（[maPeriod] 恒 > 0）。 */
data class LineMember(val interval: CandleInterval, val maPeriod: Int) {

    companion object {
        /** 序列化格式 `intervalStorageKey|maPeriod`（storageKey 自带冒号，这里用竖线分隔）。 */
        fun parse(token: String): LineMember? {
            val parts = token.split('|')
            if (parts.size != 2) return null
            val interval = CandleInterval.fromStorageKey(parts[0]) ?: return null
            val period = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return LineMember(interval, period)
        }
    }
}

/**
 * 一条划线的告警方式。
 * [OFF] 只当参考不响；[ONCE] 命中一次后规则退场、模式自动回落到 OFF；
 * [EVERY_CROSS] 每次穿越都提醒（判定复用 [AlertRepeatMode] 的边沿语义）。
 */
enum class LineAlertMode(val key: String, val label: String) {
    OFF("off", "不告警"),
    ONCE("once", "单次"),
    EVERY_CROSS("every_cross", "每次穿越"),
    ;

    val repeatMode: AlertRepeatMode
        get() = when (this) {
            EVERY_CROSS -> AlertRepeatMode.EVERY_CROSS
            else -> AlertRepeatMode.ONCE
        }

    companion object {
        fun fromKey(key: String): LineAlertMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * 「指标线」（[LineAlertMode.OFF]）模式均线带的实时锚点：现价上/下方各最近的一条成员均线。
 *
 * 引擎按预警轮询节奏算好（与上下破预警同一套候选集合与穿越冷却，只是不挂规则不响），
 * 图上各画一条灰色水平线。null = 该侧没有可选成员（全在价下同侧/都在冷却）。
 */
data class BandAnchorDisplay(
    val lineId: Long,
    val market: MarketType,
    val symbol: String,
    val upper: Double?,
    val lower: Double?,
)

/**
 * 详情页的一条指标划线（用户显式配置的指标线，取代旧的「指标告警条目」候选池设计）。
 *
 * 引擎为开了告警（[alertMode] != [LineAlertMode.OFF]）的线至多维护一条规则
 * （[AlertRuleSource.INDICATOR]）：阈值跟随该线指标的最新取值，
 * 上破/下破在挂线时按线相对现价的位置定，之后线自己越过现价也算穿越。
 * 线被删除或告警关掉后，对应规则按孤儿回收。
 */
data class IndicatorLine(
    val id: Long = 0L,
    val market: MarketType,
    val symbol: String,
    val kind: IndicatorKind,
    val interval: CandleInterval,
    /** 均线周期；仅旧单周期 MA 线有意义。 */
    val maPeriod: Int = 0,
    val alertMode: LineAlertMode = LineAlertMode.OFF,
    /** [IndicatorKind.MA_BAND] 的均线成员集合（周期 × 均线）。 */
    val members: List<LineMember> = emptyList(),
    /**
     * 启用开关：关闭后引擎不再同步它的规则/锚点（已挂的规则按孤儿回收），
     * 图上也不画它的参考线 —— 配置原样保留，重新启用即恢复。
     */
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    /** 规则与图上参考线要覆盖的周期：多周期条目取成员周期，旧的单周期线取 [interval]。 */
    val coverIntervals: List<CandleInterval>
        get() = if (members.isNotEmpty()) members.map { it.interval } else listOf(interval)

    /** 规则名与展示用的完整标识（如「1h MA10」「均线带 1mMA10/20 · 15mMA10」）。 */
    val label: String
        get() = when (kind) {
            IndicatorKind.MA -> "${interval.label} MA$maPeriod"
            IndicatorKind.MA_BAND -> "均线带 " + members
                .groupBy { it.interval }
                .entries
                .joinToString(" · ") { (interval, group) ->
                    "${interval.label}MA${group.joinToString("/") { it.maPeriod.toString() }}"
                }
        }
}
