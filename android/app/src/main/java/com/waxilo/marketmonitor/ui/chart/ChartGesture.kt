package com.waxilo.marketmonitor.ui.chart

import kotlin.math.abs
import kotlin.math.exp

/**
 * 图表手势的方向判定。抽成纯函数是为了能在 JVM 上直接断言 ——
 * 手势逻辑一旦错了，图上表现是「斜着拖会同时平移又缩放」，
 * 这种问题靠模拟器截图很难复现，但用累积位移直接测就一目了然。
 */
object ChartGesture {

    /** 单指手势锁定的方向。 */
    enum class Axis { HORIZONTAL, VERTICAL }

    /**
     * 根据累积位移判断本次单指手势的方向。
     *
     * 返回 `null` 表示「还没定」——两个方向的累积量都没到 [threshold]。
     * 这是必需的中间态：手指刚按下时轨迹总是斜的，
     * 若一有位移就定性，纵向拖动会因为前几帧的横向抖动被判成横向平移。
     *
     * 到阈值后用**绝对值较大的那个方向**定性；
     * 恰好相等时判横向（横向平移是更高频的操作）。
     */
    fun axisLock(accumX: Float, accumY: Float, threshold: Float): Axis? {
        if (threshold <= 0f) return null
        val ax = abs(accumX)
        val ay = abs(accumY)
        if (ax < threshold && ay < threshold) return null
        return if (ax >= ay) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /**
     * 双指张合换算成缩放因子。
     *
     * [previous] 与 [current] 是两指间距；**张开时因子 < 1**：
     * 间距变大意味着想看得更细（可见根数变少），
     * 调用方拿到因子后乘到「可见根数/量程」上，取比值比直接用比值更直观。
     *
     * 横纵两个维度**各调一次**，各管各的轴：横向间距算出的因子进 `ChartViewport.zoom()`，
     * 纵向间距算出的因子进价格量程的缩放。同一个函数、同一套语义 ——
     * 拉开 = 因子 < 1 = 看得更细（时间轴可见根数变少、价格量程变小 K 线变高）。
     *
     * 单帧的比值被钳在 [[MIN_RATIO], [MAX_RATIO]]，
     * 否则一次跳变的 event（指头抬起/落下）会让图瞬间缩放几十倍。
     */
    fun pinchFactor(previous: Float, current: Float): Float? {
        if (previous <= 0f || current <= 0f) return null
        return (previous / current).coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /**
     * 拖动**价格刻度区**时的纵向位移换算成价格量程的缩放因子。
     *
     * 语义与 `ValueRange.scaled()` 的 factor 一致：**因子 < 1 = 量程变小 = K 线纵向变高**。
     *
     * 方向按用户指定：**向上拖（[deltaY] < 0）= 拉升 = K 线变高**，向下拖 = 收缩变矮。
     * 取指数而不是线性：每帧位移都很小，指数映射保证「拖过的总距离」与「缩放倍数」
     * 是一一对应的乘性关系 —— 分十帧拖 100px 与一帧拖 100px 结果完全相同，
     * 而线性映射在跨越钳制边界时会丢掉已经走过的行程。
     *
     * 单帧因子钳在 `[1/e, e]`：一次事件的位移不可能超过一个绘图区高度，
     * 万一上报了异常值（多指切换、坐标系跳变）也不至于让图瞬间缩放几十倍。
     *
     * @param deltaY 本帧纵向像素位移（屏幕坐标，向下为正）
     * @param plotHeightPx 主图高度，用来把像素位移归一化成「拖了几屏」
     */
    fun priceScaleFactor(deltaY: Float, plotHeightPx: Float): Float? {
        if (plotHeightPx <= 0f || !plotHeightPx.isFinite() || !deltaY.isFinite()) return null
        return exp((deltaY / plotHeightPx).coerceIn(-1f, 1f))
    }

    /**
     * 把单帧像素位移换算成「根数」（可为小数）。
     *
     * 结果**不做取整**：视窗的可见根数与右端偏移都是浮点，单帧位移折算出来
     * 通常不足一根，取整会把零头全抹掉 —— 历史上表现为「横向完全拖不动」。
     *
     * 非法输入（[slotPx] 非正/非有限、[pixelDelta] 非有限）视为「本帧无位移」，
     * 避免手抖出个 NaN 就把视窗写坏。
     */
    fun barDelta(pixelDelta: Float, slotPx: Float): Float {
        if (slotPx <= 0f || !slotPx.isFinite() || !pixelDelta.isFinite()) return 0f
        val bars = pixelDelta / slotPx
        return if (bars.isFinite()) bars else 0f
    }

    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2f
}
