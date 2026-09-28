package com.waxilo.marketmonitor.ui.chart

import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.indicator.Indicators
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.closeDouble
import com.waxilo.marketmonitor.domain.model.highDouble
import com.waxilo.marketmonitor.domain.model.lowDouble
import com.waxilo.marketmonitor.domain.model.volumeDouble
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线条配色只给语义角色，具体颜色由主题在绘制层解析，避免图表模型绑死配色。
 * PRIMARY~OCTONARY 是八个互不相同的指标线色位（[com.waxilo.marketmonitor.ui.theme.ChartLineColors]），
 * 刚好覆盖最满配置：MA 五期 + BOLL 三线。
 * [LABEL] 不是一条线，只给读数行里那些中性文字（指标名、参数）用。
 */
enum class LineRole {
    PRIMARY, SECONDARY, TERTIARY, ACCENT, QUATERNARY, QUINARY, SENARY, OCTONARY,
    UP, DOWN, LABEL,
}

/** 图上的一条折线（均线族、BOLL 上下轨、MACD 的 DIF/DEA…）。 */
data class ChartLine(
    val label: String,
    val role: LineRole,
    val values: DoubleArray,
)

/**
 * 读数行里的一个片段：文字 + 配色角色。
 * 与折线共用 [LineRole]，于是「这个数字属于哪条线」由颜色直接说清，
 * 不必再为每条线单独钉一行图例（那正是旧版左上角那一大块 OHLC 的来历）。
 */
data class ReadoutSegment(val text: String, val role: LineRole)

/** 主图叠加：收盘价衍生出的均线与布林带。 */
data class OverlayData(
    val lines: List<ChartLine>,
    val bandFill: Pair<DoubleArray, DoubleArray>?,
)

/** 一个副图窗格。[bars] 非空时按柱状绘制（VOL、MACD 柱），否则画折线。 */
data class SubPaneData(
    val title: String,
    val lines: List<ChartLine>,
    val bars: DoubleArray? = null,
    /** 柱状指标（VOL）从 0 起画，否则每根柱子的底边会跟着窗口最小值跳动。 */
    val fromZero: Boolean = false,
    val fixedRange: ValueRange? = null,
    val decimals: Int = PriceFormatter.DEFAULT_DECIMALS,
    /**
     * 这块副图自己的读数行（币安式：参数名 + 各线数值，各自配色）。
     * 入参是「蜡烛下标」与「价格小数位」：MACD 与价格同量级所以跟着价格走，
     * RSI / KDJ 有界在 0~100，固定两位更稳。
     */
    val readoutAt: (Int, Int) -> List<ReadoutSegment> = { _, _ -> emptyList() },
)

/**
 * 可选副图。**不含「不显示」**——空集本身就是「不显示」，
 * 用枚举值表达「无」会和「支持多选」互相打架（多选时空集才是唯一的"无"）。
 */
enum class SubPaneKind(val label: String) {
    VOLUME("VOL"),
    MACD("MACD"),
    RSI("RSI"),
    KDJ("KDJ"),
    ;
}

/**
 * 图表序列（PRD FR-2.2：指标一律由本地按原始蜡烛计算）。
 * 构建一次可能涉及上千次浮点运算，因此只在「数据或参数变化」时重建，
 * 缩放与平移不触发重算——那只是换 [ChartViewport.window] 的取数区间。
 */
