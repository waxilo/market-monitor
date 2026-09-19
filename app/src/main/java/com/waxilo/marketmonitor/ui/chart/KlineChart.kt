package com.waxilo.marketmonitor.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.closeDouble
import com.waxilo.marketmonitor.domain.model.highDouble
import com.waxilo.marketmonitor.domain.model.lowDouble
import com.waxilo.marketmonitor.domain.model.openDouble
import com.waxilo.marketmonitor.ui.theme.DownRed
import com.waxilo.marketmonitor.ui.theme.UpGreen
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自研 K 线画布（PRD FR-2.1，已定不引入第三方图表库）。
 *
 * 分工：[ChartViewport] 决定「看哪几根」，[ChartSeries] 决定「画什么」，这里只做像素换算。
 * 缩放与平移不触发指标重算，因此手势期间只有 draw 与少量 Text 重排。
 */
@Composable
fun KlineChart(
    series: ChartSeries,
    interval: CandleInterval,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
    upColor: Color = UpGreen,
    downColor: Color = DownRed,
    onLoadMore: () -> Unit = {},
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember { mutableStateOf(ChartViewport.initial(series.size)) }
    var crosshair by remember { mutableStateOf<Crosshair?>(null) }
    var oldestRequested by remember { mutableLongStateOf(-1L) }

    val geo = remember(canvasSize, density, series.subPane) {
        ChartGeo.of(canvasSize, density, series.subPane != null)
    }
    val window = remember(viewport, series.size) { viewport.window() }
    val visibleBars = remember(viewport) { viewport.clamp().visibleBars }
    val mainRange = remember(series, window.first, window.last) {
        series.mainRange(window.first, window.last)
    }
    val subRange = remember(series, window.first, window.last) {
        series.subRange(window.first, window.last)
    }

    // 序列变长（新蜡烛、翻页）时右端视感保持不变
    LaunchedEffect(series.size) {
        if (series.size > 0) viewport = viewport.resize(series.size)
    }
    // 换周期等于换序列，视窗回到默认宽度，否则自定义周期里会带着上一周期的缩放比例
    LaunchedEffect(interval.storageKey) {
        viewport = ChartViewport.initial(series.size)
        crosshair = null
    }

    val palette = remember(
        upColor,
        downColor,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        ChartPalette(
            up = upColor,
            down = downColor,
            grid = MaterialTheme.colorScheme.outlineVariant,
            label = MaterialTheme.colorScheme.onSurfaceVariant,
            lines = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.error,
            ),
            band = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            crosshair = MaterialTheme.colorScheme.onSurface,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(geo.plotWidthPx, series.size, visibleBars) {
                    detectChartGestures(
                        longPressMs = viewConfiguration.longPressTimeoutMillis,
                        touchSlop = viewConfiguration.touchSlop,
                        plotWidth = geo.plotWidthPx,
                        onPan = { deltaPx ->
                            val slot = geo.slot(viewport.clamp().visibleBars)
                            val moved = viewport.pan(if (slot > 0f) deltaPx / slot else 0f)
                            viewport = moved
                            // 拖到最左端还继续往右拖 = 要看更早的历史（PRD FR-2.2）
                            if (deltaPx > 0f && moved.startIndex() == 0) {
                                val oldest = series.candles.firstOrNull()?.openTime ?: 0L
                                if (oldest != oldestRequested) {
                                    oldestRequested = oldest
                                    onLoadMore()
                                }
                            }
                        },
                        onZoom = { barFactor, anchorX ->
                            viewport = viewport.zoom(barFactor, anchorX / geo.plotWidthPx)
                        },
                        onCrosshair = { position ->
                            crosshair = if (position == null) null else {
                                val index = viewport.indexAt(position.x / geo.plotWidthPx)
                                if (index >= 0) Crosshair(index, position.y) else crosshair
                            }
                        },
                    )
                },
        ) {
            if (series.size == 0 || geo.plotWidthPx <= 1f) return@Canvas
            drawGridAndAxes(geo, palette, mainRange, subRange)
            drawCandles(series, window, visibleBars, geo, palette, mainRange)
            drawOverlays(series, window, visibleBars, geo, palette, mainRange)
            drawLastPrice(series, geo, palette, mainRange)
            series.subPane?.let { drawSubPane(series, it, window, visibleBars, geo, palette, subRange) }
            drawCrosshair(crosshair, window, visibleBars, geo, palette, series, mainRange, subRange)
        }

        PriceAxisLabels(mainRange, geo, density, tickSize, Modifier.align(Alignment.TopStart))
        TimeAxisLabels(series, window, visibleBars, geo, interval, density, Modifier.align(Alignment.TopStart))
        ChartLegend(
            tooltip = remember(crosshair, series.size, interval, tickSize) {
                ChartModel.tooltip(
                    series.candles,
                    crosshair?.index ?: (series.size - 1),
                    interval,
                    tickSize,
                )
            },
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
    }
}

