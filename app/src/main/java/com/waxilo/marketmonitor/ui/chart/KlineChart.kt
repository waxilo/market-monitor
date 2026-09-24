package com.waxilo.marketmonitor.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.closeDouble
import com.waxilo.marketmonitor.domain.model.highDouble
import com.waxilo.marketmonitor.domain.model.lowDouble
import com.waxilo.marketmonitor.domain.model.openDouble
import com.waxilo.marketmonitor.ui.theme.ChartLineColors
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
 * 图上的一条告警价线。
 *
 * [ruleId] 为 null 表示**正在新建**、还没落库的那根。线的归属与含义（对应哪条规则、
 * 能不能改）图表一概不管，只负责画出来并把落点报给调用方 ——
 * 与 [ChartLine] 一样，这里只承载语义，不承载业务。
 */
data class AlertPriceLine(val ruleId: Long?, val price: Double, val dragging: Boolean = false)

/**
 * 指标划线「不告警」模式在图上的一条参考曲线：**跟着该线自己配置的 K 线周期走**，
 * 逐根台阶式对齐到当前展示序列（不是定在某个价位的水平线）。
 *
 * [values] 与主图蜡烛逐根对齐（长度 = 序列长度，无值处 NaN），[label] 供读数带标注。
 * 位置由调用方（DetailViewModel）用与引擎同一套指标纯函数算好。
 * 与 [AlertPriceLine] 分开建模：告警线是可交互的「预警」，这条只是「指标在哪」。
 */
data class IndicatorGuideLine(val lineId: Long, val label: String, val values: DoubleArray)

/**
 * 指标线模式（OFF）均线带在图上的一条灰色锚点水平线。
 *
 * 与 [IndicatorGuideLine] 的逐根台阶线不同：均线带把所有成员的均线值合成一个集合，
 * 图上只画现价上/下最近的两条水平线（锚点与换锚逻辑在 AlertEngine，这里只画结果）。
 * [lineId] 只供调用方过滤归属，图表本身不解释。
 */
data class BandGuideLine(val lineId: Long, val price: Double)

/**
 * 图表左下角的角标按钮（进/出全屏）。放在左下而不是左上：左上角是指标读数带
 * （连同其上方的 K 线详情小条），浮在图上会把整块读数往下顶（当年十字光标弹窗
 * 也栽在这儿，如今弹窗已改成读数带上方的小条）。
 *
 * 图标由调用方以 composable 提供，而不是传 `ImageVector`：本应用只依赖 icons-core，
 * 没有现成的全屏图标，而 Compose 1.10 起 `ImageVector.Builder.addPath` 只收
 * `List<PathNode>`，手搓矢量没有比 `Canvas` 画四条角更稳的办法。
 */
