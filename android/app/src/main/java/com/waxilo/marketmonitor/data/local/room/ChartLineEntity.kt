package com.waxilo.marketmonitor.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.waxilo.marketmonitor.domain.indicator.MaFamily
import com.waxilo.marketmonitor.domain.indicator.MaOrigin
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind

/** 图上画线（水平线/直线，含均线分析代画的那批）。金额字符串存储，与 alert_rule 同套路。 */
@Entity(tableName = "chart_line")
data class ChartLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val market: String,
    val symbol: String,
    /** [ChartLineKind.key]：h / trend。 */
    val kind: String,
    val price: String?,
    val t1: Long?,
    val p1: String?,
    val t2: Long?,
    val p2: String?,
    /** 线上那句话（全屏选中这条线后在竖栏里改）。null / 空串都表示没备注。 */
    val note: String? = null,
    /**
     * 均线分析代画的线的身份：(族, 周期, 档位)。三列都是 null = 用户手画的线。
     *
     * 拆成三列而不是一个字符串：周期存 [CandleInterval.storageKey]（`o:1h` / `c:180`），
     * 与周期条配置用的是同一个稳定键，比对的永远是同一个东西。VARCHAR 拼串也能存，
     * 但下次要按族或按周期查询时就只能扫全表。
     */
    val originKind: String? = null,
    val originInterval: String? = null,
    val originPeriod: Int? = null,
    /** 右轴价位牌是否显示（1 = 显示）。系统线建线时写 0，用户手画默认 1。 */
    val showBadge: Int = 1,
    /** 挂着的告警规则 id（null = 不告警）。删线时连它一起收。 */
    val alertRuleId: Long? = null,
    /**
     * 画成虚线（1 = 虚线）。纯样式，与 [showBadge] / [alertRuleId] 互不影响。
     *
     * 老行由迁移补 0 —— 默认实线，和 v12 之前的行为一致。
     */
    val dashed: Int = 0,
    val createdAt: Long,
)

fun ChartLineEntity.toDomain(): ChartLine? {
    val lineKind = ChartLineKind.fromKey(kind) ?: return null
    return ChartLine(
        id = id,
        market = MarketType.fromKey(market),
        symbol = symbol,
        kind = lineKind,
        price = price?.toBigDecimalOrNull(),
        t1 = t1,
        p1 = p1?.toBigDecimalOrNull(),
        t2 = t2,
        p2 = p2?.toBigDecimalOrNull(),
        note = note,
        origin = originOf(originKind, originInterval, originPeriod),
        // 只有显式的 0 才算「用户关掉价签」：老行没有这一列（迁移补 1），读出来仍是显示
        showBadge = showBadge != 0,
        alertRuleId = alertRuleId,
        dashed = dashed != 0,
        createdAt = createdAt,
    )
}

/** 三列身份 → 领域对象。任一列缺失/认不出就整条当「用户手画」（与桌面端 `parseOrigin` 同口径）。 */
private fun originOf(kind: String?, interval: String?, period: Int?): MaOrigin? {
    val family = when (kind) {
        MaFamily.MA.name -> MaFamily.MA
        MaFamily.EMA.name -> MaFamily.EMA
        else -> return null
    }
    val iv = interval?.takeIf { it.isNotEmpty() }?.let(CandleInterval::fromStorageKey) ?: return null
    if (period == null || period <= 0) return null
    return MaOrigin(family, iv, period)
}

fun ChartLine.toEntity(): ChartLineEntity = ChartLineEntity(
    id = id,
    market = market.key,
    symbol = symbol,
    kind = kind.key,
    price = price?.toPlainString(),
    t1 = t1,
    p1 = p1?.toPlainString(),
    t2 = t2,
    p2 = p2?.toPlainString(),
    note = note,
    originKind = origin?.family?.name,
    originInterval = origin?.interval?.storageKey,
    originPeriod = origin?.period,
    showBadge = if (showBadge) 1 else 0,
    alertRuleId = alertRuleId,
    dashed = if (dashed) 1 else 0,
    createdAt = createdAt,
)