data class ChartSeries(
    val candles: List<Kline>,
    val closes: DoubleArray,
    val overlay: OverlayData,
    /** 副图列表：空列表表示不显示副图，多元素表示多副图纵向堆叠。 */
    val subPanes: List<SubPaneData>,
) {
    val size: Int get() = candles.size

    /** 主图纵向范围：影线 + 可见的叠加线，否则均线会顶穿画布。 */
    fun mainRange(start: Int, end: Int): ValueRange {
        val lows = DoubleArray(size) { candles[it].lowDouble() }
        val highs = DoubleArray(size) { candles[it].highDouble() }
        val base = listOfNotNull(
            ValueRange.of(lows, start, end),
            ValueRange.of(highs, start, end),
        ).let { ranges ->
            if (ranges.isEmpty()) null else ValueRange(
                ranges.minOf { it.low },
                ranges.maxOf { it.high },
            )
        } ?: return ValueRange(0.0, 1.0)
        val withOverlays = overlay.lines.mapNotNull { ValueRange.of(it.values, start, end) }
        val all = listOf(base) + withOverlays
        return ValueRange(all.minOf { it.low }, all.maxOf { it.high }).padded()
    }

    /**
     * 单块副图的纵向范围：固定刻度（RSI / KDJ）优先，其余按可见窗口取值。
     * 每块副图各算各的，互不影响——多选时 MACD 与 VOL 的量级差好几个数量级。
     */
    fun subRange(pane: SubPaneData, start: Int, end: Int): ValueRange {
        pane.fixedRange?.let { return it }
        val fromBars = pane.bars?.let { ValueRange.of(it, start, end) }
        val fromLines = pane.lines.mapNotNull { ValueRange.of(it.values, start, end) }
        val all = listOfNotNull(fromBars) + fromLines
        if (all.isEmpty()) return ValueRange(0.0, 1.0)
        val low = if (pane.fromZero) 0.0 else all.minOf { it.low }
        val high = all.maxOf { it.high }
        if (low >= high) return ValueRange(low, high + 1.0)
        return ValueRange(low, high).padded(if (pane.fromZero) 0.0 else 0.08)
    }

    /**
     * 主图指标读数（币安式那一行彩色参数）。[index] 取十字光标所在根；
     * 没有长按时调用方传末根，于是这行平时读的就是最新一根的数值。
     */
    fun mainReadoutAt(index: Int, priceDecimals: Int): List<ReadoutSegment> =
        overlay.lines.map { line ->
            ReadoutSegment(
                "${line.label}: ${readoutNumber(line.values.getOrNull(index), priceDecimals)}",
                line.role,
            )
        }

    /**
     * 单块副图的读数行。[index] 由调用方保证落在序列内（十字光标的下标已由视窗夹好）。
     */
    fun subReadoutAt(pane: SubPaneData, index: Int, priceDecimals: Int): List<ReadoutSegment> =
        pane.readoutAt(index, priceDecimals)
}

/** 读数用的数字：千分位分组，缺值与 NaN 走统一占位而不是 0。 */
private fun readoutNumber(value: Double?, decimals: Int): String =
    if (value == null || value.isNaN()) PriceFormatter.NO_DATA
    else String.format(Locale.US, "%,.${decimals}f", value)

/**
 * 十字光标 tooltip（PRD FR-2.1：OHLC + 时间 + 涨幅）。
 * 涨幅按「与上一根收盘比」计算，这是行业惯例，也是 24h 涨幅之外的第二套口径，
 * 因此文案里显式写「较上根」。
 */
data class CandleTooltip(
    val time: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val changeText: String,
    val volume: String,
    val up: Boolean,
)

object ChartModel {

    fun build(
        candles: List<Kline>,
        maPeriods: List<Int>,
        showBoll: Boolean,
        subPanes: List<SubPaneKind>,
        bollPeriod: Int = 20,
        macdParams: Triple<Int, Int, Int> = Triple(12, 26, 9),
        rsiPeriod: Int = 14,
    ): ChartSeries {
        val closes = DoubleArray(candles.size) { candles[it].closeDouble() }
        val lines = Indicators.movingAverages(closes, maPeriods.sorted()).entries.sortedBy { it.key }.map { (period, values) ->
            ChartLine("MA$period", roleFor(period), values)
        }
        val boll = if (showBoll) Indicators.boll(closes, bollPeriod) else null
        val overlayLines = if (boll == null) {
            lines
        } else {
            // 三条都要加：只加上下轨的话中轨（SMA）画不出来；
            // 三线各占一个色位，彼此与均线族都不同色
            lines + listOf(
                ChartLine("BOLL.MB", LineRole.QUINARY, boll.middle),
                ChartLine("BOLL.UP", LineRole.SENARY, boll.upper),
                ChartLine("BOLL.DN", LineRole.OCTONARY, boll.lower),
            )
        }
        return ChartSeries(
            candles = candles,
            closes = closes,
            overlay = OverlayData(
                lines = overlayLines,
                bandFill = boll?.let { Pair(it.upper, it.lower) },
            ),
            // 按枚举声明顺序输出，保证「多选后副图从上到下的次序」稳定，
            // 不随用户点击先后跳来跳去。
            subPanes = SubPaneKind.entries
                .filter { it in subPanes }
                .mapNotNull { buildSubPane(candles, closes, it, macdParams, rsiPeriod) },
        )
    }