data class ChartCornerAction(
    val description: String,
    val content: @Composable () -> Unit,
    val onClick: () -> Unit,
)

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
    /**
     * 该标的已有的告警价线（价格原始值），由调用方持有并保证已按标的过滤。
     * 显隐交给 [alertLinesVisible]，但**划线模式下始终绘制**：看不见自己设过的预警价、
     * 却要进到划线模式才能调，等于把线藏进了抽屉。
     */
    alertLines: List<AlertPriceLine> = emptyList(),
    /**
     * 指标划线里选了「不告警」的那几条：按各自配置的 K 线周期逐根画出的细灰参考曲线
     * （跟着指标周期走，不是定死的水平线）。不参与拖拽与垃圾桶，只随 [alertLinesVisible]
     * 那只眼睛显隐。划线模式下不画——那时单指属于告警线，图上再叠一层拖不动的灰线只会引人去拖它。
     */
    indicatorGuides: List<IndicatorGuideLine> = emptyList(),
    /**
     * 指标线（OFF）模式均线带在图上的锚点参考线：现价上方/下方最近成员各一条
     * **灰色水平线**（引擎按集合择近 + 5 分钟冷却换锚算好，始终至多两条、不会响）。
     * 与 [indicatorGuides] 同样只随眼睛显隐、不参与拖拽；划线模式下同样不画。
     */
    bandGuides: List<BandGuideLine> = emptyList(),
    /**
     * 划线模式下：单指纵向拖动改为移动告警线，**不再**平移价格刻度，也不会出十字光标。
     * 双指缩放照旧（画线时同样需要能缩放看细节）。
     */
    alertLineMode: Boolean = false,
    /**
     * 划线是否画在图上（主图右下角那只眼睛控制）。仅在非划线模式下生效 ——
     * 划线时线必须可见，否则是在拖一根看不见的线。
     */
    alertLinesVisible: Boolean = true,
    /**
     * 点主图右下角的眼睛时回调；null = 不画那只眼睛（标的没有预警线时也就没有可隐藏的东西）。
     */
    onToggleAlertLines: (() -> Unit)? = null,
    /**
     * 划线过程中每帧回调「抓到的线 id（null = 新建）」与当前落点（价格原始值）。
     * id 在按下那一刻定好，整场手势不再改：拖过另一根时跳过去会让线瞬间失控。
     */
    onAlertLineDrag: (Long?, Double) -> Unit = { _, _ -> },
    /** 手指离开：调用方按同一 id 把新价写回那条规则（id 为 null 则新建规则）。 */
    onAlertLineCommit: (Long?) -> Unit = {},
    /**
     * 把线拖到右上角垃圾桶上松手：调用方删除该 id 对应的规则（id 为 null = 手上一笔
     * 还没落库的新线，等同于放弃）。划线模式下右上角才会出现垃圾桶。
     */
    onAlertLineDelete: (Long?) -> Unit = {},
    /** 左上角角标（进入全屏）。null 表示不画，读数带也就顶到最左。 */
    cornerAction: ChartCornerAction? = null,
    /**
     * 十字光标当前指到的 K 线下标；null = 没在长按。
     *
     * K 线详情（OHLC）不再浮在图上做弹窗 —— 弹窗那块字常年压着左上的蜡烛与形态。
     * 下标交给调用方，由调用方做成常显小条塞进 [candleReadout]：
     * 长按跟着手指读那一根，松手回到最新一根。
     */
    onCrosshairIndexChange: (Int?) -> Unit = {},
    /**
     * K 线详情小条：画在图表顶部、指标读数带的**正上方**，与读数带同一个容器 ——
     * 两组数字上下对读，它读单根 K 线、读数带读指标。占用的高度按
     * [CANDLE_READOUT_BAND_DP] 从绘图区顶部让出，不会压到蜡烛。
     */
    candleReadout: (@Composable () -> Unit)? = null,
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
    // 下标变化就同步给调用方的 K 线详情小条（长按换根 / 松手归 null 都走这里）
    LaunchedEffect(crosshair?.index) { onCrosshairIndexChange(crosshair?.index) }
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
     * 手势进行期间**冻结**的价格基准量程；`null` = 用实时的自动量程。
     *
     * 自动量程是由「可见数据窗口」算出来的，而横向平移/缩放会改可见窗口 ——
     * 基准若跟着逐帧重算，缩放过程中价格轴就会随可见区间一跳一跳；
     * 手指上下拉开时难免带出一点横向间距变化，于是时间轴也缩放、价格轴跟着跳，
     * 叠加起来正是用户报的「图表缩放时抖动」。
     *
     * 所以手势一开始就把基准钉死，整场手势价格轴绝对不动；松手后再交回自动量程
     * 做一次性适配（新区间的价格范围该重新适配，但只需要发生一次，而不是每帧一次）。
     */
    var priceBase by remember(symbolKey, interval.storageKey) { mutableStateOf<ValueRange?>(null) }
    /**
     * 主读数带按「指标族」分行（MA 一族一行、BOLL 一族一行）：加一个指标就多占一行，
     * 而不是把所有数值挤在同一行。行数只取决于开了哪些指标，与十字光标下标无关，
     * 因此从 `overlay.lines` 的标签直接数出来，喂给顶部读数带的高度。
     */
    val mainReadoutLines = remember(series) {
        val labels = series.overlay.lines.map { it.label }
        (if (labels.any { it.startsWith("MA") }) 1 else 0) +
            (if (labels.any { it.startsWith("BOLL") }) 1 else 0)
    }
    val readoutHeightDp = ChartGeo.readoutBandHeight(mainReadoutLines) +
        // 小条与读数带同容器：它的高度也计进读数带，绘图区整体下移
        (if (candleReadout != null) CANDLE_READOUT_BAND_DP else 0f)
    val geo = remember(canvasSize, density, series.subPanes.size, readoutHeightDp) {
        ChartGeo.of(canvasSize, density, series.subPanes.size, readoutHeightDp)
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
     * 绘图区横向几何（**含右侧留白，可能越出序列末尾**），用于所有横向定位。
     * 与 [dataWindow] 分开是因为两者在「最新 K 线左移留白」时起点不同：
     * 用错会让整排蜡烛横向错位。
     */
    val plot = remember(viewport, barCount) {
        PlotGeometry(
            indices = viewport.plotRange(barCount),
            start = viewport.plotStart(barCount),
            visibleBars = viewport.clamp(barCount).visibleBars,
        )
    }
    /** 主图自动量程（未叠加用户纵向缩放）。 */
    val autoRange = remember(series, dataWindow.first, dataWindow.last) {
        series.mainRange(dataWindow.first, dataWindow.last)
    }
    /** 纵向缩放/平移的基准：手势中为冻结值，平时为自动量程（见 [priceBase]）。 */
    val rangeBase = priceBase ?: autoRange
    val mainRange = remember(rangeBase, priceZoom, pricePan) {
        rangeBase
            .scaled(priceZoom, rangeBase)
            .panned(pricePan, rangeBase)
    }
    val subRanges = remember(series, dataWindow.first, dataWindow.last) {
        series.subPanes.map { series.subRange(it, dataWindow.first, dataWindow.last) }
    }

    /**
     * 手势协程在 `pointerInput` 启动那一刻把回调闭包一起捕获，之后不会更新。
     * 量程与划线回调都必须读**实时**值，否则量程一变（缩放/平移后）划线价位就按旧量程换算。
     */
    val alertRange by rememberUpdatedState(mainRange)
    val alertDrag by rememberUpdatedState(onAlertLineDrag)
    val alertCommit by rememberUpdatedState(onAlertLineCommit)
    val alertDelete by rememberUpdatedState(onAlertLineDelete)
    val liveAlertLines by rememberUpdatedState(alertLines)
    /**
     * 绘图区几何、根数、序列与自动量程同样必须读**实时**值。
     *
     * 它们都是 `pointerInput` key 的候选，但**一个都不能真进 key**：key 一变协程就重启，
     * 而手指还按着的话，新协程的 `awaitFirstDown` 等不到新的按下事件 —— 表现为
     * 「图表卡住、怎么拖都不动」，松手重按才恢复。换周期、翻页、轮询推来一根新蜡烛
     * 都会让根数变化，所以这个坑踩得非常频繁。
     * 改走 `rememberUpdatedState` 后，key 只剩「划线模式」这个只由按钮切换的开关。
     */
    val liveGeo by rememberUpdatedState(geo)
    val liveBarCount by rememberUpdatedState(barCount)
    val liveSeries by rememberUpdatedState(series)
    val liveAutoRange by rememberUpdatedState(autoRange)
    /** 纵向平移的钳制基准也要用冻结值，否则手势中基准一变，`pricePan` 会被反复重新钳。 */
    val liveRangeBase by rememberUpdatedState(rangeBase)
    val liveLoadMore by rememberUpdatedState(onLoadMore)

    // 每帧重建 7 个 Color 引用代价极低，反而省掉一长串 remember key——key 里不能放 MaterialTheme 调用
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette(
        up = upColor,
        down = downColor,
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        lines = ChartLineColors,
        band = scheme.primary.copy(alpha = 0.08f),
        crosshair = scheme.onSurface,
    )

    /**
     * 已有告警线的像素落点，供手势就近抓取。
     *
     * 走 live* 而不是直接读 `geo` / `alertLines`：本函数被 `pointerInput` 协程捕获，
     * 而协程只活到手势结束，期间量程与几何一直在变（缩放、来新蜡烛），
     * 按下那一刻按旧值算落点就会抓错线、或者干脆凭空新建一根。
     */
    fun alertAnchors(): List<AlertLineAnchor> {
        val plot = liveGeo
        val range = alertRange
        return liveAlertLines.map { line ->
            AlertLineAnchor(
                ruleId = line.ruleId,
                yPx = plot.yOf(range.toFraction(line.price), plot.mainTopPx, plot.mainHeightPx),
            )
        }
    }
    val alertGrabPx = with(density) { ALERT_GRAB_WIDTH.toPx() }

    /**
     * 划线模式下，手指正把线悬在右上角垃圾桶上方 —— 用来高亮垃圾桶给反馈。
     * 与 priceBase 等一样是「手势期间写、重组读」的内部态，不进气势 key，靠 remember 常驻。
     */
    var alertTrashHot by remember { mutableStateOf(false) }
    val trashInsetPx = with(density) { SPACING_TRASH_INSET.toPx() }
    val trashSizePx = with(density) { TRASH_BUTTON_SIZE.toPx() }
    /**
     * 右上角删除垃圾桶的命中矩形（画布像素），锚在**绘图区**（主图）的右上角。
     * 放右上而不是右下：右下角是最新蜡烛与最新价标，正被盯着看，删除目标压在那儿代价最大
     * （那块地方平时也归划线显隐的眼睛）；而划线时右上角一般空着（左上角是指标读数带，
     * 最新价读数在右侧轴上）。
     * 只在划线模式下非空；与浮层里那个垃圾桶 Box 用同一套 geo + inset + size，
     * 两者一旦错开就会「看着命中、松手却没删」。
     *
     * 读实时的 [liveGeo]（本函数被手势协程捕获，期间量程/尺寸一直在变）。
     */
    fun alertDeleteRect(): Rect? {
        if (!alertLineMode) return null
        val plot = liveGeo
        if (!plot.isUsable) return null
        val right = plot.plotWidthPx - trashInsetPx
        val top = plot.mainTopPx + trashInsetPx
        return Rect(left = right - trashSizePx, top = top, right = right, bottom = top + trashSizePx)
    }

    /**
     * 这一帧要不要画线（连同线右侧的价位标签）。
     *
     * 划线模式下无条件为真：眼睛关的是「平时嫌线挡着看形态」，不该顺手把要拖的那根也藏掉，
     * 拖一根看不见的线等于盲操作。
     */
    val alertLinesShown = alertLinesVisible || alertLineMode

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                // ⚠️ key 只能有这三样：划线开关、标的、周期。
                //
                // 它们是**只由用户点击改变**的量，不可能在手势中途变。其余一切（可见根数、
                // 序列长度、绘图区像素尺寸）都会在手势进行中被数据更新改掉：key 一变
                // pointerInput 协程就重启，当前手势直接作废 —— 重启后的 awaitFirstDown
                // 要等一次全新的按下，手指没抬起就永远等不到。表现有两种：
                // 「缩放被打断，每次捏一下只动一根」，以及「整块图卡住、怎么拖都不动」。
                // 这些值改从上面的 live* 里读实时值。
                //
                // 标的与周期**必须**进 key，原因是另一回事：下面的视窗状态挂在
                // `remember(symbolKey, interval.storageKey)` 上，换周期会**重建 MutableState**，
                // 而本协程的闭包捕获的是创建那一刻的委托对象。key 不含周期时协程不重启，
                // 手势就一直在写**已经被丢弃的旧 state**，渲染读的却是新 state ——
                // 表现为「切换周期后图表固定死、怎么拖都不动」。首次进入时两者同时创建，
                // 所以「从条目进来的图能拖」而「切过周期的图拖不动」。
                .pointerInput(alertLineMode, symbolKey, interval.storageKey) {
                    detectChartGestures(
                        longPressMs = viewConfiguration.longPressTimeoutMillis,
                        touchSlop = viewConfiguration.touchSlop,
                        geo = { liveGeo },
                        alertLineMode = alertLineMode,
                        alertLineAnchors = ::alertAnchors,
                        alertGrabPx = alertGrabPx,
                        alertDeleteRect = ::alertDeleteRect,
                        onAlertLineDrag = { id, yPx ->
                            val plot = liveGeo
                            val fraction =
                                (yPx - plot.mainTopPx) / plot.mainHeightPx.coerceAtLeast(1f)
                            // 量程是变动的（缩放/平移），必须走 rememberUpdatedState 读实时值，
                            // 否则这个回调会拿协程启动那一刻的旧量程换算，画出来的线跑偏
                            val price = alertRange.fromFraction(fraction)
                            if (price.isFinite()) alertDrag(id, price)
                        },
                        onAlertLineCommit = { id -> alertCommit(id) },
                        onAlertLineDelete = { id -> alertDelete(id) },
                        onAlertLineOverTrash = { hot -> alertTrashHot = hot },
                        onGestureStart = {
                            // 冻结价格基准：整场手势里价格轴不再随可见区间重算（见 priceBase）
                            priceBase = liveAutoRange
                        },
                        onGestureEnd = { priceBase = null },
                        onPan = { deltaPx ->
                            val bars = liveBarCount
                            val slot = liveGeo.slot(viewport.clamp(bars).visibleBars)
                            // 位移直接折成**小数根**交给 pan()：视窗是浮点的，
                            // 单帧不足一根也照样生效，不需要再攒零头（旧版攒零头正是
                            // 「拖动一格一格跳」的来源）。
                            val deltaBars = ChartGesture.barDelta(deltaPx, slot)
                            if (deltaBars != 0f) {
                                val moved = viewport.pan(deltaBars, bars)
                                viewport = moved
                                // 拖到最左端还继续往右拖 = 要看更早的历史（PRD FR-2.2）
                                if (deltaBars > 0f && moved.startIndex(bars) == 0) {
                                    val oldest = liveSeries.candles.firstOrNull()?.openTime ?: 0L
                                    if (oldest != oldestRequested) {
                                        oldestRequested = oldest
                                        liveLoadMore()
                                    }
                                }
                            }
                        },
                        onZoom = { barFactor, anchorX ->
                            // 因子直接交给 zoom()：视窗是浮点的，`visibleBars` 不会被取整，
                            // 缓慢捏合的零点几根也能逐帧体现，不需要再攒余量。
                            viewport = viewport.zoom(
                                barFactor = barFactor,
                                anchorRatio = anchorX / liveGeo.plotWidthPx,
                                barCount = liveBarCount,
                            )
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
                            val range = liveRangeBase
                            val span = (range.high - range.low) * priceZoom
                            val limit = range.panLimit(range, span)
                            pricePan = (pricePan + deltaFraction).coerceIn(limit.start, limit.endInclusive)
                        },
                        onCrosshair = { position ->
                            crosshair = if (position == null) null else {
                                val index = viewport.indexAt(position.x / liveGeo.plotWidthPx, liveBarCount)
                                if (index >= 0) Crosshair(index, position.y) else crosshair
                            }
                        },
                    )
                },
        ) {
            if (!isReady) return@Canvas
            // 纵向缩放/平移会把 K 线推出量程，`toFraction` 只把结果夹到 [-0.5, 1.5]，
            // 落在边界外的部分仍会被画出来 —— 于是 K 线跑到读数带和时间轴上去。
            // 用 clipRect 把每次绘制限制在本 pane 的矩形里，才是根治。
            clipRect(
                left = 0f,
                top = geo.mainTopPx,
                right = geo.plotWidthPx,
                bottom = geo.mainTopPx + geo.mainHeightPx,
            ) {
                drawGridAndAxes(geo, palette, mainRange, subRanges)
                drawCandles(series, plot, geo, palette, mainRange)
                drawOverlays(series, plot, geo, palette, mainRange)
                drawLastPrice(series, geo, palette, mainRange)
                // 指标参考线先画：它只是背景参照，告警线压上来时以告警线为准
                if (alertLinesVisible && !alertLineMode) {
                    drawIndicatorGuides(indicatorGuides, series, plot, geo, palette, mainRange)
                    drawBandGuides(bandGuides, geo, palette, mainRange)
                }
                if (alertLinesShown) drawAlertLines(alertLines, geo, palette, mainRange)
            }
            series.subPanes.forEachIndexed { index, pane ->
                clipRect(
                    left = 0f,
                    top = geo.subTopOf(index),
                    right = geo.plotWidthPx,
                    bottom = geo.subTopOf(index) + geo.subHeightPx,
                ) {
                    drawSubPane(series, pane, plot, geo, palette, subRanges[index], index)
                }
            }
            drawCrosshair(crosshair, plot, geo, palette)
        }

        if (isReady) {
            PriceAxisLabels(mainRange, geo, density, tickSize, Modifier.align(Alignment.TopStart))
            // 最新价那条虚线必须自带读数：它回答的是「现在多少钱」，而右侧刻度是 1-2-5 阶梯，
            // 最近的一条刻度离它往往还有一段距离，靠目测估价没有意义。
            // 画在刻度之后 —— 它要盖住同高的那条刻度文字（这正是交易软件的通行做法）；
            // 又排在十字光标/告警线之前 —— 那两个跟着手指走，被压住就看不见了。
            LastPriceBadge(
                series = series,
                range = mainRange,
                geo = geo,
                density = density,
                tickSize = tickSize,
                upColor = upColor,
                downColor = downColor,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // 十字光标的横向读数：长按只画一条线而不给价位，用户无从知道线落在哪，
            // 而「这是哪个价位」正是划线的全部意义（全屏里还要据此建预警线）。
            CrosshairPriceBadge(
                crosshair = crosshair,
                range = mainRange,
                geo = geo,
                density = density,
                tickSize = tickSize,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // 告警线也要给出价位：划线时手指底下若没有读数，落点全凭感觉
            // 线不画时标签也不画 —— 只藏线不藏数字，右侧会凭空挂着一串来路不明的价位
            if (alertLinesShown) alertLines.forEach { line ->
                AlertLinePriceBadge(
                    price = line.price,
                    range = mainRange,
                    geo = geo,
                    density = density,
                    tickSize = tickSize,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            SubAxisLabels(series.subPanes, subRanges, geo, density, Modifier.align(Alignment.TopStart))
            TimeAxisLabels(
                series, plot, geo, interval, density,
                Modifier.align(Alignment.TopStart),
            )

            // ---- 角标（进入 / 退出全屏）：绘图区左下角 ----
            // **不占左上角**：左上角是指标读数带，放那儿会把整块读数往下顶
            // （当年浮在左上的十字光标弹窗被报过同样的「挤占 k 线数据弹窗」）。
            // 左下角信息密度最低，且离右上角的删除垃圾桶最远。
            // 放在图表而不是顶栏：详情页要滚动才能看到图表，入口钉在顶栏等于每次先得翻页。
            cornerAction?.let { action ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = CORNER_BUTTON_INSET,
                            y = with(density) {
                                ((geo.mainTopPx + geo.mainHeightPx) -
                                    CORNER_BUTTON_SIZE.toPx() - CORNER_BUTTON_INSET.toPx()).toDp()
                            },
                        )
                        .size(CORNER_BUTTON_SIZE)
                        .clip(Radius.fullShape)
                        .semantics { contentDescription = action.description }
                        .clickable(onClick = action.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    // 尺寸与着色都交给调用方：它才知道自己要画什么
                    action.content()
                }
            }

            // ---- 币安式的指标读数带 ----
            // 有十字光标就读那一根，否则读最新一根：这一行是「指标现在多少」，
            // 平时跟着最新价走、长按时跟着手指走。
            val readoutIndex = crosshair?.index ?: (series.size - 1)
            val priceDecimals = PriceFormatter.decimalsFor(tickSize)
            // 角标已移到绘图区左下角，不再挤占顶部这一行，读数带从绘图区左沿起。
            val readoutStart = Spacing.Sm
            // 副图读数带只能铺到绘图区右沿（右侧价格轴的刻度与它同高，越界会压住数字）。
            val subReadoutWidth = with(density) { geo.plotWidthPx.toDp() } - readoutStart
            // 主读数带在**顶部读数带**这一区，而右侧价格轴从绘图区（readoutHeightPx）才开始，
            // 所以这几行右上角是空的：铺满整宽，多出的那截轴宽刚好容下整族数值不截断。
            val mainReadoutWidth = with(density) { canvasSize.width.toDp() } - readoutStart
            // 按指标族拆行：MA 一族一行、BOLL 一族一行（顺序沿用 overlay 的 MA→BOLL）。
            val mainGroups = remember(series, readoutIndex, priceDecimals) {
                series.mainReadoutAt(readoutIndex, priceDecimals)
                    .partition { it.text.startsWith("BOLL") }
                    .let { (boll, ma) -> listOf(ma, boll).filter { it.isNotEmpty() } }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = readoutStart, top = Spacing.Xxs)
                    .widthIn(max = mainReadoutWidth),
            ) {
                // K 线详情小条排在读数带正上方：同一个容器、同一个左起点，
                // 上下两叠数字天然是「这一根 K 线 / 这些指标」的对读关系
                if (candleReadout != null) {
                    Box(modifier = Modifier.padding(bottom = Spacing.Xxs)) { candleReadout() }
                }
                mainGroups.forEach { group ->
                    IndicatorReadout(segments = group, palette = palette)
                }
            }
            val subReadouts = remember(series, readoutIndex, priceDecimals) {
                series.subPanes.map { series.subReadoutAt(it, readoutIndex, priceDecimals) }
            }
            subReadouts.forEachIndexed { index, segments ->
                IndicatorReadout(
                    segments = segments,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = Spacing.Sm, y = with(density) { (geo.subReadoutTopOf(index) + 2f).toDp() })
                        .widthIn(max = subReadoutWidth),
                )
            }

            // ---- 划线模式的删除垃圾桶（绘图区右上角，仅拖动时浮现） ----
            // 只在「手上有正在拖的线」时出现：不拖线时它是不可用的，常驻只会白占绘图区一角。
            // 定位到**绘图区**右上角而不是整块画布（画布右上角会盖住指标读数带），
            // 且与 alertDeleteRect() 严格同框，否则会出现「看着命中、松手却没删」。
            // 它不是可点按钮而是**落点**：
            // 不挂 clickable，指针事件穿透到下层 Canvas，由手势判定「线拖进来松手 = 删除」。
            if (alertLineMode && alertLines.any { it.dragging }) {
                val colors = MarketTheme.colors
                val rect = alertDeleteRect()
                if (rect != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = with(density) { rect.left.toDp() },
                                y = with(density) { rect.top.toDp() },
                            )
                            .size(TRASH_BUTTON_SIZE)
                            .clip(Radius.fullShape)
                            .background(if (alertTrashHot) DownRed else colors.washStrong)
                            .semantics { contentDescription = "拖动告警线到此删除" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(TRASH_ICON_SIZE),
                            tint = if (alertTrashHot) Color.White else colors.muted,
                        )
                    }
                }
            }

            // ---- 划线显隐的眼睛（主图右下角） ----
            // 贴着主图底沿：副图再怎么堆，眼睛都留在被藏的那条线所在的那块图上，
            // 开关与被控对象同区。左下/左上分别站着全屏角标与指标读数带，右上归垃圾桶。
            // 划线模式下不画：那时线必然可见（见 alertLinesShown），眼睛是个永远无效的开关。
            // 没有预警线时不画：藏无可藏，常驻一个不动的开关只是噪点。
            // 它**要**挂 clickable（与垃圾桶相反）：点它就该切显隐，
            // 不该同时被下层画布当成一次拖动。
            if (onToggleAlertLines != null && !alertLineMode &&
                (alertLines.isNotEmpty() || indicatorGuides.isNotEmpty() || bandGuides.isNotEmpty())
            ) {
                val colors = MarketTheme.colors
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = with(density) { (geo.plotWidthPx - trashInsetPx - EYE_BUTTON_SIZE.toPx()).toDp() },
                            y = with(density) {
                                ((geo.mainTopPx + geo.mainHeightPx) - trashInsetPx - EYE_BUTTON_SIZE.toPx()).toDp()
                            },
                        )
                        .size(EYE_BUTTON_SIZE)
                        .clip(Radius.fullShape)
                        // 半透明底：它贴在蜡烛上，实心底等于在图里挖了一个洞；
                        // 图标本体保持不透明，读「开/关」状态不能跟着一起淡
                        .background(colors.washStrong.copy(alpha = 0.5f))
                        .semantics {
                            contentDescription = if (alertLinesVisible) "隐藏划线" else "显示划线"
                        }
                        .clickable(onClick = onToggleAlertLines),
                    contentAlignment = Alignment.Center,
                ) {
                    EyeGlyph(
                        open = alertLinesVisible,
                        tint = if (alertLinesVisible) colors.ink else colors.muted,
                    )
                }
            }
        }
        // 纵向刻度被缩放过就提示一次并给一键复位：不然用户会以为「图怎么长这样」，
        // 而且没有任何办法回去（双击复位这个手势不显眼）。
        // 位置选**绘图区左下角**：
        //   · 右侧不放 —— 最新/最该看的蜡烛就在右边，浮层压上去代价最大；
        //   · 顶部不放 —— 指标读数带占满左上，且绘图区顶端往往是近期高点；
        //   · 左下角是整块图里信息密度最低的地方（左侧是历史蜡烛、下沿是最低价影线），
        //     且天然离读数带与时间轴都足够远。
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
                        // 左下角现在也站着全屏角标：小标挪到角的右侧，且两者挂在同一条
                        // 水平中线（角标的行中心）上，一高一低会显得散。
                        x = with(density) {
                            (Spacing.Sm.toPx() +
                                if (cornerAction != null) CORNER_BUTTON_SIZE.toPx() + Spacing.Xs.toPx()
                                else 0f
                                ).toDp()
                        },
                        y = with(density) {
                            ((geo.mainTopPx + geo.mainHeightPx) -
                                CORNER_BUTTON_INSET.toPx() -
                                CORNER_BUTTON_SIZE.toPx() / 2f -
                                badgeHeightPx / 2f).toDp()
                        },
                    ),
            )
        }
    }
}

