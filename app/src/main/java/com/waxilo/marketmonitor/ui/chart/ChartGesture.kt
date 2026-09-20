package com.waxilo.marketmonitor.ui.chart

import kotlin.math.abs

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
     * 单帧的比值被钳在 [[MIN_RATIO], [MAX_RATIO]]，
     * 否则一次跳变的 event（指头抬起/落下）会让图瞬间缩放几十倍。
     */
    fun pinchFactor(previous: Float, current: Float): Float? {
        if (previous <= 0f || current <= 0f) return null
        return (previous / current).coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /**
     * 横向平移的累积结果：这一帧真正移动的**整根数**，以及留给下一帧的零头。
     *
     * @property bars 本次可以提交给 `ChartViewport.pan()` 的整根数（带符号，可为 0）
     * @property remainder 不足一根、攒着下次再用的余量（与 [bars] 同号，绝对值 < 1）
     */
    data class BarPan(val bars: Int, val remainder: Float)

    /**
     * 把单帧像素位移换算成「整根数 + 余量」。
     *
     * 为什么需要累积而不是直接 `roundToInt()`：一次手势事件通常只移动几个像素，
     * 除以每根宽度后远不到半根，`roundToInt()` 会把它抹成 0 ——
     * 每一帧都被抹掉，累积起来就是「横向完全拖不动」。
     *
     * 这里用 `toInt()`（**向零取整**，保留符号）而不是 `roundToInt()`：
     * 位移不足一根时 `bars` 保持 0、零头进 [remainder]，
     * 攒够一根才提交；反向拖动时负零头同样被留住，不会出现「回拖无反应」。
     *
     * 非法输入（[slotPx] 非正/非有限、[pixelDelta] 非有限）视为「本帧无位移」，
     * 但**保留既有余量**，避免手抖出个 NaN 就把累积量清掉。
     */
    fun accumulateBarPan(remainder: Float, pixelDelta: Float, slotPx: Float): BarPan {
        if (slotPx <= 0f || !slotPx.isFinite() || !pixelDelta.isFinite()) {
            return BarPan(bars = 0, remainder = remainder)
        }
        val total = remainder + pixelDelta / slotPx
        if (!total.isFinite()) return BarPan(bars = 0, remainder = remainder)
        val whole = total.toInt()
        return BarPan(bars = whole, remainder = total - whole)
    }

    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2f
}