/** 十字光标：竖线锁定到某根蜡烛，横线跟手指。 */
data class Crosshair(val index: Int, val yPx: Float)

private data class ChartPalette(
    val up: Color,
    val down: Color,
    val grid: Color,
    val label: Color,
    val lines: List<Color>,
    val band: Color,
    val crosshair: Color,
) {
    fun roleColor(role: LineRole): Color = when (role) {
        LineRole.PRIMARY -> lines.getOrElse(0) { grid }
        LineRole.SECONDARY -> lines.getOrElse(1) { grid }
        LineRole.TERTIARY -> lines.getOrElse(2) { grid }
        LineRole.ACCENT -> lines.getOrElse(3) { grid }
        LineRole.UP -> up
        LineRole.DOWN -> down
    }
}

/**
 * 像素布局：价格刻度固定右侧、时间刻度固定底部，其余给蜡烛。
 * 有副图时主图占 [MAIN_SHARE]，两图共用横轴，因此左右边界天然对齐。
 */
private data class ChartGeo(
    val plotWidthPx: Float,
    val plotHeightPx: Float,
    val labelWidthPx: Float,
    val timeAxisHeightPx: Float,
    val mainHeightPx: Float,
    val subTopPx: Float,
    val subHeightPx: Float,
) {
    fun slot(visibleBars: Int): Float = plotWidthPx / max(1, visibleBars)

    /** 一根蜡烛的横向中心；窗口起点左侧的蜡烛会落在画布外，由裁剪处理。 */
    fun xOf(index: Int, start: Int, visibleBars: Int): Float =
        (index - start + 0.5f) * slot(visibleBars)

    fun yOf(fraction: Float, top: Float, height: Float): Float = top + fraction * height

    fun bodyWidth(visibleBars: Int): Float = (slot(visibleBars) * BODY_SHARE).coerceIn(1f, 26f)

    companion object {
        const val MAIN_SHARE = 0.72f
        const val BODY_SHARE = 0.66f
        const val AXIS_LABEL_WIDTH_DP = 58f
        const val TIME_AXIS_HEIGHT_DP = 18f

        fun of(size: IntSize, density: Density, hasSubPane: Boolean): ChartGeo = with(density) {
            val label = AXIS_LABEL_WIDTH_DP.dp.toPx()
            val timeAxis = TIME_AXIS_HEIGHT_DP.dp.toPx()
            val plotWidth = max(1f, size.width.toFloat() - label)
            val plotHeight = max(1f, size.height.toFloat() - timeAxis)
            val main = if (hasSubPane) plotHeight * MAIN_SHARE else plotHeight
            ChartGeo(
                plotWidthPx = plotWidth,
                plotHeightPx = plotHeight,
                labelWidthPx = label,
                timeAxisHeightPx = timeAxis,
                mainHeightPx = main,
                subTopPx = main,
                subHeightPx = if (hasSubPane) plotHeight - main else 0f,
            )
        }
    }
}

private fun DrawScope.drawGridAndAxes(
    geo: ChartGeo,
    palette: ChartPalette,
    mainRange: ValueRange,
    subRange: ValueRange,
) {
    mainRange.gridLines().forEach { value ->
        val y = geo.yOf(mainRange.toFraction(value), 0f, geo.mainHeightPx)
        if (y in 0f..geo.mainHeightPx) {
            drawLine(palette.grid, Offset(0f, y), Offset(geo.plotWidthPx, y), 1f)
        }
    }
    if (geo.subHeightPx > 1f) {
        drawLine(palette.grid, Offset(0f, geo.subTopPx), Offset(geo.plotWidthPx, geo.subTopPx), 1f)
        if (subRange.low < 0 && subRange.high > 0) {
            val zero = geo.yOf(subRange.toFraction(0.0), geo.subTopPx, geo.subHeightPx)
            drawLine(palette.grid, Offset(0f, zero), Offset(geo.plotWidthPx, zero), 1f)
        }
    }
}