    fun tooltip(
        candles: List<Kline>,
        index: Int,
        interval: CandleInterval,
        tickSize: BigDecimal?,
    ): CandleTooltip? {
        val candle = candles.getOrNull(index) ?: return null
        val previous = candles.getOrNull(index - 1)
        val change = previous?.let { prev ->
            if (prev.close.signum() == 0) null
            else candle.close.subtract(prev.close)
                .divide(prev.close, java.math.MathContext.DECIMAL128)
                .multiply(BigDecimal.valueOf(100))
                .toDouble()
        }
        return CandleTooltip(
            time = formatTime(candle.openTime, interval.minutes),
            open = PriceFormatter.format(candle.open, tickSize),
            high = PriceFormatter.format(candle.high, tickSize),
            low = PriceFormatter.format(candle.low, tickSize),
            close = PriceFormatter.format(candle.close, tickSize),
            changeText = if (change == null) "较上根 --" else "较上根 ${PriceFormatter.formatChange(change)}",
            volume = PriceFormatter.formatCompact(candle.volume),
            up = change == null || change >= 0,
        )
    }

    private fun buildSubPane(
        candles: List<Kline>,
        closes: DoubleArray,
        kind: SubPaneKind,
        macdParams: Triple<Int, Int, Int>,
        rsiPeriod: Int,
    ): SubPaneData? = when (kind) {
        SubPaneKind.VOLUME -> {
            val volumes = DoubleArray(candles.size) { candles[it].volumeDouble() }
            SubPaneData(
                title = "VOL",
                lines = emptyList(),
                bars = volumes,
                fromZero = true,
                readoutAt = { i, _ ->
                    listOf(
                        ReadoutSegment(
                            "VOL: ${PriceFormatter.formatQuantity(candles.getOrNull(i)?.volume)}",
                            LineRole.LABEL,
                        )
                    )
                },
            )
        }

        SubPaneKind.MACD -> {
            val macd = Indicators.macd(closes, macdParams.first, macdParams.second, macdParams.third)
            SubPaneData(
                title = "MACD(${macdParams.first},${macdParams.second},${macdParams.third})",
                lines = listOf(
                    ChartLine("DIF", LineRole.PRIMARY, macd.dif),
                    ChartLine("DEA", LineRole.SECONDARY, macd.signal),
                ),
                bars = macd.histogram,
                // MACD 与价格同量级，所以小数位跟着价格走；柱值按正负取涨跌色，
                // 与画柱子用的是同一套语言。
                readoutAt = { i, decimals ->
                    val hist = macd.histogram.getOrNull(i)
                    listOf(
                        ReadoutSegment("MACD(${macdParams.first},${macdParams.second},${macdParams.third})", LineRole.LABEL),
                        ReadoutSegment("DIF: ${readoutNumber(macd.dif.getOrNull(i), decimals)}", LineRole.PRIMARY),
                        ReadoutSegment("DEA: ${readoutNumber(macd.signal.getOrNull(i), decimals)}", LineRole.SECONDARY),
                        ReadoutSegment(
                            "MACD: ${readoutNumber(hist, decimals)}",
                            if (hist != null && hist < 0) LineRole.DOWN else LineRole.UP,
                        ),
                    )
                },
            )
        }

        SubPaneKind.RSI -> {
            val rsi = Indicators.rsi(closes, rsiPeriod)
            SubPaneData(
                title = "RSI($rsiPeriod)",
                lines = listOf(ChartLine("RSI", LineRole.PRIMARY, rsi)),
                fixedRange = ValueRange(0.0, 100.0),
                readoutAt = { i, _ ->
                    listOf(
                        ReadoutSegment("RSI($rsiPeriod)", LineRole.LABEL),
                        ReadoutSegment("RSI: ${readoutNumber(rsi.getOrNull(i), 2)}", LineRole.PRIMARY),
                    )
                },
            )
        }

        SubPaneKind.KDJ -> {
            val high = DoubleArray(candles.size) { candles[it].highDouble() }
            val low = DoubleArray(candles.size) { candles[it].lowDouble() }
            val kdj = Indicators.kdj(high, low, closes)
            SubPaneData(
                title = "KDJ(9,3,3)",
                lines = listOf(
                    ChartLine("K", LineRole.PRIMARY, kdj.k),
                    ChartLine("D", LineRole.SECONDARY, kdj.d),
                    ChartLine("J", LineRole.TERTIARY, kdj.j),
                ),
                readoutAt = { i, _ ->
                    listOf(
                        ReadoutSegment("KDJ(9,3,3)", LineRole.LABEL),
                        ReadoutSegment("K: ${readoutNumber(kdj.k.getOrNull(i), 2)}", LineRole.PRIMARY),
                        ReadoutSegment("D: ${readoutNumber(kdj.d.getOrNull(i), 2)}", LineRole.SECONDARY),
                        ReadoutSegment("J: ${readoutNumber(kdj.j.getOrNull(i), 2)}", LineRole.TERTIARY),
                    )
                },
            )
        }
    }

