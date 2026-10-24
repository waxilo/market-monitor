package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/** 图上视觉画线的类型：水平线只锚定价位；直线由两个 (时间, 价位) 锚点确定。 */
enum class ChartLineKind(val key: String) {
    HORIZONTAL("h"),
    TREND("trend"),
    ;

    companion object {
        fun fromKey(key: String): ChartLineKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 用户在 K 线图上画的纯视觉线（与预警无关，画线工具里的「水平线/直线」）。
 *
 * 直线存两个锚点（[t1]/[p1]、[t2]/[p2]）而不是斜率截距：锚点是绝对时间戳 + 价格，
 * 图表平移/缩放、K 线增删后都能按同一换算链落回正确像素，与桌面端趋势线同构。
 * 整体拖改 = 两个锚点同步平移。
 */
data class ChartLine(
    val id: Long = 0L,
    val market: MarketType,
    val symbol: String,
    val kind: ChartLineKind,
    /** 水平线价位；直线为 null。 */
    val price: BigDecimal? = null,
    val t1: Long? = null,
    val p1: BigDecimal? = null,
    val t2: Long? = null,
    val p2: BigDecimal? = null,
    /** 用户写的备注（全屏选中后在竖栏里加）。null = 没备注。 */
    val note: String? = null,
    val createdAt: Long,
)

interface ChartLineRepository {

    /** 指定标的的画线，按落笔先后返回。 */
    fun lines(market: MarketType, symbol: String): Flow<List<ChartLine>>

    /** 新增或更新，返回 id。 */
    suspend fun save(line: ChartLine): Long

    suspend fun delete(id: Long)

    /**
     * 只改备注，不动几何。
     *
     * 不复用 [save]：那是整行 upsert，调用方手上若没有完整的原行（比如只改备注时
     * 没有端点字段），写回去会把价位/端点一起抹掉。
     */
    suspend fun setNote(id: Long, note: String?)

    /**
     * 清空指定标的的全部画线（「清空划线」用）。
     *
     * 只认标的不认类型：水平线 / 直线一起删 —— 用户点的是「清空」，不是「清空水平线」。
     */
    suspend fun deleteAll(market: MarketType, symbol: String)
}
