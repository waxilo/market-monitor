package com.waxilo.marketmonitor.ui.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图表可视窗口（PRD FR-2.1 的缩放与平移）。
 *
 * **这里只存「用户意图」两个量**：[visibleBars]（看多宽）与 [rightOffset]（右端停在哪儿）。
 * 序列长度 `barCount` **不是视窗的状态，而是每次查询时的入参** —— 它是数据层的投影，
 * 存进来就必须和 `series.size` 保持同步，而「保持同步」正是历史上出问题的地方：
 *
 * > 旧版把 `barCount` 存成字段，靠 `LaunchedEffect(series.size)` 事后对齐。
 * > 但 effect 在组合结束后的副作用里才跑，不参与当前帧绘制 —— 首个「数据已到、
 * > 视窗未对齐」的帧里 `barCount` 还是 0（初始值），`window()`/`clamp()` 全按 0 算，
 * > 量程与窗口一起错位，表现为「首次进入 K 线图挤在一角，切一下周期才恢复」。
 *
 * 改成入参之后，`barCount` 与 `series.size` 在**同一个表达式里**取值，
 * 结构上不可能再脱钩，那几条对齐用的 `LaunchedEffect` 也随之删掉。
 *
 * 因为不碰像素与 Compose 类型，本类在 JVM 单测里可穷举。
 */