private fun DrawScope.drawCandles(
    series: ChartSeries,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    val slot = geo.slot(visibleBars)
    val bodyWidth = geo.bodyWidth(visibleBars)
    val half = bodyWidth / 2f
    for (i in window) {
        val candle = series.candles[i]
        val color = if (candle.close >= candle.open) palette.up else palette.down
        val x = geo.xOf(i, window.first, visibleBars)
        if (x < 0f || x > geo.plotWidthPx) continue
        val highY = geo.yOf(range.toFraction(candle.highDouble()), 0f, geo.mainHeightPx)
        val lowY = geo.yOf(range.toFraction(candle.lowDouble()), 0f, geo.mainHeightPx)
        if (slot < 1.5f) {
            // 密度太高时实体只会糊成一片，退化成一根影线
            drawLine(color, Offset(x, min(highY, lowY)), Offset(x, max(highY, lowY)), 1f)
            continue
        }
        drawLine(color, Offset(x, highY), Offset(x, lowY), 1f)
        val openY = geo.yOf(range.toFraction(candle.openDouble()), 0f, geo.mainHeightPx)
        val closeY = geo.yOf(range.toFraction(candle.closeDouble()), 0f, geo.mainHeightPx)
        val top = min(openY, closeY)
        drawRect(
            color = color,
            topLeft = Offset(x - half, top),
            size = androidx.compose.ui.geometry.Size(bodyWidth, max(1f, abs(closeY - openY))),
        )
    }
}

private fun DrawScope.drawOverlays(
    series: ChartSeries,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    series.overlay.bandFill?.let { (upper, lower) ->
        bandPath(upper, lower, window, visibleBars, geo, range)?.let { drawPath(it, palette.band) }
    }
    series.overlay.lines.forEach { line ->
        val path = linePath(line.values, window, visibleBars, geo, range, geo.mainHeightPx, 0f)
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 1.5f))
    }
}

private fun DrawScope.drawLastPrice(
    series: ChartSeries,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    val last: Kline = series.candles.lastOrNull() ?: return
    val y = geo.yOf(range.toFraction(last.closeDouble()), 0f, geo.mainHeightPx)
    if (y !in 0f..geo.mainHeightPx) return
    drawLine(
        color = if (last.close >= last.open) palette.up else palette.down,
        p1 = Offset(0f, y),
        p2 = Offset(geo.plotWidthPx, y),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
    )
}

private fun DrawScope.drawSubPane(
    series: ChartSeries,
    pane: SubPaneData,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    pane.bars?.let { bars ->
        val width = geo.bodyWidth(visibleBars)
        val base = geo.yOf(range.toFraction(0.0.coerceIn(range.low, range.high)), geo.subTopPx, geo.subHeightPx)
        for (i in window) {
            val value = bars.getOrNull(i) ?: continue
            if (value.isNaN()) continue
            val y = geo.yOf(range.toFraction(value), geo.subTopPx, geo.subHeightPx)
            val candle = series.candles.getOrNull(i)
            val color = if (candle == null || candle.close >= candle.open) palette.up else palette.down
            drawRect(
                color = color.copy(alpha = 0.8f),
                topLeft = Offset(geo.xOf(i, window.first, visibleBars) - width / 2f, min(y, base)),
                size = androidx.compose.ui.geometry.Size(width, max(1f, abs(base - y))),
            )
        }
    }
    pane.lines.forEach { line ->
        val path = linePath(line.values, window, visibleBars, geo, range, geo.subHeightPx, geo.subTopPx)
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 1.2f))
    }
}

private fun DrawScope.drawCrosshair(
    crosshair: Crosshair?,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    palette: ChartPalette,
    series: ChartSeries,
    mainRange: ValueRange,
    subRange: ValueRange,
) {
    val mark = crosshair ?: return
    if (mark.index !in window) return
    val x = geo.xOf(mark.index, window.first, visibleBars)
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    drawLine(
        palette.crosshair.copy(alpha = 0.7f),
        Offset(x, 0f),
        Offset(x, geo.plotHeightPx),
        1.5f,
        pathEffect = dash,
    )
    val y = mark.yPx.coerceIn(0f, geo.plotHeightPx)
    drawLine(
        palette.crosshair.copy(alpha = 0.7f),
        Offset(0f, y),
        Offset(geo.plotWidthPx, y),
        1.5f,
        pathEffect = dash,
    )
}

private fun linePath(
    values: DoubleArray,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    range: ValueRange,
    height: Float,
    top: Float,
): Path? {
    var path: Path? = null
    for (i in window) {
        val value = values.getOrNull(i) ?: continue
        if (value.isNaN()) continue
        val x = geo.xOf(i, window.first, visibleBars)
        val y = geo.yOf(range.toFraction(value), top, height)
        val current = path
        if (current == null) path = Path().apply { moveTo(x, y) } else current.lineTo(x, y)
    }
    return path
}

