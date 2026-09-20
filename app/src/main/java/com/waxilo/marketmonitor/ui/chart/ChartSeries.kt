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

/** 线条配色只给语义角色，具体颜色由主题在绘制层解析，避免图表模型绑死配色。 */
enum class LineRole { PRIMARY, SECONDARY, TERTIARY, ACCENT, UP, DOWN }

/** 图上的一条折线（均线族、BOLL 上下轨、MACD 的 DIF/DEA…）。 */
data class ChartLine(
    val label: String,
    val role: LineRole,
    val values: DoubleArray,
)

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
    val legendAt: (Int) -> String = { "" },
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
}

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
            // 三条都要加：只加上下轨的话中轨（SMA）画不出来
            lines + listOf(
                ChartLine("BOLL.M", LineRole.ACCENT, boll.middle),
                ChartLine("BOLL.U", LineRole.ACCENT, boll.upper),
                ChartLine("BOLL.L", LineRole.ACCENT, boll.lower),
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
                legendAt = { i -> "量 ${PriceFormatter.formatQuantity(candles.getOrNull(i)?.volume)}" },
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
                legendAt = { i ->
                    "DIF ${num(macd.dif.getOrNull(i))} DEA ${num(macd.signal.getOrNull(i))}"
                },
            )
        }

        SubPaneKind.RSI -> {
            val rsi = Indicators.rsi(closes, rsiPeriod)
            SubPaneData(
                title = "RSI($rsiPeriod)",
                lines = listOf(ChartLine("RSI", LineRole.PRIMARY, rsi)),
                fixedRange = ValueRange(0.0, 100.0),
                legendAt = { i -> "RSI ${num(rsi.getOrNull(i))}" },
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
                legendAt = { i ->
                    "K ${num(kdj.k.getOrNull(i))} D ${num(kdj.d.getOrNull(i))} J ${num(kdj.j.getOrNull(i))}"
                },
            )
        }
    }

    /** 均线颜色按周期长短稳定分配，MA5 与 MA60 永远不同色。 */
    private fun roleFor(period: Int): LineRole = when (period) {
        5 -> LineRole.PRIMARY
        10 -> LineRole.SECONDARY
        20, 30 -> LineRole.TERTIARY
        else -> LineRole.ACCENT
    }

    private fun num(value: Double?): String =
        if (value == null || value.isNaN()) "--" else String.format(Locale.US, "%.4f", value)

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