data class ChartViewport(
    /**
     * 可见根数（**浮点**）。
     *
     * 为什么不是 `Int`：双指缩放时手指是连续移动的，单帧折算出的根数变化远不到一根
     * （可见 60 根时缓慢捏合一帧只有零点几根）。若把它取整，量化误差会让整张图
     * 「几帧不动、突然跳一根」—— 一根 15px，跳一根就是肉眼可见的横向抖动；
     * 快速捏合时又会被限成每帧一根，画面严重滞后于手指。改成浮点后
     * [zoom] 不再取整，缩放是连续的。
     *
     * 取数据的下标仍必须是整数，所以 [window]/[plotRange] 内部才取整；
     * 像素定位走 [plotStart] 这个浮点左端。
     */
    val visibleBars: Float = DEFAULT_VISIBLE,
    /**
     * 右端**越过最新一根**空出的根数（浮点，理由同 [visibleBars]）。
     *
     * - `0` = 窗口右端正好贴着最新价（默认）；
     * - `> 0` = 最右边是一个窗口左移的效果，最新一根被推到屏幕内偏左，
     *   右侧露出 [rightOffset] 根空白 —— 真实交易软件里都允许这样看形态；
     * - `< 0` = 窗口整体移向更早的数据（`|rightOffset|` 根历史数据被移出右边界之外）。
     *
     * 也就是说：**向右拖（看更早）→ 负数；向左拖（留白看最新形态）→ 正数**。
     * 历史上这里只允许 `>= 0`（把"看更早"当成"留白"），
     * 于是最新一根永远只能顶在最右边，往左拖不动。
     */
    val rightOffset: Float = 0f,
) {

    /** 单屏最少 15 根（再多影线就糊成一条线），最多不超过序列长度与 [MAX_BARS]。 */
    private fun minVisible(barCount: Int): Float = min(MIN_BARS, max(1, barCount)).toFloat()

    private fun maxVisible(barCount: Int): Float = min(max(1, barCount), MAX_BARS).toFloat()

    private fun effectiveVisible(barCount: Int): Float =
        visibleBars.coerceIn(minVisible(barCount), maxVisible(barCount))

    /**
     * 右侧最多能留出多少根空白（让最新一根可以被推到屏幕中间偏左）。
     *
     * 取可见根数的 `(1 - MIN_VISIBLE_SHARE)`：保证数据区至少占满可视区的
     * [MIN_VISIBLE_SHARE] —— 与纵向平移的 `panLimit` 是同一套「不许拖成空屏」原则。
     * 不留这个上限的话，用户可以一路往左拖到只剩一片空白。
     */
    private fun maxRightBlank(visible: Float): Float =
        max(0f, visible * (1f - MIN_VISIBLE_SHARE))

    /**
     * 把意图夹到 [barCount] 允许的范围内。每次查询前都会调用，
     * 因此字段里允许短暂存在越界值（比如刚缩放完还没换过序列）。
     */
    fun clamp(barCount: Int): ChartViewport {
        val visible = effectiveVisible(barCount)
        // 左界：最多把窗口右端推到「只看得到 1 根」之前 —— 即移出 (barCount-visible) 根历史
        val minOffset = -max(0f, barCount - visible)
        // 右界：最新一根被推到屏幕内偏左，右侧最多留 maxRightBlank 根空白。
        // 但**整段序列一屏就放得下**时不留白：此时屏幕里本来就没有多余空间，
        // 留白只会把最老的那几根挤出左边界（新币的月线常常只有十几根）。
        val maxOffset = if (barCount <= visible) 0f else maxRightBlank(visible)
        val offset = rightOffset.coerceIn(minOffset, maxOffset)
        return copy(visibleBars = visible, rightOffset = offset)
    }

    /**
     * 绘图区左端的**浮点**根下标（可为负、可越出序列末尾）。
     *
     * 与 [plotRange] 的分工：[plotRange] 取整、用来**取数据**（下标不能是小数）；
     * 本函数不取整、用来**算像素** —— 可见根数是连续变化的，位置若按整数根算，
     * 每变一根整张图就横向跳一个槽宽，那正是「捏合时抖动」。
     *
     * 窗口右端 = `barCount - 1 + rightOffset`，故左端 = 右端 - visibleBars + 1。
     */
    fun plotStart(barCount: Int): Float {
        if (barCount <= 0) return 0f
        val current = clamp(barCount)
        return barCount + current.rightOffset - current.visibleBars
    }

    /**
     * **真实数据**的可见区间，两端闭合，保证落在 `0..barCount-1` 内。
     *
     * 右侧留白（`rightOffset > 0`）时窗口右端会越出序列末根，
     * 本函数把它夹回末根 —— 所有**按索引取数据**的地方都用它（索引必须安全）。
     * 需要「含空白的完整绘图区」的地方用 [plotRange]。
     *
     * 两端都**向下取整**：左端 `floor` 能把左侧被裁掉一半的那根也纳进来（它确实可见），
     * 右端 `floor` 恰好排除掉完全落在边界外的那根。
     */
    fun window(barCount: Int): IntRange {
        if (barCount <= 0) return 1..0
        val current = clamp(barCount)
        val end = floor(barCount - 1 + current.rightOffset).toInt().coerceIn(0, barCount - 1)
        val start = floor(barCount + current.rightOffset - current.visibleBars).toInt()
            .coerceIn(0, end)
        return start..end
    }

    /**
     * 完整绘图区区间（含右侧留白），可能**越出** `0..barCount-1`。
     *
     * 供绘制层做「横向位置/网格/时间轴刻度」这类**纯几何**计算：
     * 越界的位置由 `xOf` 落到位图外，再由 `clipRect` 裁掉，不需要索引数据。
     */
    fun plotRange(barCount: Int): IntRange {
        if (barCount <= 0) return 1..0
        val current = clamp(barCount)
        val end = floor(barCount - 1 + current.rightOffset).toInt()
        val start = floor(barCount + current.rightOffset - current.visibleBars).toInt()
        return start..end
    }

    fun startIndex(barCount: Int): Int = plotRange(barCount).first
    fun endIndex(barCount: Int): Int = plotRange(barCount).last

    /**
     * 平移：[deltaBars] > 0 表示手指往右移 = 看更早的数据（窗口左移）；
     * 负值表示手指往左移 = 把最新一根往屏幕里推，右侧露出空白。
     *
     * 不做任何取整 —— 单帧位移折算出来通常不足一根，取整会把零头全抹掉，
     * 历史上表现为「横向完全拖不动」。浮点视窗天然没有这个问题。
     */
    fun pan(deltaBars: Float, barCount: Int): ChartViewport =
        copy(rightOffset = rightOffset - deltaBars).clamp(barCount)

    /**
     * 缩放：[barFactor] 为新可见根数 / 旧可见根数（双指张开 <1，捏合 >1）。
     * [anchorRatio] 为手势焦点在窗口内的横向位置（0 左端、1 右端），缩放时该处的蜡烛保持不动。
     *
     * 可见根数**不取整**（见 [visibleBars] 的说明）：取整会让缓慢捏合被量化成
     * 「几帧不动、突然跳一根」。
     */
    fun zoom(barFactor: Float, anchorRatio: Float = 1f, barCount: Int): ChartViewport {
        val current = clamp(barCount)
        if (barCount <= 0 || !barFactor.isFinite() || barFactor <= 0f) return current
        val target = (current.visibleBars * barFactor.coerceIn(0.05f, 20f))
            .coerceIn(minVisible(barCount), maxVisible(barCount))
        // 当前绘图区左端 = (barCount - 1 + O) - V + 1 = barCount + O - V
        val start = barCount + current.rightOffset - current.visibleBars
        val ratio = anchorRatio.coerceIn(0f, 1f)
        val anchor = start + current.visibleBars * ratio
        val newStart = anchor - target * ratio
        // 反推 O'：新右端 newStart + target - 1 应等于 barCount - 1 + O'
        //   => O' = newStart + target - barCount
        return ChartViewport(target, newStart + target - barCount).clamp(barCount)
    }

    /**
     * 第 [index] 根在**绘图区**内的归一化横向位置（0..1）；窗口外返回 NaN。
     *
     * 用 [plotRange] 而非 [window]：右侧留白时两者起点/终点都不同，
     * 用 [window] 算会让十字光标的位置整体偏移。
     */
    fun fractionOf(index: Int, barCount: Int): Float {
        val range = plotRange(barCount)
        if (index !in range || range.last == range.first) return Float.NaN
        val span = clamp(barCount).visibleBars - 1f
        if (span <= 0f) return Float.NaN
        return (index - plotStart(barCount)) / span
    }

    /**
     * 归一化横坐标反查蜡烛下标，供十字光标取 tooltip。
     *
     * 结果**夹回 `0..barCount-1`**：右侧留白区内按下时几何上落在最后一根之后，
     * 那个位置没有数据，返回末根是最贴近的语义（而不是返回一个会越界的下标）。
     */
    fun indexAt(fraction: Float, barCount: Int): Int {
        val range = plotRange(barCount)
        if (range.first > range.last || barCount <= 0) return -1
        val span = clamp(barCount).visibleBars - 1f
        if (span <= 0f) return range.first.coerceIn(0, barCount - 1)
        val raw = plotStart(barCount) + fraction.coerceIn(0f, 1f) * span
        return raw.roundToInt().coerceIn(0, barCount - 1)
    }

    companion object {
        const val MIN_BARS = 15
        const val MAX_BARS = 600

        /**
         * 默认可见根数。
         *
         * 取 60 而不是更宽：绘图区宽度约 906px（1080 屏减去 58dp 的价格刻度区），
         * 120 根摊下来每根只有 7.5px、蜡烛实体约 5px，一眼看去整张图又密又小。
         * 60 根每根约 15px，实体宽约 10px，形态看得清，一屏也仍够看出趋势。
         */
        const val DEFAULT_VISIBLE = 60f

        /**
         * 平移/缩放后，可视区里至少要留给「真实数据」的比例（两个方向共用）。
         * 横向用它算右侧留白上限，纵向用它算 `ValueRange.panLimit`。
         *
         * 取 0.25：允许把最新一根推到屏幕中间偏左（留出右侧空白看形态），
         * 但永远拖不成一整屏空白。
         */
        const val MIN_VISIBLE_SHARE = 0.25f

        /**
         * 初始视窗在最新一根右侧留出的空白根数。
         *
         * 留 3 根：最新一根贴着右边缘时右边没有任何参照，正在走的那根蜡烛
         * 等于压在边框上，看不到它后续还能往哪走；留一点空白既让最新一根
         * 离开边框，也和真实交易软件的默认观感一致。
         *
         * 这只是**初始值**，用户随时可以左右拖动改掉（贴边就是 0）。
         */
        const val DEFAULT_RIGHT_BLANK = 3f

        /**
         * 初始视窗：最新一根右侧留 [DEFAULT_RIGHT_BLANK] 根空白、默认宽度。
         * **与序列长度无关** —— 宽度会在 `clamp(barCount)` 里按实际根数收窄，
         * 因此拿到短序列也安全。
         */
        fun initial(): ChartViewport = ChartViewport(DEFAULT_VISIBLE, DEFAULT_RIGHT_BLANK)
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

    /** 中位值，纵向缩放以此为不动点（否则一缩放图就整体跑掉）。 */
    val center: Double get() = (low + high) / 2.0

    /**
     * 纵向缩放：以 [center] 为不动点，把量程按 [factor] 拉伸/压缩。
     *
     * - `factor > 1` = 量程变大（价格被压扁，看全局形态）；
     * - `factor < 1` = 量程变小（价格被放大，看细节波动）。
     *
     * 量程下限钳到原量程的 [MIN_SPAN_RATIO]，避免一路捏到 0 之后
     * `toFraction` 的除法退化成「所有 K 线挤在同一条线上」。
     * 上限钳到 [MAX_SPAN_RATIO]，避免把 K 线缩成一个点。
     *
     * @param base 未缩放前的原始量程，用来算钳制边界（不要传缩放后的自己）
     */
    fun scaled(factor: Float, base: ValueRange): ValueRange {
        val baseSpan = base.high - base.low
        if (baseSpan <= EPSILON || factor <= 0f || !factor.isFinite()) return this
        val target = (high - low) * factor
        val minSpan = baseSpan * MIN_SPAN_RATIO
        val maxSpan = baseSpan * MAX_SPAN_RATIO
        val span = target.coerceIn(minSpan, maxSpan)
        val c = center
        return ValueRange(c - span / 2.0, c + span / 2.0)
    }

    /**
     * 纵向平移：整段量程上下移动（手指下滑 = 看更低价区）。
     *
     * 位移量按 [base]（**未缩放**的原始量程）计算，而不是当前量程 ——
     * 否则放大 10 倍后拖同样的手指距离，图会飞出去 10 倍远。
     *
     * @param deltaFraction 手指位移占主图高度的比例
     */
    fun panned(deltaFraction: Float, base: ValueRange = this): ValueRange {
        val baseSpan = base.high - base.low
        if (baseSpan <= EPSILON || deltaFraction == 0f) return this
        val shift = baseSpan * deltaFraction
        return ValueRange(low + shift, high + shift)
    }

    /**
     * 允许的纵向平移比例区间（相对 [base] 的跨度），闭区间。
     *
     * 与 [clampedTo] 是同一条规则的两种用法：
     * - 绘制时用 [clampedTo] 兜底（防止任何路径漏钳）；
     * - 手势时用本函数钳**累积量本身**。
     *
     * 后者不可省：若只钳渲染结果，`pricePan` 会一路累加到很离谱的值，
     * 而画面早就不动了 —— 此时反向拖动要先把这段「空行程」走完才见效，手感像坏了。
     */
    fun panLimit(base: ValueRange, currentSpan: Double): ClosedFloatingPointRange<Float> {
        val baseSpan = base.high - base.low
        if (baseSpan <= EPSILON || currentSpan <= EPSILON) return 0f..0f
        val minOverlap = min(currentSpan, baseSpan) * MIN_VISIBLE_SHARE.toDouble()
        if (minOverlap >= baseSpan) return 0f..0f
        // 位移 s 后：可视区 [base.low + s, base.high + s]（以 base 为参照的对称展开）
        // 需要与 [base.low, base.high] 重叠 >= minOverlap。
        // 上移（s < 0）时可视底边 = base.low + s，要求 <= base.high - minOverlap
        //   => s <= baseSpan - minOverlap
        // 下移（s > 0）时可视顶边 = base.high + s，要求 >= base.low + minOverlap
        //   => s >= -(baseSpan - minOverlap)
        val limit = ((baseSpan - minOverlap) / baseSpan).toFloat()
        return -limit..limit
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

        /** 纵向缩放的量程下/上限（相对原始量程），防止捏到退化成一条线或一个点。 */
        const val MIN_SPAN_RATIO = 0.08
        const val MAX_SPAN_RATIO = 12.0

        /** 纵向平移后可视区与数据区至少要保留的重叠比例。全图表共用，见 [ChartViewport.MIN_VISIBLE_SHARE]。 */
        const val MIN_VISIBLE_SHARE = ChartViewport.MIN_VISIBLE_SHARE

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
