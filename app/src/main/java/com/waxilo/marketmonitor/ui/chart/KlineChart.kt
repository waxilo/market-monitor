package com.waxilo.marketmonitor.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import com.waxilo.marketmonitor.ui.theme.UpGreen
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
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
    /**
     * 标的身份（如 `BTCUSDT`）。换标的时纵向缩放/平移必须复位：
     * 平移量是「按原始量程折算的比例」，BTC 上 0.2 的位移放到 ETH 上毫无意义；
     * 缩放虽是无量纲倍数，但跨标的保留也只会让人莫名其妙。
     */
    symbolKey: String = "",
    upColor: Color = UpGreen,
    downColor: Color = DownRed,
    onLoadMore: () -> Unit = {},
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    /**
     * 视窗只存「用户意图」（看多宽、右端停哪儿）；序列长度每次查询时作为入参传入。
     *
     * `remember` 的 key 就是**换序列的两件事**：换标的与换周期。key 一变就重建视窗，
     * 复位天然发生在组合期、与 `series` 同帧生效，因此不再需要
     * `LaunchedEffect(series.size)` 事后对齐、也不再需要两个复位用的 effect。
     * 副作用里的复位永远慢一帧，而这一帧就是首屏错渲染的全部窗口期。
     */
    val barCount = series.size
    var viewport by remember(symbolKey, interval.storageKey) {
        mutableStateOf(ChartViewport.initial())
    }
    var crosshair by remember(symbolKey, interval.storageKey) { mutableStateOf<Crosshair?>(null) }
    var oldestRequested by remember { mutableLongStateOf(-1L) }
    // 整个图表共用一个测量器：每个子组件各建一个没有意义，还多一份缓存
    val measurer = rememberTextMeasurer()
    /**
     * 纵向缩放倍数（相对主图原始量程）。1f = 自动量程。
     * 手指在**价格轴一侧**上下拖会改它 —— 这是「拉伸/压缩金额刻度」的手势。
     *
     * 与 [viewport] 同为「用户意图」，因此挂同一组 key：换标的/换周期一律复位。
     * 跨标的保留毫无意义（BTC 上 0.2 的位移放到 ETH 上不是同一回事），
     * 而换周期后价格量级也可能完全不同。
     */
    var priceZoom by remember(symbolKey, interval.storageKey) { mutableFloatStateOf(1f) }
    /** 纵向平移（占主图高度的比例）。手指上滑看更低价区。 */
    var pricePan by remember(symbolKey, interval.storageKey) { mutableFloatStateOf(0f) }
    /**
     * 横向平移的「不足一根」余量（单位：根）。
     *
     * [ChartViewport.rightOffset] 是整数根数，而一次手势事件往往只移动几像素
     * （除以 slot 后不足半根）。若直接把每帧的 `deltaPx / slot` 交给 `pan()`，
     * `roundToInt()` 会把每帧的零头全部抹掉 —— 表现为「左右拖完全不动」。
     * 所以把零头攒在这里，凑够一根才提交。
     *
     * 挂同一组 key：零头按「根数」计，换周期后每根的像素宽度变了，旧零头已无意义。
     */
    var barPanRemainder by remember(symbolKey, interval.storageKey) { mutableFloatStateOf(0f) }

    /**
     * 双指捏合的对数余量。
     *
     * 与 [barPanRemainder] 同一个道理，但作用在**乘性**的缩放上：
     * `ChartViewport.zoom()` 最后要把可见根数 `roundToInt()`，
     * 可见 120 根时缓慢张开一帧只让因子到 0.997，`120 * 0.997 ≈ 119.6`
     * 取整又回到 120 —— 每帧都被抹平，手指慢慢捏就完全没反应。
     * 这里把 `ln(factor)` 攒起来，凑够「一根可见变化」的对数当量再一次性提交。
     */
    var pinchRemainder by remember(symbolKey, interval.storageKey) { mutableFloatStateOf(0f) }

    val geo = remember(canvasSize, density, series.subPanes.size) {
        ChartGeo.of(canvasSize, density, series.subPanes.size, ChartGeo.LEGEND_HEIGHT_DP)
    }
    /**
     * 「有数据 + 有尺寸」才允许绘制。
     *
     * `ChartGeo.of` 在尺寸还是 [IntSize.Zero] 时会把宽度兜底成 1f（为了让下游除法不炸），
     * 于是所有以 `plotWidthPx <= 1f` 当哨兵值的判断都依赖这个魔数。
     * 这里显式算一次，绘制与轴标签共用，避免各写各的判据时漏掉某一个。
     */
    val isReady = barCount > 0 && geo.isUsable
    /**
     * 有数据的区间（**夹在 `0..barCount-1` 内**），只用于「算量程」——
     * 右侧留白那段没有数据，参与算量程会把价格区间拉歪。
     */
    val dataWindow = remember(viewport, barCount) { viewport.window(barCount) }
    /**
     * 绘图区区间（**含右侧留白，可能越出序列末尾**），用于所有横向定位。
     * 与 [dataWindow] 分开是因为两者在「最新 K 线左移留白」时起点不同：
     * 用错会让整排蜡烛横向错位。
     */
    val plotRange = remember(viewport, barCount) { viewport.plotRange(barCount) }
    val visibleBars = remember(viewport, barCount) { viewport.clamp(barCount).visibleBars }
    /** 主图自动量程（未叠加用户纵向缩放）。 */
    val autoRange = remember(series, dataWindow.first, dataWindow.last) {
        series.mainRange(dataWindow.first, dataWindow.last)
    }
    val mainRange = remember(autoRange, priceZoom, pricePan) {
        autoRange
            .scaled(priceZoom, autoRange)
            .panned(pricePan, autoRange)
    }
    val subRanges = remember(series, dataWindow.first, dataWindow.last) {
        series.subPanes.map { series.subRange(it, dataWindow.first, dataWindow.last) }
    }

    // 每帧重建 7 个 Color 引用代价极低，反而省掉一长串 remember key——key 里不能放 MaterialTheme 调用
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette(
        up = upColor,
        down = downColor,
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        lines = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error),
        band = scheme.primary.copy(alpha = 0.08f),
        crosshair = scheme.onSurface,
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(geo.plotWidthPx, geo.mainHeightPx, barCount, visibleBars) {
                    detectChartGestures(
                        longPressMs = viewConfiguration.longPressTimeoutMillis,
                        touchSlop = viewConfiguration.touchSlop,
                        plotWidth = geo.plotWidthPx,
                        plotHeight = geo.mainHeightPx,
                        onGestureStart = {
                            barPanRemainder = 0f
                            pinchRemainder = 0f
                        },
                        onPan = { deltaPx ->
                            val slot = geo.slot(viewport.clamp(barCount).visibleBars)
                            // 换算成「整根 + 余量」：单帧位移通常不足一根，
                            // 逐帧取整会把零头全抹掉（历史上表现为横向完全拖不动）。
                            val step = ChartGesture.accumulateBarPan(barPanRemainder, deltaPx, slot)
                            barPanRemainder = step.remainder
                            if (step.bars != 0) {
                                val moved = viewport.pan(step.bars.toFloat(), barCount)
                                viewport = moved
                                // 拖到最左端还继续往右拖 = 要看更早的历史（PRD FR-2.2）
                                if (step.bars > 0 && moved.startIndex(barCount) == 0) {
                                    val oldest = series.candles.firstOrNull()?.openTime ?: 0L
                                    if (oldest != oldestRequested) {
                                        oldestRequested = oldest
                                        onLoadMore()
                                    }
                                }
                            }
                        },
                        onZoom = { barFactor, anchorX ->
                            // 逐帧因子太小会被 zoom() 里的 roundToInt 抹平，
                            // 必须先把「不足一根」的零头攒起来（与横向平移同一套思路）。
                            val before = viewport.clamp(barCount).visibleBars
                            val step = ChartGesture.accumulatePinch(
                                remainder = pinchRemainder,
                                factor = barFactor,
                                visibleBars = before,
                            )
                            if (step.bars != 0) {
                                viewport = viewport.zoom(
                                    barFactor = (before + step.bars).toFloat() / before,
                                    anchorRatio = anchorX / geo.plotWidthPx,
                                    barCount = barCount,
                                )
                                // zoom() 内部 roundToInt，实际生效的根数未必等于请求的 step，
                                // 差额必须退回余量，否则取整误差会逐帧累积成偏置。
                                val applied = viewport.clamp(barCount).visibleBars - before
                                pinchRemainder = ChartGesture.reportApplied(
                                    remainder = step.remainder,
                                    requestedBars = step.bars,
                                    appliedBars = applied,
                                )
                            } else {
                                pinchRemainder = step.remainder
                            }
                        },
                        onPriceZoom = { factor ->
                            // 用 autoRange 当基准算钳制边界，不能传当前量程（会越缩越跑）
                            priceZoom = (priceZoom * factor).coerceIn(
                                ValueRange.MIN_SPAN_RATIO.toFloat(),
                                ValueRange.MAX_SPAN_RATIO.toFloat(),
                            )
                        },
                        onPricePan = { deltaFraction ->
                            // 钳的是累积量本身而不是渲染结果：否则拖到边界后 pricePan
                            // 还在涨，反向拖要先走完这段空行程才见效，手感像卡住了。
                            // 跨度取「缩放后」的量程（平移不改变跨度），与渲染时一致。
                            val span = (autoRange.high - autoRange.low) * priceZoom
                            val limit = autoRange.panLimit(autoRange, span)
                            pricePan = (pricePan + deltaFraction).coerceIn(limit.start, limit.endInclusive)
                        },
                        onCrosshair = { position ->
                            crosshair = if (position == null) null else {
                                val index = viewport.indexAt(position.x / geo.plotWidthPx, barCount)
                                if (index >= 0) Crosshair(index, position.y) else crosshair
                            }
                        },
                    )
                },
        ) {
            if (!isReady) return@Canvas
            // 纵向缩放/平移会把 K 线推出量程，`toFraction` 只把结果夹到 [-0.5, 1.5]，
            // 落在边界外的部分仍会被画出来 —— 于是 K 线跑到图例带和时间轴上去。
            // 用 clipRect 把每次绘制限制在本 pane 的矩形里，才是根治。
            clipRect(
                left = 0f,
                top = geo.mainTopPx,
                right = geo.plotWidthPx,
                bottom = geo.mainTopPx + geo.mainHeightPx,
            ) {
                drawGridAndAxes(geo, palette, mainRange, subRanges)
                drawCandles(series, plotRange, visibleBars, geo, palette, mainRange)
                drawOverlays(series, plotRange, visibleBars, geo, palette, mainRange)
                drawLastPrice(series, geo, palette, mainRange)
            }
            series.subPanes.forEachIndexed { index, pane ->
                clipRect(
                    left = 0f,
                    top = geo.subTopOf(index),
                    right = geo.plotWidthPx,
                    bottom = geo.subTopOf(index) + geo.subHeightPx,
                ) {
                    drawSubPane(
                        series, pane, plotRange, visibleBars, geo, palette, subRanges[index], index,
                    )
                }
            }
            drawCrosshair(crosshair, plotRange, visibleBars, geo, palette)
        }

        if (isReady) {
            PriceAxisLabels(mainRange, geo, density, tickSize, Modifier.align(Alignment.TopStart))
            SubAxisLabels(series.subPanes, subRanges, geo, density, Modifier.align(Alignment.TopStart))
            TimeAxisLabels(
                series, plotRange, visibleBars, geo, interval, density,
                Modifier.align(Alignment.TopStart),
            )
            ChartLegend(
                tooltip = remember(crosshair, barCount, interval, tickSize) {
                    ChartModel.tooltip(
                        series.candles,
                        crosshair?.index ?: (barCount - 1),
                        interval,
                        tickSize,
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = Spacing.Sm, top = 4.dp),
                legendMaxWidth = with(density) { geo.plotWidthPx.toDp() } - Spacing.Sm * 2,
            )
        }
        // 纵向刻度被缩放过就提示一次并给一键复位：不然用户会以为「图怎么长这样」，
        // 而且没有任何办法回去（双击复位这个手势不显眼）。
        // 位置选**绘图区左下角**：
        //   · 右侧不放 —— 最新/最该看的蜡烛就在右边，浮层压上去代价最大；
        //   · 顶部不放 —— 图例带（开高低收/量）占满左上，且绘图区顶端往往是近期高点；
        //   · 左下角是整块图里信息密度最低的地方（左侧是历史蜡烛、下沿是最低价影线），
        //     且天然离图例与时间轴都足够远。
        if (priceZoom != 1f || pricePan != 0f) {
            val badgeStyle = MaterialTheme.typography.labelSmall
            val badgeSize = remember(badgeStyle, density) {
                measurer.measure(
                    AnnotatedString(RESET_BADGE_TEXT),
                    badgeStyle,
                    density = density,
                ).size
            }
            val badgeHeightPx = with(density) {
                badgeSize.height.toFloat() + RESET_BADGE_VERTICAL_PADDING_DP.dp.toPx()
            }
            PriceScaleResetBadge(
                onClick = {
                    priceZoom = 1f
                    pricePan = 0f
                },
                modifier = Modifier
                    .offset(
                        x = Spacing.Sm,
                        y = with(density) {
                            ((geo.mainTopPx + geo.mainHeightPx) - badgeHeightPx - Spacing.Xs.toPx())
                                .toDp()
                        },
                    ),
            )
        }
    }
}

