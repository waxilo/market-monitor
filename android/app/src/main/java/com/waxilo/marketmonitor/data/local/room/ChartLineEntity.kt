package com.waxilo.marketmonitor.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
import java.math.BigDecimal

/** 图上视觉画线（水平线/直线）。金额字符串存储，与 alert_rule 同套路。 */
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
    /** 用户写的备注（全屏选中这条线后在竖栏里加）。null / 空串都表示没备注。 */
    val note: String? = null,
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
        createdAt = createdAt,
    )
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
    createdAt = createdAt,
)
