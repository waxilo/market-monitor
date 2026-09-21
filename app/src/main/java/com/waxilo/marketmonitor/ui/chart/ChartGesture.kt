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

    /**
     * 缩放（捏合）的累积结果。
     *
     * @property bars 本帧应提交给 [ChartViewport.zoom] 的**根数步进**（0 = 本帧不提交，可正可负）
     * @property remainder 尚未凑够一根而攒下的**根数**余量
     *
     * 为什么提交的是「根数」而不是「因子」：`ChartViewport.zoom()` 最后要把可见根数
     * `roundToInt()`，**实际生效的变化量未必等于请求的量**。调用方必须把真实生效的
     * 根数变化回调给 [reportApplied]，否则取整误差会一轮轮混进余量里累积
     * （实测余量能漂到 -26 根，缩放手感完全脱节）。
     */
    data class PinchBar(val bars: Int, val remainder: Float)

    /**
     * 把每一帧的捏合因子累积成「真正能改变可见根数」的根数步进。
     *
     * ## 要解决什么
     *
     * 不能逐帧直接 `zoom()`：`ChartViewport.zoom()` 内部要对可见根数
     * `roundToInt()`。可见 120 根时，手指缓慢张开一帧只让间距变 1px
     * （因子约 1.0033），`120 * 1.0033 = 120.4` 一取整又回到 **120** ——
     * 每帧都被抹平，表现为「手指慢慢捏了半天，图纹丝不动」。
     * 只有快速大幅捏合（单帧变化超过半根）才看得出效果，
     * 这正是「双指缩放时灵时不灵 / 无法放大放小」的根因。
     *
     * ## 为什么余量以「根数」而不是「对数」记账（写错过两次，务必看）
     *
     * 直觉做法是把因子取对数后累加，凑够 `stepLog = ln((bars+1)/bars)` 再提交。
     * 但 `stepLog` **随 bars 变**、bars 又随每次提交而变，旧对数换到新单位下不再成立：
     * 要么被误判成「没攒够」而丢量，要么被当成「攒了很多」而**一帧冲过头**。
     *
     * ## 为什么必须允许回调真实生效量
     *
     * 余量改用根数记账后，仍然会漂：因为 `zoom()` 收到因子 `(bars+1)/bars` 后
     * 走的是 `(visibleBars * factor).roundToInt()`，而取整结果**不等于 bars±1**
     * （浮点误差 + 锚点反推都会掺进来）。这部分差额若不校正，会被当成「手指没捏完的量」
     * 留在余量里，一帧帧累加就成了持续的偏置。
     *
     * 所以调用方在 `zoom()` 之后**必须**把真实生效的根数变化调 [reportApplied] 报回来，
     * 由它扣掉余量。只提交不校正 = 手感会慢慢跑偏。
     *
     * ## 反向必须立刻换向（写错过一次，务必看）
     *
     * 每帧只提交一根意味着快速捏合会攒下一笔**同方向的欠账**（几十根）。
     * 手指中途反向时，这笔欠账若继续参与抵扣，反向的前若干帧仍然提交旧方向的 -1/+1 ——
     * 图上表现为「手指已经在往回收，图还在朝原方向继续跑」，越快的捏合越明显。
     * 所以本帧折算量与余量**异号**时先把余量清零，反向第一帧就换向。
     * 这与 `ValueRange.panLimit` 里「钳累积量而不是钳渲染结果」是同一类问题：
     * 累积量本身带着方向，方向一变就必须作废。
     *
     * @param remainder 上一帧留下的**根数**余量（不是对数）
     * @param factor 本帧的捏合因子（来自 [pinchFactor]）
     * @param visibleBars 当前可见根数（用于把因子折算成根数）
     */
    fun accumulatePinch(remainder: Float, factor: Float, visibleBars: Int): PinchBar {
        if (!factor.isFinite() || factor <= 0f) return PinchBar(0, remainder)
        val bars = visibleBars.coerceAtLeast(1)
        val ratio = factor.coerceIn(MIN_RATIO, MAX_RATIO).toDouble()
        // 因子 → 根数：在「bars 根占满一屏」的假设下，可见根数变为 bars * ratio，
        // 因此根数增量 = bars * (ratio - 1)。
        // 注意 ratio < 1（张开手指，间距变大）→ 增量为负 → 可见根数变少 = 放大。
        // 这与 TradingView 一致：张开 = 放大 = 看得更少更细；合拢 = 缩小 = 看得更多。
        val folded = (bars * (ratio - 1.0)).toFloat()
        // 异号 = 手指改了方向 → 旧方向的欠账作废（见上方「反向必须立刻换向」）。
        // 本帧折算量为 0（factor == 1）时没有方向信息，余量原样结转继续逐帧消化。
        val carried = if (remainder * folded < 0f) 0f else remainder
        val total = carried + folded
        if (!total.isFinite()) return PinchBar(0, remainder)
        // 没攒够一根：本帧不缩放，余量留到下一帧（这是「缓慢捏合仍生效」的关键）
        val whole = total.toInt()
        if (whole == 0) return PinchBar(0, total)
        // 每帧最多走一根：既不会把攒下的量一次放完（冲过头），也不会清零丢量。
        // 快速张合时每帧都能提交一根，累积起来自然就是连续缩放。
        val step = whole.coerceIn(-1, 1)
        return PinchBar(bars = step, remainder = total - step)
    }

    /**
     * 把「实际生效的根数变化」从余量里扣掉 —— 见 [accumulatePinch] 的说明。
     *
     * @param remainder [accumulatePinch] 返回的余量
     * @param requestedBars 本帧请求提交的根数（[PinchBar.bars]）
     * @param appliedBars 调用方实际生效的根数变化（新 visibleBars - 旧 visibleBars）
     */
    fun reportApplied(remainder: Float, requestedBars: Int, appliedBars: Int): Float {
        if (requestedBars == 0) return remainder
        // 请求 step 根、实际只生效 appliedBars 根时，差额要退回余量里下次再用
        val corrected = remainder + (requestedBars - appliedBars)
        return corrected
    }

    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2f
}