/** 「价格刻度已缩放 · 复位」小标。 */
@Composable
private fun PriceScaleResetBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MarketTheme.colors
    Text(
        text = "刻度已缩放 · 复位",
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Full))
            .background(colors.wash)
            .clickable(onClick = onClick)
            .padding(
                horizontal = Spacing.Xs,
                vertical = RESET_BADGE_VERTICAL_PADDING_DP.dp / 2,
            ),
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
        maxLines = 1,
    )
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
 * 有副图时主图占 [MAIN_SHARE]，全部副图等分剩余高度、共用横轴，
 * 因此左右边界天然对齐。
 *
 * 副图数量可变（0~4），所以副图区域用「第几块」描述而不是单一起点/高度：
 * `subTopOf(i)` / `subHeightPx` 算出每块自己的矩形。
 *
 * 顶部额外预留 [LEGEND_HEIGHT_DP] 给图例（开高低收/量），
 * 否则图例是自由流的多行 Text，会直接压在蜡烛和网格上。
 */
private data class ChartGeo(
    /**
     * 画布是否已经量到真实尺寸。
     *
     * 首帧 `onSizeChanged` 还没回调，`IntSize.Zero` 会让下方所有 `max(1f, ...)`
     * 兜底成 1px —— 那是为了让除法不炸的权宜值，**不能当尺寸用**。
     * 绘制前一律先看这个标志，而不是拿 `plotWidthPx <= 1f` 当哨兵。
     */
    val measured: Boolean,
    val plotWidthPx: Float,
    val plotHeightPx: Float,
    val labelWidthPx: Float,
    val timeAxisHeightPx: Float,
    /** 图例占用的高度；绘图区（含主图与副图）从这条线以下才开始。 */
    val legendHeightPx: Float,
    val mainHeightPx: Float,
    /** 副图块数；0 表示不显示副图。 */
    val subCount: Int,
) {
    /** 尺寸可用（已量到且宽度足够放下一个像素以上的绘图区）。 */
    val isUsable: Boolean get() = measured && plotWidthPx > 1f

    fun slot(visibleBars: Int): Float = plotWidthPx / max(1, visibleBars)

    /** 一根蜡烛的横向中心；窗口起点左侧的蜡烛会落在画布外，由裁剪处理。 */
    fun xOf(index: Int, start: Int, visibleBars: Int): Float =
        (index - start + 0.5f) * slot(visibleBars)

    fun yOf(fraction: Float, top: Float, height: Float): Float = top + fraction * height

    fun bodyWidth(visibleBars: Int): Float = (slot(visibleBars) * BODY_SHARE).coerceIn(1f, 26f)

    /**
     * 主图顶边：图例之下。`yOf(..., topPx = mainTopPx, ...)` 是唯一正确的用法，
     * 直接传 0f 会把曲线画到图例里。
     */
    val plotTopPx: Float get() = legendHeightPx

    /** 绘图区（主图 + 全部副图）总高度。 */
    val candlesHeightPx: Float get() = plotHeightPx - legendHeightPx

    /** 每块副图的高度（等分主图之外的区域）。 */
    val subHeightPx: Float
        get() = if (subCount <= 0) 0f else (candlesHeightPx - mainHeightPx) / subCount

    /** 第 [index] 块副图的顶边 Y。 */
    fun subTopOf(index: Int): Float = plotTopPx + mainHeightPx + index * subHeightPx

    companion object {
        const val MAIN_SHARE = 0.72f
        const val BODY_SHARE = 0.66f
        const val AXIS_LABEL_WIDTH_DP = 58f
        const val TIME_AXIS_HEIGHT_DP = 18f

        /**
         * 图例区高度：4 行 labelSmall（时间行 + 开高 + 低收 + 量）≈ 4×13dp，
         * 再加一点与绘图区的间距。行数变了必须同步改这里，否则图例又会压到蜡烛上。
         */
        const val LEGEND_HEIGHT_DP = 62f

        fun of(size: IntSize, density: Density, subCount: Int, legendHeightDp: Float): ChartGeo =
            with(density) {
                val measured = size.width > 0 && size.height > 0
                val label = AXIS_LABEL_WIDTH_DP.dp.toPx()
                val timeAxis = TIME_AXIS_HEIGHT_DP.dp.toPx()
                val legend = legendHeightDp.dp.toPx()
                val plotWidth = max(1f, size.width.toFloat() - label)
                val plotHeight = max(1f, size.height.toFloat() - timeAxis)
                // 极端窄高比下先保住蜡烛区域，再夹图例区，避免把绘图区压没
                val safeLegend = legend.coerceAtMost((plotHeight * 0.3f).coerceAtLeast(0f))
                val candles = max(1f, plotHeight - safeLegend)
                val main = if (subCount > 0) candles * MAIN_SHARE else candles
                ChartGeo(
                    measured = measured,
                    plotWidthPx = plotWidth,
                    plotHeightPx = plotHeight,
                    labelWidthPx = label,
                    timeAxisHeightPx = timeAxis,
                    legendHeightPx = safeLegend,
                    mainHeightPx = main,
                    subCount = subCount,
                )
            }
    }
}

