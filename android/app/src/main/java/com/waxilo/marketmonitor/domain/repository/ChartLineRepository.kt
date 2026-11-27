package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.indicator.MaOrigin
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
 * 用户在 K 线图上画的纯视觉线（画线工具里的「水平线/直线」），**也是均线分析代画的那批线**。
 *
 * 直线存两个锚点（[t1]/[p1]、[t2]/[p2]）而不是斜率截距：锚点是绝对时间戳 + 价格，
 * 图表平移/缩放、K 线增删后都能按同一换算链落回正确像素，与桌面端趋势线同构。
 * 整体拖改 = 两个锚点同步平移。
 *
 * 2026-11-26 起均线分析的线也落在这里（早先是 `alert_rule`，一条规则一条线）：
 * 那时「画一条线」必然等于「开一条预警」，与「默认只是一条参考线」的诉求正相反。
 * 现在线与告警分开 —— 线是线（本表），要告警才挂一条规则（见 [alertRuleId]）。
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
    /** 线上那几个字（全屏选中后在竖栏里改）。null = 没备注。 */
    val note: String? = null,
    /**
     * 系统划线的身份（哪一族均线、哪个周期、哪一档）。**null = 用户手画的线**。
     *
     * 与桌面端 `PriceLine.origin` 同构。以前 Android 靠「规则名 = 自动名」认人，
     * 用户一改备注就丢绑定；身份成为字段之后，改名只改字，不影响回显与自动对齐。
     */
    val origin: MaOrigin? = null,
    /**
     * 右轴那枚价位牌显不显示。
     *
     * 默认值按来源分：用户手画的线显示（一两条，价位是重点），系统线隐藏
     * （均线一次落好几条，右轴排一排价牌会盖住行情读数，而价位在面板里本来就能读到）。
     */
    val showBadge: Boolean = true,
    /**
     * 这条线挂着的告警规则 id。**null = 不告警**（默认）。
     *
     * 线是线、告警是告警：把「告警」打开就是给这条线建一条 `alert_rule`（阈值 = 线的价位），
     * 关掉就把规则删掉。挂在同一个实体上存一个外键，比另存一份「线 ↔ 规则」映射简单；
     * 规则被用户在预警页删掉时这里会指向空 —— 读取方按「找不到 = 不告警」处理。
     */
    val alertRuleId: Long? = null,
    /**
     * 画成虚线。**默认 false = 实线**（与桌面端 `dashed` 缺省同口径）。
     *
     * 纯样式开关：不进身份、不影响告警、不影响价签，自动对齐也照旧只挪价位。
     */
    val dashed: Boolean = false,
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

    /** 只改右轴价位牌的显隐。与 [setNote] 同一条理由：不整行写回。 */
    suspend fun setShowBadge(id: Long, show: Boolean)

    /** 只改挂着的告警规则（null = 不告警）。 */
    suspend fun setAlertRule(id: Long, ruleId: Long?)

    /** 只改线型（实线 / 虚线）。与 [setShowBadge] 同一条理由：不整行写回。 */
    suspend fun setDashed(id: Long, dashed: Boolean)

    /** 只改价位（自动对齐 / 拖动落库用）。 */
    suspend fun setPrice(id: Long, price: BigDecimal)

    /**
     * 清空指定标的的全部画线（「清空划线」用）。
     *
     * 只认标的不认类型：水平线 / 直线一起删 —— 用户点的是「清空」，不是「清空水平线」。
     */
    suspend fun deleteAll(market: MarketType, symbol: String)
}