/**
 * 划线显隐的「眼睛」：开 = 眼眶 + 瞳孔，关 = 再加一道斜杠。
 *
 * 自绘而不用 `Icons.Default.Visibility` —— 眼睛那对矢量在 material-icons-extended 里，
 * 为一个图标拖进上千个不划算，本应用只依赖 icons-core（同 DetailScreen 的全屏角标）。
 */
@Composable
private fun EyeGlyph(open: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(EYE_ICON_SIZE)) {
        val stroke = size.width * 0.08f
        val centerY = size.height / 2f
        val halfWidth = size.width * 0.46f
        val arch = size.height * 0.6f
        // 上下两条对称的二次曲线拼出眼眶：二次曲线只走到控制点的一半高度，
        // 所以 arch 要放大到 0.6 倍画高，眼睛才有 0.3 倍画高的实际半高
        val outline = Path().apply {
            moveTo(size.width / 2f - halfWidth, centerY)
            quadraticBezierTo(size.width / 2f, centerY - arch, size.width / 2f + halfWidth, centerY)
            quadraticBezierTo(size.width / 2f, centerY + arch, size.width / 2f - halfWidth, centerY)
            close()
        }
        drawPath(outline, tint, style = Stroke(width = stroke))
        drawCircle(tint, radius = size.width * 0.14f, center = Offset(size.width / 2f, centerY))
        if (!open) {
            drawLine(
                color = tint,
                start = Offset(size.width * 0.14f, size.height * 0.12f),
                end = Offset(size.width * 0.86f, size.height * 0.88f),
                strokeWidth = stroke,
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
        LineRole.QUATERNARY -> lines.getOrElse(4) { grid }
        LineRole.QUINARY -> lines.getOrElse(5) { grid }
        LineRole.SENARY -> lines.getOrElse(6) { grid }
        LineRole.OCTONARY -> lines.getOrElse(7) { grid }
        LineRole.UP -> up
        LineRole.DOWN -> down
        LineRole.LABEL -> label
    }
}

/**
 * 绘图区的横向几何：**整数**的索引区间 + **浮点**的窗口左端与可见根数。
 *
 * 两者缺一不可，也**不能合并**：
 * - [indices] 用来取数据（下标不能是小数），右侧留白时还会越出序列末尾，
 *   所以取数一律走 `getOrNull`；
 * - [start] / [visibleBars] 用来算像素。双指缩放时可见根数是连续变化的，
 *   若按整数根定位，每变一根整张图就横向跳一个槽宽 —— 那正是用户报的「捏合时抖动」。
 */
private data class PlotGeometry(
    val indices: IntRange,
    val start: Float,
    val visibleBars: Float,
)

/**
 * 像素布局：价格刻度固定右侧、时间刻度固定底部，其余给蜡烛。
 * 有副图时每块副图固定 [SUB_PANE_HEIGHT_DP] 高（多开一块就多一块，不把已有的挤薄；
 * 竖屏 ChartArea 据此加高整张图），主图吃剩下的、大小与副图块数无关；空间不够
 * （全屏叠多块）时主图保底 [ChartGeo.MAIN_MIN_DP]，副图等比压缩。
 *
 * 副图数量可变（0~4），所以副图区域用「第几块」描述而不是单一起点/高度：
 * `subReadoutTopOf(i)` 是那块读数行的位置，`subTopOf(i)` / `subHeightPx` 算出
 * 每块自己的**绘图**矩形 —— 读数行之下，曲线不会再从字底下穿过去。
 *
 * 顶部额外预留一截给指标读数带（币安式的彩色参数，高度由 [readoutBandHeight] 按指标行数算），
 * 否则读数是自由流的 Text，会直接压在蜡烛和网格上。
 */
/**
 * 每块副图的固定高度（dp）。竖屏 ChartArea 按副图块数加高整张图时用的就是这个值，
 * 所以放文件顶层而不是 ChartGeo 的伴生里 —— ChartGeo 是 private，常量却要跨文件共享。
 */
internal const val SUB_PANE_HEIGHT_DP = 110f

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
    /** 指标读数带占用的高度；绘图区（含主图与副图）从这条线以下才开始。 */
    val readoutHeightPx: Float,
    val mainHeightPx: Float,
    /** 副图块数；0 表示不显示副图。 */
    val subCount: Int,
    /** 每块副图顶部为读数行预留的高度；曲线从它下方才开始画。 */
    val subReadoutPx: Float,
) {
    /** 尺寸可用（已量到且宽度足够放下一个像素以上的绘图区）。 */
    val isUsable: Boolean get() = measured && plotWidthPx > 1f

    fun slot(visibleBars: Float): Float = plotWidthPx / max(1f, visibleBars)

    /** 一根蜡烛的横向中心；窗口起点左侧的蜡烛会落在画布外，由裁剪处理。 */
    fun xOf(index: Int, plot: PlotGeometry): Float =
        (index - plot.start + 0.5f) * slot(plot.visibleBars)

    fun yOf(fraction: Float, top: Float, height: Float): Float = top + fraction * height

    fun bodyWidth(visibleBars: Float): Float = (slot(visibleBars) * BODY_SHARE).coerceIn(1f, 26f)

    /**
     * 主图顶边：读数带之下。`yOf(..., topPx = mainTopPx, ...)` 是唯一正确的用法，
     * 直接传 0f 会把曲线画到读数里。
     */
    val plotTopPx: Float get() = readoutHeightPx

    /** 绘图区（主图 + 全部副图）总高度。 */
    val candlesHeightPx: Float get() = plotHeightPx - readoutHeightPx

    /** 每块副图的高度（等分主图之外的区域，再减掉各自顶部的读数行）。 */
    val subHeightPx: Float
        get() = (subRegionPx - subReadoutPx).coerceAtLeast(0f)

    /** 每块副图整区高度（读数行 + 绘图区）。 */
    private val subRegionPx: Float
        get() = if (subCount <= 0) 0f else (candlesHeightPx - mainHeightPx) / subCount

    /** 第 [index] 块副图读数行的顶边（在该块绘图区之上）。 */
    fun subReadoutTopOf(index: Int): Float = plotTopPx + mainHeightPx + index * subRegionPx

    /** 第 [index] 块副图绘图区的顶边（读数行之下）。 */
    fun subTopOf(index: Int): Float = subReadoutTopOf(index) + subReadoutPx

    companion object {
        /**
         * 空间不够时（全屏叠多块副图）主图保底的高度（dp），其余等比压缩副图。
         * 必须是**绝对值**而不是占比：竖屏容器随副图数变高，
         * 占比保底会反过来把主图抬高，「主图大小与副图数量无关」就破了。
         */
        const val MAIN_MIN_DP = 150f
        const val BODY_SHARE = 0.66f
        const val AXIS_LABEL_WIDTH_DP = 58f
        const val TIME_AXIS_HEIGHT_DP = 18f

        /** 单行读数占用的高度（与 [READOUT_LINE_HEIGHT] 对齐，单位 dp）。 */
        const val READOUT_LINE_DP = 12f

        /** 读数带顶部留白（`Spacing.Xxs` 那截），加在整叠读数行的上下。 */
        const val READOUT_BAND_PAD_DP = 6f

        /** 每块副图顶部为读数行预留的高度（dp）：一行读数 + 上下留白。 */
        const val SUB_READOUT_DP = 16f

        /**
         * 顶部读数带高度：一族指标一行，故按行数线性增长（至少留一行）。
         * 与 [READOUT_LINE_HEIGHT] 联动，改了字号/行高要同步改 [READOUT_LINE_DP]，否则读数会压到蜡烛上。
         */
        fun readoutBandHeight(lineCount: Int): Float =
            READOUT_BAND_PAD_DP + READOUT_LINE_DP * lineCount.coerceAtLeast(1)

        fun of(size: IntSize, density: Density, subCount: Int, readoutHeightDp: Float): ChartGeo =
            with(density) {
                val measured = size.width > 0 && size.height > 0
                val label = AXIS_LABEL_WIDTH_DP.dp.toPx()
                val timeAxis = TIME_AXIS_HEIGHT_DP.dp.toPx()
                val readout = readoutHeightDp.dp.toPx()
                val subReadout = SUB_READOUT_DP.dp.toPx()
                val plotWidth = max(1f, size.width.toFloat() - label)
                val plotHeight = max(1f, size.height.toFloat() - timeAxis)
                // 极端窄高比下先保住蜡烛区域，再夹读数带，避免把绘图区压没
                val safeReadout = readout.coerceAtMost((plotHeight * 0.3f).coerceAtLeast(0f))
                val candles = max(1f, plotHeight - safeReadout)
                // 每块副图固定高度、主图吃剩下的；剩余不够时主图保底 MAIN_MIN_DP，
                // 副图退化为等分压缩（全屏叠 3~4 块就是这种情形）。
                val subPane = SUB_PANE_HEIGHT_DP.dp.toPx()
                val main = if (subCount > 0) {
                    (candles - subCount * subPane)
                        .coerceAtLeast(MAIN_MIN_DP.dp.toPx())
                } else candles
                ChartGeo(
                    measured = measured,
                    plotWidthPx = plotWidth,
                    plotHeightPx = plotHeight,
                    labelWidthPx = label,
                    timeAxisHeightPx = timeAxis,
                    readoutHeightPx = safeReadout,
                    mainHeightPx = main,
                    subCount = subCount,
                    subReadoutPx = subReadout,
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
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    val slot = geo.slot(plot.visibleBars)
    val bodyWidth = geo.bodyWidth(plot.visibleBars)
    val half = bodyWidth / 2f
    // plot.indices 含右侧留白（可能越出序列末尾），所以索引一律走 getOrNull：
    // 右侧留白那几根没有数据，不画即可，不能让它崩掉。
    for (i in plot.indices) {
        val candle = series.candles.getOrNull(i) ?: continue
        val color = if (candle.close >= candle.open) palette.up else palette.down
        val x = geo.xOf(i, plot)
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
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    series.overlay.bandFill?.let { (upper, lower) ->
        bandPath(upper, lower, plot, geo, range)?.let { drawPath(it, palette.band) }
    }
    series.overlay.lines.forEach { line ->
        val path = linePath(line.values, plot, geo, range, geo.mainHeightPx, geo.mainTopPx)
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

/**
 * 指标划线的「不告警」参考线：按各自配置的周期逐根台阶对齐的细灰线。
 *
 * 换算链与 [drawAlertLines] 一致（`toFraction` → `yOf`），NaN（指标窗口不足）跳过。
 * 与告警线（虚线 + 可拖）刻意不同形态：一眼分出「这是指标参照」还是「这是会响的预警」。
 */
private fun DrawScope.drawIndicatorGuides(
    guides: List<IndicatorGuideLine>,
    series: ChartSeries,
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    guides.forEach { guide ->
        if (guide.values.size != series.size) return@forEach
        val path = linePath(guide.values, plot, geo, range, geo.mainHeightPx, geo.mainTopPx)
        if (path != null) drawPath(path, palette.label.copy(alpha = 0.7f), style = Stroke(width = 1f))
    }
}

/**
 * 指标线模式均线带的两条锚点水平线：灰色实线，比台阶参考线略粗一档 ——
 * 它是「现价上下最近的那条均线」这个聚合结果，不是某条指标的逐根序列，
 * 形态上必须和 [drawIndicatorGuides] 区分开。
 * 换算链与 [drawAlertLines] 一致（`toFraction` → `yOf`），超出主图量程不画。
 */
private fun DrawScope.drawBandGuides(
    guides: List<BandGuideLine>,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    guides.forEach { guide ->
        if (!guide.price.isFinite()) return@forEach
        val y = geo.yOf(range.toFraction(guide.price), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        drawLine(
            color = palette.label.copy(alpha = 0.85f),
            start = Offset(0f, y),
            end = Offset(geo.plotWidthPx, y),
            strokeWidth = 1.5f,
        )
    }
}

/**
 * 已有的告警线，一次一排。
 *
 * 与 [drawLastPrice] 用同一条换算链（`toFraction` → `yOf`），所以线所在的高度与右侧
 * 价格标上的数字严格对应。量程是被缩放过/平移过的，超出主图区就不画 —— 一条跑到读数带
 * 或时间轴上的虚线只会让人以为刻度坏了。
 *
 * 只有正在拖的那根是不透明实色：其余各根属于「已经定好的参照」，压淡一档才不跟 K 线抢视线。
 */
private fun DrawScope.drawAlertLines(
    lines: List<AlertPriceLine>,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    lines.forEach { line ->
        if (!line.price.isFinite()) return@forEach
        val y = geo.yOf(range.toFraction(line.price), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        drawLine(
            color = if (line.dragging) palette.crosshair else palette.crosshair.copy(alpha = STATIC_ALERT_ALPHA),
            start = Offset(0f, y),
            end = Offset(geo.plotWidthPx, y),
            strokeWidth = if (line.dragging) 2f else 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
    }
}

private fun DrawScope.drawSubPane(
    series: ChartSeries,
    pane: SubPaneData,
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
    paneIndex: Int,
) {
    val top = geo.subTopOf(paneIndex)
    val height = geo.subHeightPx
    pane.bars?.let { bars ->
        val width = geo.bodyWidth(plot.visibleBars)
        val base = geo.yOf(range.toFraction(0.0.coerceIn(range.low, range.high)), top, height)
        for (i in plot.indices) {
            val value = bars.getOrNull(i) ?: continue
            if (value.isNaN()) continue
            val y = geo.yOf(range.toFraction(value), top, height)
            val candle = series.candles.getOrNull(i)
            val color = if (candle == null || candle.close >= candle.open) palette.up else palette.down
            drawRect(
                color = color.copy(alpha = 0.8f),
                topLeft = Offset(geo.xOf(i, plot) - width / 2f, min(y, base)),
                size = androidx.compose.ui.geometry.Size(width, max(1f, abs(base - y))),
            )
        }
    }
    pane.lines.forEach { line ->
        val path = linePath(line.values, plot, geo, range, height, top)
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 1.2f))
    }
}

private fun DrawScope.drawCrosshair(
    crosshair: Crosshair?,
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
) {
    val mark = crosshair ?: return
    if (mark.index !in plot.indices) return
    val x = geo.xOf(mark.index, plot)
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
    plot: PlotGeometry,
    geo: ChartGeo,
    range: ValueRange,
    height: Float,
    top: Float,
): Path? {
    var path: Path? = null
    for (i in plot.indices) {
        val value = values.getOrNull(i) ?: continue
        if (value.isNaN()) continue
        val x = geo.xOf(i, plot)
        val y = geo.yOf(range.toFraction(value), top, height)
        val current = path
        if (current == null) path = Path().apply { moveTo(x, y) } else current.lineTo(x, y)
    }
    return path
}

private fun bandPath(
    upper: DoubleArray,
    lower: DoubleArray,
    plot: PlotGeometry,
    geo: ChartGeo,
    range: ValueRange,
): Path? {
    val points = plot.indices.filter {
        !upper.getOrElse(it) { Double.NaN }.isNaN() && !lower.getOrElse(it) { Double.NaN }.isNaN()
    }
    if (points.size < 2) return null
    return Path().apply {
        points.forEachIndexed { order, i ->
            val x = geo.xOf(i, plot)
            val y = geo.yOf(range.toFraction(upper[i]), geo.mainTopPx, geo.mainHeightPx)
            if (order == 0) moveTo(x, y) else lineTo(x, y)
        }
        points.asReversed().forEach { i ->
            lineTo(
                geo.xOf(i, plot),
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
    val style = rememberAxisTextStyle()
    val measurer = rememberTextMeasurer()
    val labelHeightPx = remember(style, density) {
        measurer.measure(AnnotatedString("0"), style, density = density).size.height.toFloat()
    }
    val minGapPx = labelHeightPx + with(density) { AXIS_LABEL_MIN_GAP_DP.dp.toPx() }

    var lastDrawnY = Float.NEGATIVE_INFINITY
    range.gridLines().forEach { value ->
        val y = geo.yOf(range.toFraction(value), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        // 必须取绝对值：gridLines 按数值升序，而像素 y 随数值增大而减小（屏幕 y 轴向下）。
        // 不取绝对值的话 y - lastDrawnY 恒为负，除第一条外全被当成「挨太近」丢掉。
        if (abs(y - lastDrawnY) < minGapPx) return@forEach
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
 * 十字光标处的价格标：贴在右侧价格轴上、与手指同高。
 *
 * 取值走 [ValueRange.fromFraction]——正是 `drawCandles` 里 `toFraction` 的逆运算，
 * 所以标上的数字与横线所指的价位严格一致，不会因为刻度对齐而差一格。
 *
 * 只画在 [CrosshairPriceBadge] 的主图范围内，与 `drawCrosshair` 里横线的显示条件保持一致：
 * 手指落到副图上时横线不画，这里也不该冒出一个孤立的读数。
 */
@Composable
private fun CrosshairPriceBadge(
    crosshair: Crosshair?,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    val mark = crosshair ?: return
    PriceTag(
        yPx = mark.yPx,
        range = range,
        geo = geo,
        density = density,
        tickSize = tickSize,
        modifier = modifier,
    )
}

/**
 * 告警线的价格标。与十字光标的价格标同一套定位，只是 y 由**已保存的价格**反算 ——
 * 原始的落点像素并没有意义（量程可能已经被缩放/平移过），价格才是唯一真相。
 */
@Composable
private fun AlertLinePriceBadge(
    price: Double?,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    if (price == null || !price.isFinite()) return
    PriceTag(
        yPx = geo.yOf(range.toFraction(price), geo.mainTopPx, geo.mainHeightPx),
        range = range,
        geo = geo,
        density = density,
        tickSize = tickSize,
        modifier = modifier,
    )
}

/**
 * 最新价标签：贴在右侧价格轴上、与最新价那条虚线同高同色。
 *
 * 换算链与 `drawLastPrice` 完全一致（`toFraction` → `yOf`），显示条件也一致 ——
 * 量程被缩放/平移把最新价挤出主图时，虚线与标签一起消失，不会剩一个没有线可指的
 * 孤立数字。标签底色取涨跌色，用的还是画蜡烛那两支颜色，所以它与虚线永远同色。
 */
@Composable
private fun LastPriceBadge(
    series: ChartSeries,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    upColor: Color,
    downColor: Color,
    modifier: Modifier = Modifier,
) {
    val last: Kline = series.candles.lastOrNull() ?: return
    val y = geo.yOf(range.toFraction(last.closeDouble()), geo.mainTopPx, geo.mainHeightPx)
    if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return
    PriceTag(
        yPx = y,
        range = range,
        geo = geo,
        density = density,
        tickSize = tickSize,
        accent = if (last.close >= last.open) upColor else downColor,
        modifier = modifier,
    )
}

/**
 * 贴在右侧价格轴上、与 [yPx] 同高的价格标。
 *
 * 取值走 [ValueRange.fromFraction]——正是 `drawCandles` 里 `toFraction` 的逆运算，
 * 所以标上的数字与横线所指的价位严格一致，不会因为刻度对齐而差一格。
 */
@Composable
private fun PriceTag(
    yPx: Float,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
    /**
     * 标签底色。默认中性墨色 —— 十字光标与告警线都跟涨跌无关；
     * 最新价传涨跌色，让它与同高的那条虚线一眼能对上。
     */
    accent: Color? = null,
) {
    val bottom = geo.mainTopPx + geo.mainHeightPx
    // 落到副图上时不画：那里既没有横线，冒一个孤立读数只会误导
    if (yPx > bottom) return
    val y = yPx.coerceIn(geo.mainTopPx, bottom)
    val fraction = if (geo.mainHeightPx > 0f) (y - geo.mainTopPx) / geo.mainHeightPx else 0f
    val price = range.fromFraction(fraction)
    if (!price.isFinite()) return

    val colors = MarketTheme.colors
    val style = rememberAxisTextStyle()
    val text = PriceFormatter.localeNumber(price, PriceFormatter.decimalsFor(tickSize))
    val measurer = rememberTextMeasurer()
    val textSize = remember(text, style, density) {
        measurer.measure(AnnotatedString(text), style, density = density).size
    }
    val padH = with(density) { PRICE_BADGE_PAD_H_DP.dp.toPx() }
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    Text(
        text = text,
        modifier = modifier
            .offset(
                x = with(density) { geo.plotWidthPx.toDp() },
                // 以横线为中心垂直居中：先退掉文字高度的一半，再退掉上内边距
                y = with(density) { (y - textSize.height / 2f - padV).toDp() },
            )
            .clip(Radius.xsShape)
            .background(accent ?: colors.ink)
            .padding(horizontal = with(density) { padH.toDp() }, vertical = with(density) { padV.toDp() }),
        style = style,
        color = colors.paper,
        maxLines = 1,
    )
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
 * 副图的右侧纵轴刻度。
 *
 * 每块副图自己的名字与参数（`MACD(12,26,9)`）不再画在这里 —— 它已经并进左上角
 * 那条读数行了（见 [IndicatorReadout]）。刻度只在每块副图自己的高度里取 2 条，
 * 避免小块副图被数字塞满。
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
    val style = rememberAxisTextStyle()
    val measurer = rememberTextMeasurer()
    val labelHeightPx = remember(style, density) {
        measurer.measure(AnnotatedString("0"), style, density = density).size.height.toFloat()
    }
    val minGapPx = labelHeightPx + with(density) { AXIS_LABEL_MIN_GAP_DP.dp.toPx() }

    panes.forEachIndexed { index, _ ->
        val range = ranges.getOrNull(index) ?: return@forEachIndexed
        val top = geo.subTopOf(index)
        // 取首尾两条刻度：副图块普遍矮，画满会糊；挨太近时同样丢掉下面那条
        // （间距判断同样必须取绝对值，理由见 PriceAxisLabels）
        val lines = range.gridLines(count = 2)
        val picks = listOfNotNull(lines.firstOrNull(), lines.lastOrNull()).distinct()
        var lastDrawnY = Float.NEGATIVE_INFINITY
        picks.forEach { value ->
            val y = geo.yOf(range.toFraction(value), top, geo.subHeightPx)
            if (abs(y - lastDrawnY) < minGapPx) return@forEach
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
    plot: PlotGeometry,
    geo: ChartGeo,
    interval: CandleInterval,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val window = plot.indices
    val count = window.count()
    if (count <= 1 || geo.plotWidthPx <= 1f) return

    // 与纵轴同字号：横纵刻度是一套视觉语言，不该一边 9sp 一边 11sp。
    val style = rememberAxisTextStyle()
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
            add(geo.xOf(i, plot) to candle.openTime)
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

/**
 * 一行彩色指标读数（币安式：`MA5: 84,647.20  MA10: …  BOLL.UP: …`）。
 * 每个片段与它所描述的那条折线同色，于是「这个数字属于哪条线」不必再靠一整块图例来说明。
 *
 * 用 [androidx.compose.ui.text.AnnotatedString] 而不是 `Row` + 一堆 `Text`：
 * 纯文字不吃指针事件，读数带因此不会把顶部那一带的平移/长按手势从图表手里抢走。
 */
@Composable
private fun IndicatorReadout(
    segments: List<ReadoutSegment>,
    palette: ChartPalette,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    // 读数带压到单行/族：labelSmall(11sp) 下 BOLL 三条就换行，把绘图区往下顶、还盖住上方蜡烛。
    // 字号再降一档、收紧行高并收掉字距，让每一族指标稳定占一行（多族则各占一行，见调用处）。
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = READOUT_FONT_SIZE,
        lineHeight = READOUT_LINE_HEIGHT,
        letterSpacing = 0.sp,
    )
    Text(
        text = remember(segments, palette) {
            buildAnnotatedString {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) append("  ")
                    withStyle(SpanStyle(color = palette.roleColor(segment.role))) {
                        append(segment.text)
                    }
                }
            }
        },
        modifier = modifier,
        style = style,
        maxLines = READOUT_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 单指平移、双指缩放、长按出十字光标，全在一个手势循环里判定。
 * 不用 detectTransformGestures：它与自定义长按分属两个 pointerInput 链节点，
 * 抢占事件时行为依赖链顺序，缩放手势一旦改版就会被静默打断。
 *
 * 手势分区（按**手指起点**判定，不是靠主方向猜）：
 * - **单指横向**（起点在绘图区）→ 平移时间轴（看更早/更晚）；
 * - **单指纵向**（起点在绘图区）→ 平移价格刻度（手指上滑 = 看更低价区）；
 * - **单指纵向**（起点在右侧价格刻度区）→ 缩放价格刻度，**向上拖 = K 线变高**；
 * - **双指横向张合**（任意位置）→ 缩放时间轴，拉开 = 放大；
 * - **双指纵向张合**（两指都在绘图区）→ 缩放价格量程，拉开 = K 线变高；
 * - **长按** → 十字光标。
 *
 * 价格刻度区的纵向拖动必须**按起点**区分，不能按主方向：同一个纵向位移，
 * 落在绘图区里是平移、落在刻度上是缩放，两者语义完全不同。用起点判定还顺带
 * 解决了「在刻度区横向划一下」——那种手势在这里没有意义，直接不响应。
 *
 * 双指缩放**只走一个轴**，和单指一样先定轴再锁死：横向间距的变化量与纵向间距的变化量
 * 各自累积，谁先够 [ZOOM_LOCK_PX] 就锁定谁，整场手势只缩放那一个轴，绝不叠加。
 * 叠加的代价是「用户控制不住」——上下拉开时手指不可能完全垂直对齐，带出的横向间距变化
 * 会把时间轴一起缩了，两个轴同时动。纵向限定「两指都在绘图区」，
 * 让刻度区里的捏合保持只缩放时间轴的旧行为。
 *
 * 单指的方向判定加了 [DIRECTION_LOCK_PX] 的锁定阈值：手指刚按下时是斜着动的，
 * 若逐帧同时应用横/纵位移，图会一边平移一边乱缩放。先动够阈值再锁定一个方向。
 *
 * 划线模式（[alertLineMode]）下单指纵向改判为「拖告警线」：既不平移刻度也不出十字光标，
 * **且不需要长按**——划线是这个模式下唯一的目的，再要求长按只是多余的一步。
 * 双指缩放保持不变：画线时同样要能缩放看清细节。
 */
private suspend fun PointerInputScope.detectChartGestures(
    longPressMs: Long,
    touchSlop: Float,
    /**
     * 绘图区几何用**取值函数**而不是三个 Float 入参。
     *
     * 入参是启动那一刻的定值：这个协程的 key 只有「划线模式」，尺寸变了不会重启，
     * 用定值就会拿旧尺寸换算落点（旋屏后划线直接跑偏）。取函数每次事件都读实时值。
     */
    geo: () -> ChartGeo,
    /** 划线模式：单指拖动直接移动告警线（见上方说明）。 */
    alertLineMode: Boolean,
    /** 已有告警线的像素落点，按下那一刻用它就近抓取。 */
    alertLineAnchors: () -> List<AlertLineAnchor>,
    /** 抓住已有线的容差（像素）。超出就当「在这里新建一根」。 */
    alertGrabPx: Float,
    /**
     * 右上角删除垃圾桶的命中矩形（划线模式下非空），用**取值函数**读实时画布尺寸。
     * 拖线松手时手指落在其中 → 改调 [onAlertLineDelete] 而不是落库。
     */
    alertDeleteRect: () -> Rect?,
    /** 划线过程中回调「抓到的线 id（null = 新建）」与未换算的 y 像素。 */
    onAlertLineDrag: (Long?, Float) -> Unit,
    /** 手指离开且本场手势划过线时回调一次，带同一 id，调用方据此落库。 */
    onAlertLineCommit: (Long?) -> Unit,
    /** 手指带着线悬在垃圾桶上又离开时回调一次，带同一 id，调用方据此删除规则。 */
    onAlertLineDelete: (Long?) -> Unit,
    /** 悬停状态变化：true = 手指正把线停在垃圾桶上方，浮层据此高亮给出反馈。 */
    onAlertLineOverTrash: (Boolean) -> Unit,
    /** 每次新手势按下时回调一次，用于清掉上一场手势遗留的累积余量。 */
    onGestureStart: () -> Unit,
    /** 每次手势结束时回调一次，用于把手势期间冻结的量交还给实时值。 */
    onGestureEnd: () -> Unit,
    onPan: (deltaPx: Float) -> Unit,
    onZoom: (barFactor: Float, anchorX: Float) -> Unit,
    onPriceZoom: (factor: Float) -> Unit,
    onPricePan: (deltaFraction: Float) -> Unit,
    onCrosshair: (Offset?) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        // 起点落在右侧价格刻度区：纵向拖动在那里是「缩放价格刻度」而不是「平移」。
        // 必须在按下时就定性并整场保持 —— 按主方向判会让同一段位移的语义随手指抖动切换。
        val onPriceAxis = first.position.x >= geo().plotWidthPx
        // 起点落在副图区：那里的纵向拖动与主图无关，既不该平移主图价格，也不该被
        // 图表吃掉 —— 让给父级滚动容器，页面照常上下翻。与 onPriceAxis 一样在按下时
        // 定性、整场保持。
        val plot0 = geo()
        val onSubPane = !onPriceAxis &&
            first.position.y >= plot0.plotTopPx + plot0.mainHeightPx
        // 立刻消费 down：图表落在 verticalScroll 的父容器里，
        // 不抢占的话父容器会先开一个滚动手势，纵向拖动就变成翻页而不是调刻度。
        // 副图区起笔则反着来 —— 翻页正是我们要的，down 留给父容器。
        // 划线模式例外：那时单指只服务于告警线，在哪儿起笔都必须抢。
        if (!onSubPane || alertLineMode) first.consume()
        // 新手势开始：把上一场遗留的「不足一根」零头清掉，
        // 否则上一场拖到一半松手，零头会算进下一场，手感上多出一小段跳动。
        onGestureStart()
        // 自己记下每个指头上一次的位置：不依赖 positionChange，它在不同版本里签名动过
        val lastPositions = mutableMapOf<Any, Offset>()
        var travelled = 0f
        var previousDistanceX = 0f
        var previousDistanceY = 0f
        /** 本场双指手势锁定的缩放轴；`null` = 还没定性。定性后整场只缩放这一个轴。 */
        var zoomAxis: ChartGesture.Axis? = null
        var zoomAccumX = 0f
        var zoomAccumY = 0f
        /** 上一帧的按下指头数：指头增减时旧间距与已定性的缩放轴都作废。 */
        var pointerCount = 0
        var longPressActive = false
        /** 本场手势是否真的划过线。没划过就不该弹确认框。 */
        var alertLineActive = false
        /** 本场手势锁定的那条线；`null` = 新建一根。判定只做一次，整场不再改。 */
        var alertGrabbedId: Long? = null
        /** 松手那一刻手指是否悬在右上角垃圾桶上 —— 是则删除这条线而非落库。 */
        var alertOverTrash = false
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
            // 每次事件都先消费，避免父滚动容器把纵向位移吃掉。
            // 例外：副图区起笔的单指手势 —— 它的纵向位移本就归页面（上下滑翻整页）。
            // 一旦锁定横向（要平移时间轴）、进入划线 / 长按十字光标 / 双指缩放，
            // 说明这场的用途已经定性回图表，立刻收回消费权。
            val handToPage = onSubPane && !alertLineMode && !longPressActive &&
                pressed.size < 2 && axis != ChartGesture.Axis.HORIZONTAL
            if (!handToPage) pressed.forEach { change -> change.consume() }

            // 指头数一变（1↔2），上一场的两指间距与已定性的缩放轴都失去意义。
            // 不丢的话，从双指抬成单指再按下第二根时，会拿旧间距算出因子，跳一大格。
            if (pressed.size != pointerCount) {
                pointerCount = pressed.size
                previousDistanceX = 0f
                previousDistanceY = 0f
                zoomAxis = null
                zoomAccumX = 0f
                zoomAccumY = 0f
            }

            // 划线分支必须排在十字光标判定之前：这个模式下不出十字光标
            if (alertLineMode && pressed.size == 1) {
                val plot = geo()
                val raw = primary.position
                val y = raw.y.coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                if (!alertLineActive) {
                    // 抓哪根只在按下时定一次。逐帧重判的话，把手里的线拖过另一根时
                    // 会突然「换手」——手指没动，动的却是另一条预警的阈值。
                    alertGrabbedId = alertLineAnchors().closestAlertLine(y, alertGrabPx)
                    alertLineActive = true
                }
                // 命中判定用**未夹取的原始坐标**（真实手指位置），而不是跟手用的夹取 y：
                // 垃圾桶锚在绘图区右上角，按真实落点判定最直观。线仍照常跟手 + 垃圾桶高亮。
                val overTrash = alertDeleteRect()?.contains(raw) == true
                if (overTrash != alertOverTrash) {
                    alertOverTrash = overTrash
                    onAlertLineOverTrash(overTrash)
                }
                onAlertLineDrag(alertGrabbedId, y)
                continue
            }
            if (!longPressActive && pressed.size == 1) {
                val held = primary.uptimeMillis - first.uptimeMillis
                if (held >= longPressMs && travelled <= touchSlop) longPressActive = true
            }
            if (longPressActive) {
                onCrosshair(primary.position)
                continue
            }
            if (pressed.size >= 2) {
                // 双指缩放**只走一个轴**：横向间距驱动时间轴，纵向间距驱动价格量程，
                // 但两者不会同时生效 —— 先定性再锁死，整场手势只缩放定下来的那个轴。
                //
                // 允许叠加的话，上下拉开时手指不可能完全垂直对齐，带出的那点横向间距
                // 变化会顺手把时间轴也缩了，两个轴一起动，用户根本控制不住（这就是
                // 「无论上下拉还是左右拉，动的都是横坐标」的老毛病换个形式回来）。
                val plot = geo()
                val dx = abs(pressed[0].position.x - pressed[1].position.x)
                val dy = abs(pressed[0].position.y - pressed[1].position.y)
                // 首帧只建立基准（此时 previousDistance 已被指头数变化清成 0），不算因子
                if (previousDistanceX > 0f && previousDistanceY > 0f) {
                    zoomAccumX += dx - previousDistanceX
                    zoomAccumY += dy - previousDistanceY
                    if (zoomAxis == null) {
                        val onPlot = pressed.all { it.position.x < plot.plotWidthPx }
                        val locked = ChartGesture.axisLock(zoomAccumX, zoomAccumY, ZOOM_LOCK_PX)
                        // 纵向缩放只在两指都落在绘图区时可用；刻度区里的捏合只能是横向
                        zoomAxis = if (locked == ChartGesture.Axis.VERTICAL && !onPlot) {
                            ChartGesture.Axis.HORIZONTAL
                        } else {
                            locked
                        }
                    }
                    when (zoomAxis) {
                        ChartGesture.Axis.HORIZONTAL -> ChartGesture
                            .pinchFactor(previousDistanceX, dx)
                            ?.let { factor ->
                                val anchorX = (pressed[0].position.x + pressed[1].position.x) / 2f
                                onZoom(factor, anchorX.coerceIn(0f, plot.plotWidthPx))
                            }

                        ChartGesture.Axis.VERTICAL ->
                            ChartGesture.pinchFactor(previousDistanceY, dy)?.let(onPriceZoom)

                        null -> Unit
                    }
                }
                previousDistanceX = dx
                previousDistanceY = dy
                continue
            }
            val deltaX = primary.position.x - previous.x
            val deltaY = primary.position.y - previous.y
            if (deltaX != 0f || deltaY != 0f) {
                travelled += abs(deltaX) + abs(deltaY)
                // 方向锁：累计位移够阈值后才定性，定性后整场手势不再改
                if (axis == null) {
                    axisAccumX += deltaX
                    axisAccumY += deltaY
                    axis = ChartGesture.axisLock(axisAccumX, axisAccumY, DIRECTION_LOCK_PX)
                }
                when (axis) {
                    ChartGesture.Axis.HORIZONTAL -> if (!onPriceAxis && abs(deltaX) > 0.1f) {
                        onPan(deltaX)
                    }

                    ChartGesture.Axis.VERTICAL -> {
                        val height = geo().mainHeightPx
                        if (height > 0f && abs(deltaY) > 0.1f) {
                            if (onPriceAxis) {
                                // 刻度区：向上拖（deltaY < 0）→ 因子 < 1 → 量程变小 → K 线变高
                                ChartGesture.priceScaleFactor(deltaY, height)?.let(onPriceZoom)
                            } else if (!onSubPane) {
                                // 绘图区：手指下滑 = 量程下移（看更低价区），因此取正号。
                                // 副图区（onSubPane）图表什么都不做：那场纵向位移
                                // 没被消费，父滚动容器已接手翻页。
                                onPricePan(deltaY / height)
                            }
                        }
                    }

                    null -> Unit
                }
            }
        }
        if (alertLineActive) {
            // 松手在垃圾桶上 = 删除这条线（id 为 null 时等于放弃一笔未落库的新线），
            // 否则照旧落库。两种情况收尾都要清掉高亮，别让它留在下一次手势上。
            if (alertOverTrash) onAlertLineDelete(alertGrabbedId) else onAlertLineCommit(alertGrabbedId)
            if (alertOverTrash) onAlertLineOverTrash(false)
        }
        onCrosshair(null)
        onGestureEnd()
    }
}

/** 一条已有告警线在主图上的像素落点，手势按它决定抓哪根。 */
internal data class AlertLineAnchor(val ruleId: Long?, val yPx: Float)

/**
 * 按 y 像素就近取一条线，超出 [slopPx] 返回 null（= 在这里新建一根）。
 *
 * 只做纵向距离：划线要改的是价格，手指横向落在哪儿无关紧要 ——
 * 判进绘图区的话，贴着线右端点一下就变成新建一根了。
 */
internal fun List<AlertLineAnchor>.closestAlertLine(yPx: Float, slopPx: Float): Long? {
    var best: Long? = null
    var bestDistance = Float.MAX_VALUE
    forEach { anchor ->
        val distance = abs(anchor.yPx - yPx)
        if (distance <= slopPx && distance < bestDistance) {
            bestDistance = distance
            best = anchor.ruleId
        }
    }
    return best
}

/** 「刻度已缩放 · 复位」小标的文案与左右内边距（用于实测宽度）。 */
private const val RESET_BADGE_TEXT = "刻度已缩放 · 复位"

/** 徽标垂直内边距（上下各一份），用于把徽标高度算准后贴绘图区下沿。 */
private const val RESET_BADGE_VERTICAL_PADDING_DP = 4f

/** 单指方向锁的累计位移阈值（px）：手指刚按下时是斜着动的，先定性再应用。 */
private const val DIRECTION_LOCK_PX = 12f

/**
 * 双指缩放的定轴阈值（两指间距的变化量，不是位移）。
 *
 * 比 [DIRECTION_LOCK_PX] 大一点：两指落下的位置本身就有抖动，
 * 阈值太小会在手指还没真正拉开时就把轴定死。
 */
private const val ZOOM_LOCK_PX = 16f

/**
 * 时间轴最多画几条。真正的条数还要受标签宽度约束（见 [TimeAxisLabels]），
 * 这个常量只是上限——否则宽屏上会一路画到糊成一片。
 */
private const val MAX_TIME_LABELS = 4

/** 时间轴相邻标签之间至少要留的水平间隙（dp）。 */
private const val TIME_LABEL_MIN_GAP_DP = 10f

/** 纵轴相邻刻度之间至少要留的垂直间隙（dp）。 */
private const val AXIS_LABEL_MIN_GAP_DP = 4f

/**
 * 单族指标读数固定一行（如一整排 MA 挤在一行、BOLL 三条挤在一行）。
 * 多族指标不再挤同一行，而是各占一行（见调用处按族拆分的 Column）；
 * 某一族过宽时宁可省略尾部，也不在本行内换行。
 */
private const val READOUT_MAX_LINES = 1

/** 指标读数带的字号：比 labelSmall(11sp) 再小一档，让整族数值稳定落在一行。 */
private val READOUT_FONT_SIZE = 9.sp

/** 右侧纵轴文字的字号：与读数带同档，刻度数字不该在视觉上与蜡烛抢地方。 */
private val AXIS_FONT_SIZE = 9.sp

/** 纵轴刻度与价格标的共用文字样式（价格轴、副图轴、十字光标/最新价/告警线标）。 */
@Composable
private fun rememberAxisTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = AXIS_FONT_SIZE,
        lineHeight = 11.sp,
        letterSpacing = 0.sp,
    )

/** 指标读数带的行高：与 [ChartGeo.READOUT_LINE_DP] 对齐，用来算读数带总高。 */
private val READOUT_LINE_HEIGHT = 12.sp

/**
 * K 线详情小条（[KlineChart] 的 candleReadout 槽）预留的高度：
 * 两行 9sp 文本（行高 11）+ 行距 2 + 与读数带之间的留白 4。
 * 小条本体是调用方传进来的 composable，画布猜不到它的尺寸，只能按约定夹表。
 */
private const val CANDLE_READOUT_BAND_DP = 28f

/** 十字光标价格标的水平内边距（dp）。 */
private const val PRICE_BADGE_PAD_H_DP = 5f

/** 十字光标价格标的垂直内边距（dp）。 */
private const val PRICE_BADGE_PAD_V_DP = 2f

/** 已有的告警线（非正在拖的那根）的不透明度：参照线不该和 K 线争视线。 */
private const val STATIC_ALERT_ALPHA = 0.45f

/** 左下角角标按钮的边长。 */
private val CORNER_BUTTON_SIZE = 26.dp

/**
 * 角标距绘图区左下角的内缩距离。取成和垃圾桶（[SPACING_TRASH_INSET]）一样的值，
 * 让「进入全屏」与「拖线删除」这两个角对称地贴在同一高度上。
 */
private val CORNER_BUTTON_INSET = 12.dp

/**
 * 划线的抓取容差：按下点离某根线在这个范围内就算抓它，否则新建一根。
 *
 * 比手指触摸半径略大 —— 屏幕上那根 1.5dp 的虚线根本按不准，
 * 容差太小会让「微调已有预警」变成「凭空多出一条新预警」。
 */
private val ALERT_GRAB_WIDTH = 24.dp

/** 划线模式右上角删除垃圾桶的边长（含圆形背景）。 */
private val TRASH_BUTTON_SIZE = 32.dp

/** 垃圾桶图标本身的大小（小于外圈背景，留出内边距）。 */
private val TRASH_ICON_SIZE = 16.dp

/** 垃圾桶距绘图区右上沿的内缩距离；必须和 alertDeleteRect() 用同一值。 */
private val SPACING_TRASH_INSET = 12.dp

/**
 * 主图右下角划线显隐「眼睛」的外圈边长。
 *
 * 内缩沿用 [SPACING_TRASH_INSET]（与右上角垃圾桶同值），大小与左下角的全屏角标
 * [CORNER_BUTTON_SIZE] 一致：眼睛是常驻控件，占的绘图区越少越好。
 */
private val EYE_BUTTON_SIZE = 26.dp

/** 眼睛图标本身的大小（小于外圈背景，留出内边距）。 */
private val EYE_ICON_SIZE = 15.dp