    /** 均线颜色按周期长短稳定分配，MA5 与 MA60 永远不同色。 */
    private fun roleFor(period: Int): LineRole = when (period) {
        5 -> LineRole.PRIMARY
        10 -> LineRole.SECONDARY
        20 -> LineRole.TERTIARY
        30 -> LineRole.ACCENT
        else -> LineRole.QUATERNARY
    }

    /** 日内周期显示到时分，日线及以上显示日期，避免十字光标读出无关字段。 */
    fun formatTime(epochMs: Long, intervalMinutes: Long): String {
        val pattern = if (intervalMinutes >= 1_440) "yyyy-MM-dd" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.US).format(Date(epochMs))
    }

    /**
     * 时间轴标签的像素落点。
     *
     * 单独抽出来是因为这段纯数学必须能跑单测——放进 Composable 里就依赖
     * [androidx.compose.ui.text.TextMeasurer]，只能靠截图肉眼看，回归了也不知道。
     *
     * 规则（三条缺一不可）：
     * 1. 以蜡烛中心为锚点、按标签宽度折半得到左边缘；
     * 2. 左边缘**夹进** `[0, plotWidth - labelWidth]`，避免首尾被切掉半个字；
     * 3. 夹完之后仍要与**上一条已画标签**保持 `minGap`：
     *    间距不够就整条丢掉。少了第 3 条，被夹回来的首条标签就会和第二条糊在一起
     *    （真实踩过：`17:18` 与 `17:23` 贴成 `17:1817:23`）。
     *
     * @param centersPx 候选位置的横向中心，按 [step] 从窗口起点起逐根取
     * @param step 每画一条前进几根蜡烛；至少 1
     * @return 每个元素是一条标签的左边缘（dp 无关，纯像素），已按绘制顺序排列，
     *         同时给出它对应的候选下标，便于调用方取时间文案
     */
    fun timeLabelPlacements(
        centersPx: List<Float>,
        labelWidthPx: Float,
        plotWidthPx: Float,
        minGapPx: Float,
        step: Int,
    ): List<TimeLabelPlacement> {
        if (centersPx.isEmpty() || plotWidthPx <= 0f) return emptyList()
        val stride = maxOf(1, step)
        val rightLimit = maxOf(0f, plotWidthPx - labelWidthPx)
        val placed = ArrayList<TimeLabelPlacement>(centersPx.size / stride + 1)
        var lastLeft = Float.NEGATIVE_INFINITY
        var index = 0
        while (index < centersPx.size) {
            val left = (centersPx[index] - labelWidthPx / 2f).coerceIn(0f, rightLimit)
            if (left - lastLeft >= minGapPx) {
                lastLeft = left
                placed += TimeLabelPlacement(index = index, leftPx = left)
            }
            index += stride
        }
        return placed
    }
}

/**
 * 一条要画的时间轴标签：对应第 [index] 根候选蜡烛，左边缘落在 [leftPx]。
 */
data class TimeLabelPlacement(val index: Int, val leftPx: Float)