/**
 * 主图区域：顶边 [ChartGeo.mainTopPx]、高度 [ChartGeo.mainHeightPx]。
 * `yOf` 只认「相对顶边的比例」，所以这两个值必须成对传，不能只换其中一个。
 */
private val ChartGeo.mainTopPx: Float get() = plotTopPx

private fun DrawScope.drawGridAndAxes(
    geo: ChartGeo,
    palette: ChartPalette,
    mainRange: ValueRange,
    subRanges: List<ValueRange>,
) {
    mainRange.gridLines().forEach { value ->
        val y = geo.yOf(mainRange.toFraction(value), geo.mainTopPx, geo.mainHeightPx)
        if (y in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) {
            drawLine(palette.grid, Offset(0f, y), Offset(geo.plotWidthPx, y), 1f)
        }
    }
    // 每块副图各画一条与主图的分隔线 + 零轴（MACD 这类有正负的指标需要基准线）
    subRanges.forEachIndexed { index, subRange ->
        if (geo.subHeightPx <= 1f) return@forEachIndexed
        val top = geo.subTopOf(index)
        drawLine(palette.grid, Offset(0f, top), Offset(geo.plotWidthPx, top), 1f)
        if (subRange.low < 0 && subRange.high > 0) {
            val zero = geo.yOf(subRange.toFraction(0.0), top, geo.subHeightPx)
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
    // window 是 plotRange（含右侧留白，可能越出序列末尾），所以索引一律走 getOrNull：
    // 右侧留白那几根没有数据，不画即可，不能让它崩掉。
    for (i in window) {
        val candle = series.candles.getOrNull(i) ?: continue
        val color = if (candle.close >= candle.open) palette.up else palette.down
        val x = geo.xOf(i, window.first, visibleBars)
        if (x < 0f || x > geo.plotWidthPx) continue
        val highY = geo.yOf(range.toFraction(candle.highDouble()), geo.mainTopPx, geo.mainHeightPx)
        val lowY = geo.yOf(range.toFraction(candle.lowDouble()), geo.mainTopPx, geo.mainHeightPx)
        if (slot < 1.5f) {
            // 密度太高时实体只会糊成一片，退化成一根影线
            drawLine(color, Offset(x, min(highY, lowY)), Offset(x, max(highY, lowY)), 1f)
            continue
        }
        drawLine(color, Offset(x, highY), Offset(x, lowY), 1f)
        val openY = geo.yOf(range.toFraction(candle.openDouble()), geo.mainTopPx, geo.mainHeightPx)
        val closeY = geo.yOf(range.toFraction(candle.closeDouble()), geo.mainTopPx, geo.mainHeightPx)
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
        val path = linePath(line.values, window, visibleBars, geo, range, geo.mainHeightPx, geo.mainTopPx)
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
    val y = geo.yOf(range.toFraction(last.closeDouble()), geo.mainTopPx, geo.mainHeightPx)
    if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return
    drawLine(
        color = if (last.close >= last.open) palette.up else palette.down,
        start = Offset(0f, y),
        end = Offset(geo.plotWidthPx, y),
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
    paneIndex: Int,
) {
    val top = geo.subTopOf(paneIndex)
    val height = geo.subHeightPx
    pane.bars?.let { bars ->
        val width = geo.bodyWidth(visibleBars)
        val base = geo.yOf(range.toFraction(0.0.coerceIn(range.low, range.high)), top, height)
        for (i in window) {
            val value = bars.getOrNull(i) ?: continue
            if (value.isNaN()) continue
            val y = geo.yOf(range.toFraction(value), top, height)
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
        val path = linePath(line.values, window, visibleBars, geo, range, height, top)
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 1.2f))
    }
}

private fun DrawScope.drawCrosshair(
    crosshair: Crosshair?,
    window: IntRange,
    visibleBars: Int,
    geo: ChartGeo,
    palette: ChartPalette,
) {
    val mark = crosshair ?: return
    if (mark.index !in window) return
    val x = geo.xOf(mark.index, window.first, visibleBars)
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    drawLine(
        palette.crosshair.copy(alpha = 0.7f),
        Offset(x, geo.plotTopPx),
        Offset(x, geo.plotHeightPx),
        1.5f,
        pathEffect = dash,
    )
    // 横线只在主图内跟随手指：落到副图上时画一条横贯全高的线会盖住副图读数
    if (mark.yPx > geo.mainTopPx + geo.mainHeightPx) return
    val y = mark.yPx.coerceIn(geo.mainTopPx, geo.mainTopPx + geo.mainHeightPx)
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
            val y = geo.yOf(range.toFraction(upper[i]), geo.mainTopPx, geo.mainHeightPx)
            if (order == 0) moveTo(x, y) else lineTo(x, y)
        }
        points.asReversed().forEach { i ->
            lineTo(
                geo.xOf(i, window.first, visibleBars),
                geo.yOf(range.toFraction(lower[i]), geo.mainTopPx, geo.mainHeightPx),
            )
        }
        close()
    }
}

/**
 * 主图右侧价格刻度。
 *
 * 两条边界规则：
 * - 文字**垂直居中**到网格线上（旧版用 `y - 6f` 硬编码，字高变了就偏）；
 * - 相邻刻度挨太近时**丢掉下面那条**——价格区间被压得很窄时网格线会挤成一堆，
 *   逐一画出来就是重叠的乱码。
 */
@Composable
private fun PriceAxisLabels(
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    val decimals = PriceFormatter.decimalsFor(tickSize)
    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val labelHeightPx = remember(style, density) {
        measurer.measure(AnnotatedString("0"), style, density = density).size.height.toFloat()
    }
    val minGapPx = labelHeightPx + with(density) { AXIS_LABEL_MIN_GAP_DP.dp.toPx() }

    var lastDrawnY = Float.NEGATIVE_INFINITY
    range.gridLines().forEach { value ->
        val y = geo.yOf(range.toFraction(value), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        if (y - lastDrawnY < minGapPx) return@forEach
        lastDrawnY = y
        AxisLabel(
            text = PriceFormatter.localeNumber(value, decimals),
            x = geo.plotWidthPx,
            centerYPx = y,
            labelHeightPx = labelHeightPx,
            density = density,
            style = style,
            modifier = modifier,
        )
    }
}

/**
 * 右侧轴文字：以 [centerYPx] 垂直居中。
 * 用固定 [labelHeightPx] 折半来定位，避免依赖 TextStyle 的行高推断。
 */
@Composable
private fun AxisLabel(
    text: String,
    x: Float,
    centerYPx: Float,
    labelHeightPx: Float,
    density: Density,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.offset(
            x = with(density) { x.toDp() },
            y = with(density) { (centerYPx - labelHeightPx / 2f).toDp() },
        ),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 副图的右侧纵轴刻度 + 左上角标题。
 *
 * 副图是多选可变的（0~4 块），所以标题必须画，否则用户看到两条线不知道哪个是 MACD；
 * 刻度只在每块副图自己的高度里取 2 条，避免小块副图被数字塞满。
 */
@Composable
private fun SubAxisLabels(
    panes: List<SubPaneData>,
    ranges: List<ValueRange>,
    geo: ChartGeo,
    density: Density,
    modifier: Modifier = Modifier,
) {
    if (panes.isEmpty() || geo.subHeightPx <= 1f) return
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val labelHeightPx = remember(style, density) {
        measurer.measure(AnnotatedString("0"), style, density = density).size.height.toFloat()
    }
    val minGapPx = labelHeightPx + with(density) { AXIS_LABEL_MIN_GAP_DP.dp.toPx() }

    panes.forEachIndexed { index, pane ->
        val range = ranges.getOrNull(index) ?: return@forEachIndexed
        val top = geo.subTopOf(index)
        Text(
            text = pane.title,
            modifier = modifier.offset(
                x = SUB_PANE_TITLE_INSET_DP.dp,
                y = with(density) { (top + SUB_PANE_TITLE_INSET_DP).toDp() },
            ),
            style = style,
            color = labelColor,
            maxLines = 1,
        )
        // 取首尾两条刻度：副图块普遍矮，画满会糊；挨太近时同样丢掉下面那条
        val lines = range.gridLines(count = 2)
        val picks = listOfNotNull(lines.firstOrNull(), lines.lastOrNull()).distinct()
        var lastDrawnY = Float.NEGATIVE_INFINITY
        picks.forEach { value ->
            val y = geo.yOf(range.toFraction(value), top, geo.subHeightPx)
            if (y - lastDrawnY < minGapPx) return@forEach
            lastDrawnY = y
            Text(
                text = PriceFormatter.localeNumber(value, PriceFormatter.DEFAULT_DECIMALS),
                modifier = modifier.offset(
                    x = with(density) { geo.plotWidthPx.toDp() },
                    y = with(density) { (y - labelHeightPx / 2f).toDp() },
                ),
                style = style,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 时间轴刻度。
 *
 * 两个必须量的约束（旧版只按「最多几条」算，必然重叠）：
 * 1. **步长由标签实际宽度反推**：`MM-dd HH:mm` 有 11 个字符，在 1080px 宽的屏上
 *    最多只放得下 3 条。所以先量出单条宽度 `labelWidthPx`，再按
 *    `minGap = labelWidthPx + 间隙` 算每条之间至少要跨多少像素。
 * 2. **右端必须裁掉**：贴着右边界的那条会横跨到价格轴上，所以最后一条的右边缘
 *    不能超过 [ChartGeo.plotWidthPx]。
 *
 * 另外文字用 `centerX` 对齐到刻度位置（旧版按左边缘定位，导致标签整体右偏、
 * 与下一个标签的间隙看起来更窄）。
 */
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

    val style = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 用最长的一条（含最宽数字）量一次，作为步长的下界；只量一次，不逐条测量。
    val sampleWidthPx = remember(interval, density, style) {
        val sample = ChartModel.formatTime(0L, interval.minutes)
        measurer.measure(AnnotatedString(sample), style, density = density).size.width.toFloat()
    }
    val minGapPx = sampleWidthPx + with(density) { TIME_LABEL_MIN_GAP_DP.dp.toPx() }

    // 「能放下几条」只决定起点步长的下限；真正是否放下由 [ChartModel.timeLabelPlacements]
    // 按前后两个标签的实际像素间距判定。旧版只按条数算 step，遇到「左端被夹进来」
    // 的标签就必然重叠（真实踩过 17:18 / 17:23 贴在一起）。
    val capacity = max(1, (geo.plotWidthPx / minGapPx).toInt())
    val slots = capacity.coerceAtMost(MAX_TIME_LABELS)
    val step = max(1, ceil(count / slots.toFloat()).toInt())
    val candidates = buildList {
        var i = window.first
        while (i <= window.last) {
            val candle = series.candles.getOrNull(i) ?: break
            add(geo.xOf(i, window.first, visibleBars) to candle.openTime)
            i += step
        }
    }

    ChartModel.timeLabelPlacements(
        centersPx = candidates.map { it.first },
        labelWidthPx = sampleWidthPx,
        plotWidthPx = geo.plotWidthPx,
        minGapPx = minGapPx,
        step = 1,
    ).forEach { placement ->
        val time = candidates[placement.index].second
        Text(
            text = ChartModel.formatTime(time, interval.minutes),
            modifier = modifier.offset(
                x = with(density) { placement.leftPx.toDp() },
                y = with(density) { (geo.plotHeightPx + 2f).toDp() },
            ),
            style = style,
            color = labelColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChartLegend(
    tooltip: CandleTooltip?,
    legendMaxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (tooltip == null) return
    Column(modifier = modifier.widthIn(max = legendMaxWidth)) {
        Text(
            text = tooltip.time + "  " + tooltip.changeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        // 拆成「开/高」与「低/收」两行：单行四个价格在 8 位数字时会顶到价格轴，
        // 拆开后每行长度腰斩，再窄的屏也放得下。
        Text(
            text = "开 ${tooltip.open} 高 ${tooltip.high}",
            style = MaterialTheme.typography.labelSmall,
            color = if (tooltip.up) UpGreen else DownRed,
            maxLines = 1,
        )
        Text(
            text = "低 ${tooltip.low} 收 ${tooltip.close}",
            style = MaterialTheme.typography.labelSmall,
            color = if (tooltip.up) UpGreen else DownRed,
            maxLines = 1,
        )
        Text(
            text = "量 ${tooltip.volume}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 单指平移、双指缩放、长按出十字光标，全在一个手势循环里判定。
 * 不用 detectTransformGestures：它与自定义长按分属两个 pointerInput 链节点，
 * 抢占事件时行为依赖链顺序，缩放手势一旦改版就会被静默打断。
 *
 * 手势分区（按手指起点和主方向判定，不是靠控件位置）：
 * - **单指横向** → 平移时间轴（看更早/更晚）；
 * - **单指纵向** → 拉伸/压缩价格刻度（手指上滑 = 量程上移看更低价区）；
 * - **双指** → 横向张合缩放时间轴 + 纵向张合缩放价格刻度（两个维度同时生效）；
 * - **长按** → 十字光标。
 *
 * 单指的方向判定加了 [DIRECTION_LOCK_PX] 的锁定阈值：手指刚按下时是斜着动的，
 * 若逐帧同时应用横/纵位移，图会一边平移一边乱缩放。先动够阈值再锁定一个方向。
 */
private suspend fun PointerInputScope.detectChartGestures(
    longPressMs: Long,
    touchSlop: Float,
    plotWidth: Float,
    plotHeight: Float,
    /** 每次新手势按下时回调一次，用于清掉上一场手势遗留的累积余量。 */
    onGestureStart: () -> Unit,
    onPan: (deltaPx: Float) -> Unit,
    onZoom: (barFactor: Float, anchorX: Float) -> Unit,
    onPriceZoom: (factor: Float) -> Unit,
    onPricePan: (deltaFraction: Float) -> Unit,
    onCrosshair: (Offset?) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        // 立刻消费 down：图表落在 verticalScroll 的父容器里，
        // 不抢占的话父容器会先开一个滚动手势，纵向拖动就变成翻页而不是调刻度。
        first.consume()
        // 新手势开始：把上一场遗留的「不足一根」零头清掉，
        // 否则上一场拖到一半松手，零头会算进下一场，手感上多出一小段跳动。
        onGestureStart()
        // 自己记下每个指头上一次的位置：不依赖 positionChange，它在不同版本里签名动过
        val lastPositions = mutableMapOf<Any, Offset>()
        var travelled = 0f
        var previousDistanceX = 0f
        var previousDistanceY = 0f
        var longPressActive = false
        // 单指方向锁：null 表示还没定，锁定后本次手势不再改
        var axis: ChartGesture.Axis? = null
        var axisAccumX = 0f
        var axisAccumY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed: List<PointerInputChange> = event.changes.filter { change -> change.pressed }
            if (pressed.isEmpty()) break
            val primary = pressed.first()
            val previous = lastPositions[primary.id] ?: primary.position
            pressed.forEach { change -> lastPositions[change.id] = change.position }
            // 每次事件都先消费，避免父滚动容器把纵向位移吃掉
            pressed.forEach { change -> change.consume() }

            if (!longPressActive && pressed.size == 1) {
                val held = primary.uptimeMillis - first.uptimeMillis
                if (held >= longPressMs && travelled <= touchSlop) longPressActive = true
            }
            if (longPressActive) {
                onCrosshair(primary.position)
                continue
            }
            if (pressed.size >= 2) {
                // 双指：水平方向管时间轴缩放，垂直方向管价格刻度缩放，互不打架
                val dx = abs(pressed[0].position.x - pressed[1].position.x)
                val dy = abs(pressed[0].position.y - pressed[1].position.y)
                val anchorX = (pressed[0].position.x + pressed[1].position.x) / 2f
                ChartGesture.pinchFactor(previousDistanceX, dx)?.let { factor ->
                    onZoom(factor, anchorX.coerceIn(0f, plotWidth))
                }
                if (plotHeight > 0f) {
                    // 垂直张开 = 量程变大（把价格压扁看全局），与水平方向直觉一致
                    ChartGesture.pinchFactor(previousDistanceY, dy)?.let(onPriceZoom)
                }
                previousDistanceX = dx
                previousDistanceY = dy
                continue
            }
            val deltaX = primary.position.x - previous.x
            val deltaY = primary.position.y - previous.y
            if (deltaX != 0f || deltaY != 0f) {
                travelled += abs(deltaX) + abs(deltaY)
                previousDistanceX = 0f
                previousDistanceY = 0f
                // 方向锁：累计位移够阈值后才定性，定性后整场手势不再改
                if (axis == null) {
                    axisAccumX += deltaX
                    axisAccumY += deltaY
                    axis = ChartGesture.axisLock(axisAccumX, axisAccumY, DIRECTION_LOCK_PX)
                }
                when (axis) {
                    ChartGesture.Axis.HORIZONTAL -> if (abs(deltaX) > 0.1f) {
                        onPan(deltaX)
                    }

                    ChartGesture.Axis.VERTICAL -> if (plotHeight > 0f && abs(deltaY) > 0.1f) {
                        // 手指下滑 = 量程下移（看更低价区），因此取正号
                        onPricePan(deltaY / plotHeight)
                    }

                    null -> Unit
                }
            }
        }
        onCrosshair(null)
    }
}

/** 「刻度已缩放 · 复位」小标的文案与左右内边距（用于实测宽度）。 */
private const val RESET_BADGE_TEXT = "刻度已缩放 · 复位"

/** 徽标垂直内边距（上下各一份），用于把徽标高度算准后贴绘图区下沿。 */
private const val RESET_BADGE_VERTICAL_PADDING_DP = 4f

/** 单指方向锁的累计位移阈值（px）：手指刚按下时是斜着动的，先定性再应用。 */
private const val DIRECTION_LOCK_PX = 12f

/**
 * 时间轴最多画几条。真正的条数还要受标签宽度约束（见 [TimeAxisLabels]），
 * 这个常量只是上限——否则宽屏上会一路画到糊成一片。
 */
private const val MAX_TIME_LABELS = 4

/** 时间轴相邻标签之间至少要留的水平间隙（dp）。 */
private const val TIME_LABEL_MIN_GAP_DP = 10f

/** 纵轴相邻刻度之间至少要留的垂直间隙（dp）。 */
private const val AXIS_LABEL_MIN_GAP_DP = 4f

/** 副图标题距绘图区左边缘的内缩（dp）。 */
private const val SUB_PANE_TITLE_INSET_DP = 6f