private fun bandPath(
    upper: DoubleArray,
    lower: DoubleArray,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    range: ValueRange,
): Path? {
    val points = window.filter {
        !upper.getOrElse(it) { Double.NaN }.isNaN() && !lower.getOrElse(it) { Double.NaN }.isNaN()
    }
    if (points.size < 2) return null
    return Path().apply {
        points.forEachIndexed { order, i ->
            val x = geo.xOf(i, window.first, visibleBars)
            val y = geo.yOf(range.toFraction(upper[i]), 0f, geo.mainHeightPx)
            if (order == 0) moveTo(x, y) else lineTo(x, y)
        }
        points.asReversed().forEach { i ->
            lineTo(
                geo.xOf(i, window.first, visibleBars),
                geo.yOf(range.toFraction(lower[i]), 0f, geo.mainHeightPx),
            )
        }
        close()
    }
}

@Composable
private fun PriceAxisLabels(
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    val decimals = PriceFormatter.decimalsFor(tickSize)
    range.gridLines().forEach { value ->
        val y = geo.yOf(range.toFraction(value), 0f, geo.mainHeightPx)
        if (y !in 0f..geo.plotHeightPx) return@forEach
        Text(
            text = PriceFormatter.localeNumber(value, decimals),
            modifier = modifier.offset(x = with(density) { geo.plotWidthPx.toDp() }, y = with(density) { (y - 6f).toDp() }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimeAxisLabels(
    series: ChartSeries,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    interval: CandleInterval,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val count = window.count()
    if (count <= 1 || geo.plotWidthPx <= 1f) return
    val step = max(1, count / MAX_TIME_LABELS)
    var index = window.first
    while (index <= window.last) {
        val candle = series.candles.getOrNull(index) ?: break
        val x = geo.xOf(index, window.first, visibleBars)
        if (x > geo.plotWidthPx) break
        Text(
            text = ChartModel.formatTime(candle.openTime, interval.minutes),
            modifier = modifier.offset(
                x = with(density) { x.toDp() },
                y = with(density) { (geo.plotHeightPx + 2f).toDp() },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        index += step
    }
}

@Composable
private fun ChartLegend(tooltip: CandleTooltip?, modifier: Modifier = Modifier) {
    if (tooltip == null) return
    Column(modifier = modifier) {
        Text(
            text = tooltip.time + "  " + tooltip.changeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "开 ${tooltip.open} 高 ${tooltip.high} 低 ${tooltip.low} 收 ${tooltip.close}",
            style = MaterialTheme.typography.labelSmall,
            color = if (tooltip.up) UpGreen else DownRed,
        )
        Text(
            text = "量 ${tooltip.volume}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 单指平移、双指缩放、长按出十字光标，全在一个手势循环里判定。
 * 不用 detectTransformGestures：它与自定义长按分属两个 pointerInput 链节点，
 * 抢占事件时行为依赖链顺序，缩放手势一旦改版就会被静默打断。
 */
private suspend fun PointerInputScope.detectChartGestures(
    longPressMs: Long,
    touchSlop: Float,
    plotWidth: Float,
    onPan: (deltaPx: Float) -> Unit,
    onZoom: (barFactor: Float, anchorX: Float) -> Unit,
    onCrosshair: (Offset?) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        var travelled = 0f
        var previousDistance = 0f
        var longPressActive = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (!longPressActive && pressed.size == 1) {
                val held = pressed[0].uptimeMillis - first.uptimeMillis
                if (held >= longPressMs && travelled <= touchSlop) longPressActive = true
            }
            if (longPressActive) {
                pressed.forEach { it.consume() }
                onCrosshair(pressed[0].position)
                continue
            }
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                val anchorX = (pressed[0].position.x + pressed[1].position.x) / 2f
                if (previousDistance > 0f && distance > 0f) {
                    // 双指张开时距离变大，可见根数应变少，因此因子取倒数比
                    onZoom((previousDistance / distance).coerceIn(0.5f, 2f), anchorX.coerceIn(0f, plotWidth))
                }
                previousDistance = distance
                pressed.forEach { it.consume() }
                continue
            }
            val change: PointerInputChange = pressed[0]
            if (change.positionChanged()) {
                val delta = change.positionChange()
                travelled += delta.getDistance()
                if (abs(delta.x) > 0.1f) {
                    change.consume()
                    onPan(delta.x)
                }
                previousDistance = 0f
            }
        }
        onCrosshair(null)
    }
}

private const val MAX_TIME_LABELS = 4
