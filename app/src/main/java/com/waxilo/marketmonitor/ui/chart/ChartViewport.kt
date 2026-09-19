package com.waxilo.marketmonitor.ui.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图表可视窗口（PRD FR-2.1 的缩放与平移）。
 * 只以「蜡烛根数」为单位表达，不碰像素与 Compose 类型，因此在 JVM 单测里可穷举；
 * 像素换算留在绘制层做。
 */
data class ChartViewport(
    val barCount: Int,
    val visibleBars: Int,
    /** 窗口右端到最新一根之间空出的根数；0 表示贴着最新价。 */
    val rightOffset: Int = 0,
) {

    /** 单屏最少 15 根（再多影线就糊成一条线），最多不超过序列长度与 [MAX_BARS]。 */
    private fun visibleBound(): IntRange =
        min(MIN_BARS, max(1, barCount))..min(max(1, barCount), MAX_BARS)

    private fun effectiveVisible(): Int = visibleBars.coerceIn(visibleBound())

    fun clamp(): ChartViewport {
        val visible = effectiveVisible()
        val offset = rightOffset.coerceIn(0, max(0, barCount - visible))
        return copy(visibleBars = visible, rightOffset = offset)
    }

    /** 可见区间，两端都闭合；空序列返回空区间。 */
    fun window(): IntRange {
        if (barCount <= 0) return 1..0
        val current = clamp()
        val end = current.barCount - 1 - current.rightOffset
        return (end - current.visibleBars + 1).coerceAtLeast(0)..end
    }

    fun startIndex(): Int = window().first
    fun endIndex(): Int = window().last

    /** 平移：[deltaBars] > 0 表示向更早的方向拖（手指往右移）。 */
    fun pan(deltaBars: Float): ChartViewport =
        copy(rightOffset = (rightOffset + deltaBars.roundToInt()).coerceAtLeast(0)).clamp()

    /**
     * 缩放：[barFactor] 为新可见根数 / 旧可见根数（双指张开 <1，捏合 >1）。
     * [anchorRatio] 为手势焦点在窗口内的横向位置（0 左端、1 右端），缩放时该处的蜡烛保持不动。
     */
    fun zoom(barFactor: Float, anchorRatio: Float = 1f): ChartViewport {
        val current = clamp()
        if (current.barCount <= 0) return current
        val target = (current.visibleBars * barFactor.coerceIn(0.05f, 20f)).roundToInt()
            .coerceIn(current.visibleBound())
        val start = current.barCount - current.visibleBars - current.rightOffset
        val ratio = anchorRatio.coerceIn(0f, 1f)
        val anchor = start + (current.visibleBars * ratio).roundToInt()
        val newStart = anchor - (target * ratio).roundToInt()
        return ChartViewport(current.barCount, target, current.barCount - target - newStart).clamp()
    }

    /** 序列变长（新蜡烛或翻页）后维持同一右端视感：默认贴回最新。 */
    fun resize(newBarCount: Int, keepRightOffset: Boolean = false): ChartViewport =
        if (keepRightOffset) copy(barCount = newBarCount).clamp()
        else ChartViewport(newBarCount, effectiveVisible(), 0).clamp()

    /** 第 [index] 根在窗口内的归一化横向位置（0..1）；窗口外返回 NaN。 */
    fun fractionOf(index: Int): Float {
        val range = window()
        if (index !in range || range.last == range.first) return Float.NaN
        return (index - range.first).toFloat() / range.last.minus(range.first).toFloat()
    }

    /** 归一化横坐标反查蜡烛下标，供十字光标取 tooltip。 */
    fun indexAt(fraction: Float): Int {
        val range = window()
        if (range.first > range.last) return -1
        val ratio = fraction.coerceIn(0f, 1f)
        return range.first + (ratio * (range.last - range.first)).roundToInt()
    }

    companion object {
        const val MIN_BARS = 15
        const val MAX_BARS = 600
        const val DEFAULT_VISIBLE = 120

        fun initial(barCount: Int, visibleBars: Int = DEFAULT_VISIBLE): ChartViewport =
            ChartViewport(barCount, min(visibleBars, max(1, barCount)), 0).clamp()
    }
}

/** 一段数值的最小/最大值，图表纵轴与副图窗格共用。 */
data class ValueRange(val low: Double, val high: Double) {

    /** 上下各留 [ratio] 的余量，否则最长影线会贴着边框。 */
    fun padded(ratio: Double = DEFAULT_PADDING): ValueRange {
        if (low.isNaN() || high.isNaN()) return ValueRange(0.0, 1.0)
        if (low == high) {
            val pad = max(kotlin.math.abs(low) * ratio, EPSILON)
            return ValueRange(low - pad, high + pad)
        }
        val span = high - low
        return ValueRange(low - span * ratio, high + span * ratio)
    }

    /** 归一化纵向位置：0 为顶部、1 为底部（像素轴向下），未定义值原样返回 NaN。 */
    fun toFraction(value: Double): Float {
        if (value.isNaN()) return Float.NaN
        val span = high - low
        if (span <= EPSILON) return 0.5f
        return ((high - value) / span).toFloat().coerceIn(-0.5f, 1.5f)
    }

    fun fromFraction(fraction: Float): Double {
        val span = high - low
        return high - fraction.toDouble() * span
    }

    /** 纵轴刻度：取 1-2-5 阶梯，保证任何量级下都是 4~6 条网格线。 */
    fun gridLines(count: Int = 5): List<Double> {
        if (low.isNaN() || high.isNaN() || high <= low) return emptyList()
        val rough = (high - low) / max(1, count)
        val magnitude = Math.pow(10.0, floor(Math.log10(rough)))
        val normalized = rough / magnitude
        val step = when {
            normalized < 1.5 -> 1.0
            normalized < 3.5 -> 2.0
            normalized < 7.5 -> 5.0
            else -> 10.0
        } * magnitude
        if (step <= 0 || step.isInfinite() || step.isNaN()) return listOf(low, high)
        val first = ceil(low / step) * step
        val lines = mutableListOf<Double>()
        var value = first
        while (value <= high + step * 1e-6 && lines.size <= MAX_GRID_LINES) {
            lines.add(value)
            value += step
        }
        return lines
    }

    companion object {
        const val DEFAULT_PADDING = 0.06
        const val EPSILON = 1e-12
        const val MAX_GRID_LINES = 12

        fun of(values: DoubleArray, from: Int = 0, to: Int = values.lastIndex): ValueRange? {
            val start = max(0, min(from, values.size))
            val end = min(to, values.size - 1)
            if (start > end) return null
            var low = Double.NaN
            var high = Double.NaN
            for (i in start..end) {
                val v = values[i]
                if (v.isNaN()) continue
                if (low.isNaN() || v < low) low = v
                if (high.isNaN() || v > high) high = v
            }
            return if (low.isNaN()) null else ValueRange(low, high)
        }

        fun of(values: Collection<Double>): ValueRange? =
            of(values.filterNot { it.isNaN() }.toDoubleArray())
    }
}
