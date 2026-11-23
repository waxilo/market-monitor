package com.waxilo.marketmonitor.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.closeDouble
import com.waxilo.marketmonitor.domain.model.highDouble
import com.waxilo.marketmonitor.domain.model.lowDouble
import com.waxilo.marketmonitor.domain.model.openDouble
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
import com.waxilo.marketmonitor.ui.common.EyeGlyph
import com.waxilo.marketmonitor.ui.theme.ChartLineColors
import com.waxilo.marketmonitor.ui.theme.DownRed
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import com.waxilo.marketmonitor.ui.theme.UpGreen
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 图上的一条告警价线。
 *
 * [ruleId] 为 null 表示**正在新建**、还没落库的那根。线的归属与含义（对应哪条规则、
 * 能不能改）图表一概不管，只负责画出来并把落点报给调用方 ——
 * 与 [ChartLine] 一样，这里只承载语义，不承载业务。
 */
/**
 * 图上一条**已有**的线的身份：告警线与视觉画线分属两个存储域（alert_rule / chart_line），
 * 落库与删除路径不同，所以抓到手之后必须能说清是哪一域、哪一条。
 *
 * 为什么要有这个类型：解锁态下「选中哪根就拖哪根」是**一个**手势通道，
 * 而落库回调（[KlineChart.onHorizontalDrag]）只有一条 ——
 * 靠当前工具去猜线型在这里不成立（解锁态根本没有工具）。
 *
 * 可空处只有 [Alert.ruleId]：它 null 表示「手上一笔还没落库的告警线」，
 * 与 `null` 整个 ref（= 落空、要在这里新建）是两件事。
 */
sealed interface LineRef {
    /** 告警线：[ruleId] 为 alert_rule 主键；null = 还没落库的那一笔草稿。 */
    data class Alert(val ruleId: Long?) : LineRef

    /** 视觉画线（水平线 / 直线）：[lineId] 为 chart_line 主键，0 = 草稿。 */
    data class Guide(val lineId: Long) : LineRef
}

data class AlertPriceLine(
    val ruleId: Long?,
    val price: Double,
    val dragging: Boolean = false,
    /**
     * 线上挂的备注牌（画在绘图区左端、与线同高）。
     *
     * EMA 分析画的线名字形如 `8时EMA50`（面板靠它回显），用户不写备注就认不出图上
     * 哪根红线是哪一档 —— 所以自动名直接充当备注；用户在编辑栏写的 [AlertRule.note]
     * 优先于自动名。null = 不挂牌。
     */
    val label: String? = null,
)

/**
 * 全屏右侧竖栏选中的划线工具。
 *
 * 一次只激活一种：单指手势在同一时刻只能有一种语义（拖价格线 / 拖水平线 / 落直线锚点），
 * 这与当年「划线开关」一个布尔的处境相同，只是把「划什么」从隐式变成显式单选。
 *
 * **没有「删除」这一档了**（2026-10-24 用户改）：删除曾经有三条路 ——
 * 拖到画布右上角的垃圾桶（2026-10-12 撤）、拖到右侧竖栏松手（2026-10-24 撤）、
 * 点竖栏垃圾桶再点线（这一档，同批撤）。三条都要用户先记住「删除住在哪」，
 * 而拖动本身是「改线」的手势，改到一半被当成删除是反直觉的。
 * 现在只剩一条：**点一下线把它选中**，右侧竖栏当场变成这条线的编辑栏（备注 / 删除）。
 * 选中态是手势通道的前提（见 [KlineChart.linesEditable]），删除于是落在「我已经
 * 指着这条线了」这个明确的上下文里。
 */
enum class DrawTool { NONE, ALERT, HORIZONTAL, TREND }

/**
 * 直线的一个端点：**分数蜡烛下标**锚 + 价格原始值。
 *
 * 存下标而不是像素 —— 下标随视窗平移/缩放自然落位（换算链与蜡烛同一个 `xOf`），
 * 时间戳→下标的折算由调用方按首根蜡烛开盘时间与周期完成（K 线是等间隔序列）。
 */
data class TrendAnchor(val index: Double, val price: Double)

/**
 * 图上的一条纯视觉画线（划线工具里的「水平线/直线」），由调用方折算成位置语言后喂给图表。
 *
 * [id] 为 0 表示**正在新建、还没落库**的那根（与 [AlertPriceLine.ruleId] 的 null 同语义，
 * 但这里统一用 0 —— 三种线型共用一条回调链，可空 Long 反而别扭）。
 * 图表只管画出来、把手势落点报给调用方落库，与告警线一样不承载业务。
 */
data class DrawGuideLine(
    val id: Long,
    val kind: ChartLineKind,
    /** 水平线价位；直线为 NaN。 */
    val price: Double = Double.NaN,
    val anchor1: TrendAnchor? = null,
    val anchor2: TrendAnchor? = null,
    val dragging: Boolean = false,
)

/**
 * 落库画线 → 图表位置语言：绝对时间戳按「首根蜡烛开盘时间 + 周期」折回分数下标。
 *
 * 每次现算、不缓存：序列头部一变（翻页前插历史、切周期）下标就要整体重折 ——
 * 这正是持久化只存时间戳的原因。序列为空或数据缺项返回 null，绘制端跳过该条。
 */
fun ChartLine.toGuideLine(candles: List<Kline>, interval: CandleInterval): DrawGuideLine? {
    val first = candles.firstOrNull()?.openTime ?: return null
    val stepMs = interval.minutes * 60_000L
    if (stepMs <= 0L) return null
    fun indexAt(t: Long): Double = (t - first).toDouble() / stepMs
    return when (kind) {
        ChartLineKind.HORIZONTAL -> {
            val p = price ?: return null
            DrawGuideLine(id = id, kind = kind, price = p.toDouble())
        }

        ChartLineKind.TREND -> {
            val t1 = t1 ?: return null
            val v1 = p1 ?: return null
            val t2 = t2 ?: return null
            val v2 = p2 ?: return null
            DrawGuideLine(
                id = id,
                kind = kind,
                anchor1 = TrendAnchor(indexAt(t1), v1.toDouble()),
                anchor2 = TrendAnchor(indexAt(t2), v2.toDouble()),
            )
        }
    }
}

/**
 * 拖动预览 → 可落库画线（[toGuideLine] 的逆链）。
 *
 * 下标当场折算成**绝对时间戳**后就与「当时那份序列」脱钩：草稿期间翻页前插历史，
 * 也不会把线拖歪。序列未到齐（空）或落点非有限时返回 null（丢弃这一帧）。
 */
fun DrawGuideLine.toChartLine(
    market: MarketType,
    symbol: String,
    candles: List<Kline>,
    interval: CandleInterval,
    createdAt: Long,
    /**
     * 这条线原有的备注。拖动是整行 upsert（[ChartLineRepository.save]），
     * 这里不带上就是把用户写的备注一并抹掉 —— 调用方从既有行里取来传入。
     */
    note: String? = null,
): ChartLine? {
    val first = candles.firstOrNull()?.openTime ?: return null
    val stepMs = interval.minutes * 60_000L
    if (stepMs <= 0L) return null
    fun timeAt(index: Double): Long = first + (index * stepMs).roundToLong()
    return when (kind) {
        ChartLineKind.HORIZONTAL -> {
            if (!price.isFinite()) return null
            ChartLine(
                id = id,
                market = market,
                symbol = symbol,
                kind = kind,
                price = price.toBigDecimal(),
                note = note,
                createdAt = createdAt,
            )
        }

        ChartLineKind.TREND -> {
            val a1 = anchor1 ?: return null
            val a2 = anchor2 ?: return null
            if (!a1.price.isFinite() || !a2.price.isFinite()) return null
            ChartLine(
                id = id,
                market = market,
                symbol = symbol,
                kind = kind,
                t1 = timeAt(a1.index),
                p1 = a1.price.toBigDecimal(),
                t2 = timeAt(a2.index),
                p2 = a2.price.toBigDecimal(),
                note = note,
                createdAt = createdAt,
            )
        }
    }
}

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
 * 图表的整份「用户意图」：横向视窗（[viewport]）+ 纵向刻度（[priceZoom] / [pricePan]）。
 *
 * 在**本页内**跨周期、跨竖屏/全屏保持；跨会话只继承宽度与纵向刻度 ——
 * 横向位置（回看历史）退出详情页即作废。图表自身不落盘：实例首帧从
 * [KlineChart.initialView] 取值，之后每次变化经 `onViewChange` 交还调用方
 * （详情页由 ViewModel 同步持有 + 防抖写 DataStore）。
 */
data class ChartViewState(
    val viewport: ChartViewport = ChartViewport.initial(),
    /** 纵向缩放倍数（1f = 自动量程）；语义与 [KlineChart] 内部同名状态一致。 */
    val priceZoom: Float = 1f,
    /** 纵向平移（占主图高度的比例，0f = 不偏移）。 */
    val pricePan: Float = 0f,
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
     * 标的身份（如 `BTCUSDT`）。只用于复位**过程状态**（十字光标、手势中的价格基准，
     * 都挂在 `(symbolKey, interval)` 的 remember 上）：下标换到另一条序列后指向的
     * 就是另一根蜡烛了。视角（[initialView]）不在此列 —— 它在本页内保持。
     */
    symbolKey: String = "",
    /**
     * 视角初值：看多宽、右端停哪、纵向刻度。**只在实例首帧生效** ——
     * 图表只会在 K 线就绪后组合，彼时调用方的设置早已恢复完毕；
     * 之后视角归图表自己持有，换标的/换周期都不复位，变化经 [onViewChange] 交回。
     */
    initialView: ChartViewState = ChartViewState(),
    /** 视角每有变化（手势、复位小标）就回调一次，调用方负责落盘与两个实例间的交接。 */
    onViewChange: (ChartViewState) -> Unit = {},
    upColor: Color = UpGreen,
    downColor: Color = DownRed,
    /**
     * 「已经拖到最老一根、还想再往更早拖」时回调（PRD FR-2.2 的翻页触发）。
     *
     * **会在拖动途中逐帧回调** —— 去重（同一个最老时间戳只请求一次、在途不重复请求）
     * 是调用方的事，图表这一侧没有序列的生命周期信息，判不了。见 `onPan` 里的说明。
     */
    onLoadMore: () -> Unit = {},
    /**
     * 该标的已有的告警价线（价格原始值），由调用方持有并保证已按标的过滤。
     * 显隐交给 [alertLinesVisible]，但**划线模式下始终绘制**：看不见自己设过的预警价、
     * 却要进到划线模式才能调，等于把线藏进了抽屉。
     */
    alertLines: List<AlertPriceLine> = emptyList(),
    /**
     * 当前激活的划线工具（全屏右侧竖栏单选）。非 NONE 时：单指只服务那一条通道，
     * **不再**平移价格刻度，也不会出十字光标；且**只新建、不抓已有线**（2026-10-14）——
     * 编辑旧线归解锁态「单击选中后拖动」，工具模式下画布同时切换成纯净态（已划的线全部让位）。
     * 直线工具下两敲落锚：拖动中十字准星跟手，松手确认定点。双指缩放照旧。
     *
     * [DrawTool.NONE] 下还能不能动已有的线，由 [linesEditable] 单独说了算 ——
     * 「没选工具」与「图只读」是两件事：解锁后不选工具也照样能拖线。
     */
    drawTool: DrawTool = DrawTool.NONE,
    /**
     * 已落库（或正在新建预览）的视觉画线，由调用方按标的过滤并折算成下标/价格语言。
     * 显隐与 [alertLinesVisible] 那只眼睛同步；不可编时时只显不可拖 ——
     * 可不可拖由 [linesEditable] 决定（竖屏、锁定态都是只显）。
     */
    drawLines: List<DrawGuideLine> = emptyList(),
    /**
     * 划线工具（水平线/直线）拖动过程中每帧回调整根预览线（[DrawGuideLine.dragging] 恒为 true，
     * 新建的 id 为 0）。调用方把它并进 [drawLines] 显示，并在提交时读出落库。
     */
    onDrawLineDrag: (DrawGuideLine) -> Unit = {},
    /** 手指离开：调用方把当前预览线落库（id 为 0 则新建）。 */
    onDrawLineCommit: (Long) -> Unit = {},
    /**
     * 撤掉一条视觉画线（id 为 0 = 只是放弃手上那笔草稿）。
     *
     * 用户的删除走竖栏编辑栏（见 [onSelectionChange]），这里只剩图表内部一个用途：
     * **直线两敲落得太近**时把这一笔当作撤销 —— 那一下用户本来是画线，不该留下一根
     * 两个端点几乎重合、看着像小圆点的线。
     */
    onDrawLineDelete: (Long) -> Unit = {},
    /**
     * **解锁态**：`drawTool == NONE` 时也能动图上已有的线（不必先选工具）。
     *
     * 2026-10-24 起是**两拍**：先单击那条线把它**选中**（图上出外发光），
     * 再按住它拖动（告警线 / 水平线改价位、直线整根平移）。没选中的线按下去拖
     * 只是平移画布 —— 误划再也拖不走线；长按回到「出十字光标看读数」。
     * false（锁定态、竖屏）= 整张图只读，线一根都动不了，也点不出选中态 ——
     * 竖屏正是 false：那里只显不可编，改线一律进全屏。
     */
    linesEditable: Boolean = false,
    /**
     * 当前**选中**的那条线变了（null = 取消选中）。
     *
     * 选中态本身由图表持有（它同时是「这一笔手势是拖动而不是平移」的前提，见
     * [linesEditable]），但**它的用途不止于手势**：调用方要把右侧竖栏从「工具列表」
     * 换成「这条线的编辑栏」（备注 / 删除）。所以这个状态得让外面看得见 ——
     * 只在图表里捂着，外面就只能靠猜「用户现在指着哪条线」。
     */
    onSelectionChange: (LineRef?) -> Unit = {},
    /**
     * 划线是否画在图上（由右下角的小眼睛控制）。仅在非划线模式下生效 ——
     * 划线时线必须可见，否则是在拖一根看不见的线。
     *
     * 落笔之后调用方会把眼睛睁开（见 `DetailViewModel.commitDrawLine` / `commitAlertLine`）：
     * 藏线状态下新落的线在工具收起后会一起消失，看着像没画上。
     *
     * 均线那边**没有**对应的开关：MA / EMA 的整族显隐住在指标条的两枚 chip 上
     * （见 `DetailScreen.IndicatorBar`），图里再放一枚「MA」只会和它们重复。
     */
    alertLinesVisible: Boolean = true,
    /** 点右下角划线小眼睛时回调；null = 不画那只眼睛。 */
    onToggleAlertLines: (() -> Unit)? = null,
    /**
     * 划线过程中每帧回调「抓到的线 id（null = 新建）」与当前落点（价格原始值）。
     * id 在按下那一刻定好，整场手势不再改：拖过另一根时跳过去会让线瞬间失控。
     */
    onAlertLineDrag: (Long?, Double) -> Unit = { _, _ -> },
    /** 手指离开：调用方按同一 id 把新价写回那条规则（id 为 null 则新建规则）。 */
    onAlertLineCommit: (Long?) -> Unit = {},
    /** 左上角角标（进入全屏）。null 表示不画，读数带也就顶到最左。 */
    cornerAction: ChartCornerAction? = null,
    /**
     * 十字光标当前指到的 K 线下标；null = 没有十字光标。
     *
     * K 线详情（OHLC）不再浮在图上做弹窗 —— 弹窗那块字常年压着左上的蜡烛与形态。
     * 下标交给调用方，由调用方做成常显小条塞进 [candleReadout]：
     * 长按跟着手指读那一根，松手**停在**那一根；被画布上的其他操作清掉时才回到最新一根。
     */
    onCrosshairIndexChange: (Int?) -> Unit = {},
    /**
     * K 线详情小条：画在图表顶部、指标读数带的**正上方**，与读数带同一个容器 ——
     * 两组数字上下对读，它读单根 K 线、读数带读指标。占用的高度按
     * [CANDLE_READOUT_BAND_DP] 从绘图区顶部让出，不会压到蜡烛。
     */
    candleReadout: (@Composable () -> Unit)? = null,
    /**
     * 现价牌：顶部读数带**右端**（右侧价格轴正上方那截空带）的常驻现价读数，
     * 右对齐、贴读数带上沿。它只叠在读数带上，不额外吃图高 ——
     * 因此调用方要保证读数带本身有牌子的容身高度（与 [candleReadout] 同用即是）。
     */
    lastPriceBoard: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    /**
     * 视窗只存「用户意图」（看多宽、右端停哪儿）；序列长度每次查询时作为入参传入。
     *
     * 这是**本页内**的看图习惯：切周期、竖屏↔全屏都保持，初值来自 [initialView]，
     * 变化经 [onViewChange] 交回调用方（横向位置不落盘，退出详情再进回到最新一根）。
     * 因此 `remember` **不挂** key：挂上就会在换标的/换周期时把视角弹回默认，
     * 正是这次要修掉的老行为。序列长短不一没关系 —— 越界由 [ChartViewport.clamp]
     * 在每次查询时兜底，存进来的只是「意图」，不是「当前序列下的有效值」。
     */
    val barCount = series.size
    var viewport by remember { mutableStateOf(initialView.viewport) }
    var crosshair by remember(symbolKey, interval.storageKey) { mutableStateOf<Crosshair?>(null) }
    /**
     * 当前**选中**的那条线（`null` = 没选中）。选中是「可以拖」的前提。
     *
     * 交互口径（2026-10-24 用户改）：先把线**单击选中**，再按住它拖动 ——
     * 原先是「解锁后按住满长按时长才转正拖动」，两步并用同一套「时长 + slop」判定，
     * 与长按十字光标抢同一刻，且慢半秒才给反馈。改成单击选中之后，拖动是
     * 「按在已选中的那条上」的直接结果：按下去就走，不用等，也不会误拖路过的线
     * （没选中时按住拖动照旧是平移画布）。
     *
     * 状态放在图表这一侧：它只影响「这一笔手势的用途」与「线画得多亮」，
     * 既不落库也不跨标的 —— 换标的/换周期随 `remember` 的 key 作废；
     * 上锁（[linesEditable] = false）与切划线工具时由下方两个 `LaunchedEffect` 清掉。
     */
    var selectedLine by remember(symbolKey, interval.storageKey) {
        mutableStateOf<LineRef?>(null)
    }
    // 下标变化就同步给调用方的 K 线详情小条（长按换根、被其他操作清掉都走这里）
    LaunchedEffect(crosshair?.index) { onCrosshairIndexChange(crosshair?.index) }
    // 进划线工具清掉遗留的十字光标：那场手势已经是「拖线/落锚」，读数标留着会跟线上的价位标抢位置。
    // 同一刻也清选中：工具模式下单指只服务「新建」（纯净画布），留一条高亮的旧线等于说
    // 「它可以拖」——而那时它根本不可见、也拖不动。
    LaunchedEffect(drawTool) {
        if (drawTool != DrawTool.NONE) {
            crosshair = null
            selectedLine = null
        }
    }
    // 上锁 = 整张图只读：选中态（「这条线可以拖」的承诺）必须跟着作废，
    // 否则解锁后一按就拖，看着像锁定没生效。
    LaunchedEffect(linesEditable) { if (!linesEditable) selectedLine = null }
    // 选中态同步给调用方：右侧竖栏要据此在「工具列表」与「这条线的编辑栏」之间切换。
    // 走 rememberUpdatedState 包一层，免得调用方换了 lambda 之后这里还留着旧的。
    val selectionSink by rememberUpdatedState(onSelectionChange)
    LaunchedEffect(selectedLine) { selectionSink(selectedLine) }
    /**
     * 纵向缩放倍数（相对主图原始量程）。1f = 自动量程。
     * 手指在**价格轴一侧**上下拖会改它 —— 这是「拉伸/压缩金额刻度」的手势。
     *
     * 与 [viewport] 同为全局看图习惯（同样**不挂**序列 key）：换标的/换周期保持，
     * 初值来自 [initialView]，变化经 [onViewChange] 落盘。**切周期时只重定位置
     * （[pricePan]），不重算这个倍数** —— 重算等于把缩放手感抹掉（见下面 recenter 注释）。
     * 平移量按原始量程的比例折算，放到别的标的上本不是同一回事 —— 但用户选的就是全局口径，
     * 真不合适时图上常驻的「复位」小标一键清掉。
     */
    var priceZoom by remember { mutableFloatStateOf(initialView.priceZoom) }
    /** 纵向平移（占主图高度的比例）。手指上滑看更低价区。 */
    var pricePan by remember { mutableFloatStateOf(initialView.pricePan) }
    /**
     * 视角每变一帧就交回调用方：状态更新**同步**（竖屏↔全屏切换时，新实例的
     * 首帧初值读的就是它），落盘由调用方防抖。
     * `drop(1)` 滤掉首帧的初值回声 —— 那个值本来就是调用方喂进来的。
     */
    val viewCommit by rememberUpdatedState(onViewChange)
    LaunchedEffect(Unit) {
        snapshotFlow { ChartViewState(viewport, priceZoom, pricePan) }
            .drop(1)
            .collect { viewCommit(it) }
    }
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
     * 因此可以直接数出来 —— 加载中的骨架图用同一套口径（见 [mainReadoutLineCount]）。
     */
    val mainReadoutLines = remember(series) { series.mainReadoutLineCount() }
    // 小条与读数带同容器：它的高度也计进读数带，绘图区整体下移
    val readoutHeightDp = chartReadoutBandDp(mainReadoutLines, candleReadout != null)
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
    /**
     * 可见窗口的最高 / 最低价（币安式极值标注）。跟着窗口走 —— 平移或捏合换了一批
     * K 线，标就换成新窗口的极值，回答的是「眼前这段的高低」。
     */
    val extremes = remember(series, dataWindow) { series.extremeMarks(dataWindow) }
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
     * 右侧价格列宽度：按这一列里最宽的文本量（见 [rememberAxisColumnWidth]）。
     *
     * 传**自动量程**而不是叠加纵轴缩放后的 `mainRange`：后者一变（捏合纵轴）步长就变，
     * 列宽跟着逐帧抖，整张图会横向跳；自动量程只跟可见数据窗口走，缩放纵轴时纹丝不动。
     */
    val axisWidthPx = rememberAxisColumnWidth(
        range = autoRange,
        lastPrice = series.candles.lastOrNull()?.closeDouble(),
        subRanges = subRanges,
        subPanes = series.subPanes,
        tickSize = tickSize,
        canvasWidthPx = canvasSize.width.toFloat(),
        density = density,
    )
    val geo = remember(canvasSize, density, series.subPanes.size, readoutHeightDp, axisWidthPx) {
        ChartGeo.of(canvasSize, density, series.subPanes.size, readoutHeightDp, axisWidthPx)
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
     * 手势协程在 `pointerInput` 启动那一刻把回调闭包一起捕获，之后不会更新。
     * 量程与划线回调都必须读**实时**值，否则量程一变（缩放/平移后）划线价位就按旧量程换算。
     */
    val alertRange by rememberUpdatedState(mainRange)
    val alertDrag by rememberUpdatedState(onAlertLineDrag)
    val alertCommit by rememberUpdatedState(onAlertLineCommit)
    val liveAlertLines by rememberUpdatedState(alertLines)
    val drawDrag by rememberUpdatedState(onDrawLineDrag)
    val drawCommit by rememberUpdatedState(onDrawLineCommit)
    val drawDelete by rememberUpdatedState(onDrawLineDelete)
    val liveDrawLines by rememberUpdatedState(drawLines)
    /**
     * 直线「敲第一下」落下的锚点。**图表内部过程态**：敲完第一下先只亮一个点，
     * 第二次按下拖动出预览、抬手才成交 —— 与 priceBase 一样靠 remember 常驻、
     * 手势协程读写、绘制端跟读。换工具/换标的/换周期即作废（key 与 pointerInput 同步）。
     */
    var trendSeed by remember(drawTool, symbolKey, interval.storageKey) {
        mutableStateOf<TrendAnchor?>(null)
    }
    /**
     * 直线工具的十字辅助线落点（像素）。**选中工具即常驻**（2026-10-14 用户拍板）：
     * `null` = 还没动过，画在**图中央**待命（有种子时停在种子点上）；
     * 拖动中逐帧跟手，松手 = 定点 —— 种子/预览在松手那一刻才落，准星**不清**，
     * 留在原位等下一次拖动。换工具/换标的/换周期即作废（key 与 pointerInput 同步，
     * 工具收起后绘制端也不再画它）。
     */
    var trendCrosshair by remember(drawTool, symbolKey, interval.storageKey) {
        mutableStateOf<Offset?>(null)
    }
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
    /** 直线锚点的「下标↔像素」换算离不开窗口起点与槽宽，同样只能读实时值。 */
    val livePlot by rememberUpdatedState(plot)
    val liveBarCount by rememberUpdatedState(barCount)
    val liveAutoRange by rememberUpdatedState(autoRange)
    /** 纵向平移的钳制基准也要用冻结值，否则手势中基准一变，`pricePan` 会被反复重新钳。 */
    val liveRangeBase by rememberUpdatedState(rangeBase)
    val liveLoadMore by rememberUpdatedState(onLoadMore)

    /**
     * 序列**落地之后**再判一次要不要继续补（补页回来 / 换周期 / 首屏取到）。
     *
     * 补页落地时视窗一动不动，只是「更早的根数」变多了：若一次缩小跨过了不止一页，
     * 窗口仍比数据宽，这时没人再问一次就停在半屏空白上 —— 表现为「缩到底了还是不刷新，
     * 得再捏一下才动」。桌面端同一处在 `KlineCanvas` 的 `useEffect([series], reportOlder)`。
     *
     * 只按 [barCount] 重启：视窗自己的变化（拖动、缩放）已经在手势里报过了，
     * 这里再挂视窗会把每帧都刷成一次 effect。
     */
    LaunchedEffect(barCount) {
        if (viewport.needsOlder(barCount)) liveLoadMore()
    }

    /**
     * 换周期/换标的/首次进入后，新序列一就绪就把纵轴**位置**重定到最新价（现价居中）。
     *
     * 只动位置，**不动缩放倍数**：纵向缩放是跨周期、跨标的全局保留的看图意图
     * （见 [priceZoom] 的注释），过去这里把倍数也一起重算成「罩住全部可见蜡烛」的值，
     * 于是用户捏出来的缩放手感每次切周期都被抹掉 —— 用户报的就是这个
     * （「横坐标记住了，纵坐标失效了」，2026-10-22）。现在倍数原样留着，
     * 只把中心对到现价上（钳制口径见 [ValueRange.panCenteringOn]）。
     *
     * 仍然保留重定的理由：旧意图里的**位置**套到新序列上会把现价顶出量程，
     * 现价虚线与现价牌会整个消失（见 [drawLastPrice]），而切周期后第一眼要看的就是现价。
     * 之后手势照常可再调，右上角小标仍可复位成贴合的自动量程。
     *
     * 量程不取 [liveAutoRange] 而是按当前序列+视口现算（与 [autoRange] 同口径）：
     * 序列从空变满的那一刻，本协程在重算 `autoRange` 的那次重组**之前**就 resumes，
     * 读到的「最新值」还是空序列的 (0,1) 兜底，居中会锚错。
     */
    val liveSeriesForRecenter by rememberUpdatedState(series)
    val liveViewportForRecenter by rememberUpdatedState(viewport)
    LaunchedEffect(symbolKey, interval.storageKey) {
        val ready = snapshotFlow {
            val current = liveSeriesForRecenter
            val close = current.candles.lastOrNull()?.closeDouble()
            if (close == null || !close.isFinite()) return@snapshotFlow null
            val visible = liveViewportForRecenter.window(current.size)
            val base = current.mainRange(visible.first, visible.last)
            if (base.high - base.low <= ValueRange.EPSILON) null else close to base
        }.filterNotNull().first()
        val (close, base) = ready
        pricePan = base.panCenteringOn(close, priceZoom)
    }

    /**
     * 横向平移松手后，把**手势期间显示的绝对量程**落成相对当前窗口的意图。
     *
     * 不这么做的话，[priceBase] 一清空 `rangeBase` 就切回新窗口的自动量程，
     * 量程与中心一起变 ⇒ 松手瞬间整张 K 线上下跳一下（用户报的「拖到历史时
     * 自动把 K 线摆到画布中间」）。落成意图之后，取景与手指松开前逐像素一致；
     * 想恢复自动适配仍由右上角「复位」一键完成。
     *
     * [frozenBase] 是手势开始时冻结的**基准**量程，屏幕上真正显示的是它叠加
     * `priceZoom` / `pricePan` 之后的结果 —— 要保住的是后者，所以先回放一次。
     * 基准按当前序列 + 当前视口现算（与 [autoRange] 同口径），不读 [liveAutoRange]：
     * 松手这一帧的重组可能还没跑，读到的「实时值」仍是上一帧窗口的量程。
     *
     * 唯一的例外是**数据已经滑出冻结量程**（见 [ValueRange.overlapShare]）：那时留住取景
     * 等于留一张空图，交给自动量程重排一次。
     */
    fun repinPriceRange(frozenBase: ValueRange) {
        val current = liveSeriesForRecenter
        if (current.size <= 0) return
        val visible = viewport.window(current.size)
        val base = current.mainRange(visible.first, visible.last)
        val shown = frozenBase
            .scaled(priceZoom, frozenBase)
            .panned(pricePan, frozenBase)
        // 新窗口的数据已经基本滑出冻结量程（< 25% 可见，与纵向平移的收口同一条线）：
        // 硬钉下去就是一张空图，这时退回自动适配 —— 宁可跳一下，也不能没 K 线。
        if (base.overlapShare(shown) < ValueRange.MIN_VISIBLE_SHARE) return
        base.intentFor(shown)?.let { intent ->
            priceZoom = intent.zoom
            pricePan = intent.pan
        }
    }

    // 每帧重建 8 个 Color 引用代价极低，反而省掉一长串 remember key——key 里不能放 MaterialTheme 调用
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette(
        up = upColor,
        down = downColor,
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        lines = ChartLineColors,
        crosshair = scheme.onSurface,
        // primary 就是设计系统的 accent 黄（见 Color.kt 的 lightMaterialScheme）
        mark = scheme.primary,
    )

    /**
     * 已有告警线的像素落点，供手势就近抓取。
     *
     * 走 live* 而不是直接读 `geo` / `alertLines`：本函数被 `pointerInput` 协程捕获，
     * 而协程只活到手势结束，期间量程与几何一直在变（缩放、来新蜡烛），
     * 按下那一刻按旧值算落点就会抓错线、或者干脆凭空新建一根。
     */
    fun alertAnchors(): List<HorizontalAnchor> {
        val plot = liveGeo
        val range = alertRange
        return liveAlertLines.map { line ->
            HorizontalAnchor(
                ref = LineRef.Alert(line.ruleId),
                yPx = plot.yOf(range.toFraction(line.price), plot.mainTopPx, plot.mainHeightPx),
            )
        }
    }
    val alertGrabPx = with(density) { ALERT_GRAB_WIDTH.toPx() }

    /** 已有水平线的纵向像素落点，供手势就近抓取（与 [alertAnchors] 同一条换算链）。 */
    fun hAnchors(): List<HorizontalAnchor> {
        val g = liveGeo
        val range = alertRange
        return liveDrawLines.filter { it.kind == ChartLineKind.HORIZONTAL }.map { line ->
            HorizontalAnchor(LineRef.Guide(line.id), g.yOf(range.toFraction(line.price), g.mainTopPx, g.mainHeightPx))
        }
    }

    /** 落点 y 像素对应的价格原始值（告警线/水平线拖动与直线端点换算共用）。 */
    fun pixelToPrice(yPx: Float): Double {
        val g = liveGeo
        val fraction = (yPx - g.mainTopPx) / g.mainHeightPx.coerceAtLeast(1f)
        return alertRange.fromFraction(fraction)
    }

    /**
     * 直线锚点（下标+价格）→ 像素：与蜡烛同一个 `xOf` 链，平移缩放后自然落位。
     *
     * ⚠️ 纵向用 [ValueRange.fractionRaw]（**不夹取**）而不是 `toFraction`：
     * 直线是两点定斜率，任一端被夹到 [-0.5, 1.5] 就改了斜率 —— 纵轴缩放到锚点出量程时，
     * 线会绕着另一端转（2026-11-23 用户「缩放纵坐标，直线穿过的点还是会变」）。
     * 越界的那一端由 `trendPixels()` 的 `clipLineToRect` 裁掉。
     */
    fun anchorPx(anchor: TrendAnchor): Offset {
        val g = liveGeo
        val p = livePlot
        return Offset(
            ((anchor.index - p.start).toFloat() + 0.5f) * g.slot(p.visibleBars),
            g.yOf(alertRange.fractionRaw(anchor.price), g.mainTopPx, g.mainHeightPx),
        )
    }

    /** 落点 x 像素对应的分数蜡烛下标（[anchorPx] 的逆链）。 */
    fun indexAt(xPx: Float): Double {
        val g = liveGeo
        val p = livePlot
        val slot = g.slot(p.visibleBars)
        return if (slot > 0f) xPx / slot + p.start.toDouble() - 0.5 else Double.NaN
    }

    /**
     * 已有直线**在当前绘图区里看得见的那一段**（两端都已被裁到主图矩形内）。
     *
     * ❗这里给的是**裁剪后的可见段**，不是原始两个锚点。三条理由（2026-11-22 真机反馈）：
     * ① 直线在屏幕上向左右沿延长到绘图区边界，用户点的是**看得见的那条线** ——
     *    按锚点段判命中时，点在延长段上算落空，于是一次都选不中；
     * ② 锚点可能早被横向平移推到屏幕外，把手画在那里就等于没有把手；
     * ③ 命中带、把手位置、绘制段三者必须同源，否则「看得见」与「点得着」永远对不上。
     */
    fun trendPixels(): List<TrendLinePixels> =
        liveDrawLines.mapNotNull { line ->
            val a1 = line.anchor1
            val a2 = line.anchor2
            if (line.kind != ChartLineKind.TREND || a1 == null || a2 == null) null
            else {
                val g = liveGeo
                clipLineToRect(
                    a = anchorPx(a1),
                    b = anchorPx(a2),
                    left = 0f,
                    top = g.mainTopPx,
                    right = g.plotWidthPx,
                    bottom = g.mainTopPx + g.mainHeightPx,
                )?.let { TrendLinePixels(line.id, it.first, it.second) }
            }
        }

    /**
     * 图内浮层（左下角「全屏」角标、右上角「复位」小标、右下角的显隐开关组）距绘图区边缘的内缩距离。
     * 几处浮层同用 [CORNER_BUTTON_INSET] 一档值，贴角才对称。
     */
    val cornerInsetPx = with(density) { CORNER_BUTTON_INSET.toPx() }

    /**
     * 这一帧画哪些告警线：
     * · 划线工具选中 = **纯净画布**（2026-10-14）：已划的线全部让位，只留手上那笔草稿
     *   （`dragging` 的那条）—— 落新线时满屏旧线只碍事，还容易被误认成自己刚拖的那根；
     * · 平时（NONE，含竖屏）随眼睛显隐。
     */
    val visibleAlertLines = when (drawTool) {
        DrawTool.ALERT -> alertLines.filter { it.dragging }
        else -> if (alertLinesVisible) alertLines else emptyList()
    }

    /**
     * 这一帧画哪些视觉画线：
     * · 告警工具下全不画 —— 那时单指属于告警线；
     * · 水平线/直线工具下**只留手上那笔草稿**（纯净画布，2026-10-14 改）——
     *   旧规则「只画同型的线」随「工具下不再抓已有线」一起废掉：编辑已归解锁态「选中后拖」，
     *   工具模式下满屏旧线只剩干扰；
     * · 平时（NONE，含竖屏）随眼睛显隐、只显不可编。
     */
    val visibleDrawLines = when (drawTool) {
        DrawTool.ALERT -> emptyList()
        DrawTool.NONE -> if (alertLinesVisible) drawLines else emptyList()
        else -> drawLines.filter { it.dragging }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                // ⚠️ key 只能有这几样：划线开关、编辑开关、标的、周期。
                //
                // 它们是**只由用户点击改变**的量，不可能在手势中途变。其余一切（可见根数、
                // 序列长度、绘图区像素尺寸）都会在手势进行中被数据更新改掉：key 一变
                // pointerInput 协程就重启，当前手势直接作废 —— 重启后的 awaitFirstDown
                // 要等一次全新的按下，手指没抬起就永远等不到。表现有两种：
                // 「缩放被打断，每次捏一下只动一根」，以及「整块图卡住、怎么拖都不动」。
                // 这些值改从上面的 live* 里读实时值。
                //
                // ❗**`linesEditable` 必须进 key**（2026-10-14 真机抓到）：
                // 这两个值是在**闭包外捕获**的普通参数，不在 key 里就永远不会刷新 ——
                // 全屏那侧「上锁」时 `drawTool` 从 `NONE` 变成……还是 `NONE`（工具本来就是收起的），
                // 于是 key 不变、协程不重启，手势仍然拿着**上锁前那份 `linesEditable = true`**，
                // 手柄里那句 `drawTool == NONE && linesEditable` 照旧成立 ⇒ **锁了还能把线拖走**，
                // 而 UI 那侧（提示条消失、按钮置灰）明明已经摆出「锁了」的样子。
                // 真机复现：上锁 → 竖拖 60px，`chart_line.price` 从 83312.04 变 83335.64。
                //
                // 为什么可以放心重启：这个开关只由竖栏那枚按钮点击改变，而点按钮时手上
                // 不可能同时按在图上，不存在「手势进行中被重启」的场景。
                //
                // 标的与周期**必须**进 key，原因是另一回事：十字光标与价格基准
                // （priceBase）仍挂在 `remember(symbolKey, interval.storageKey)` 上，
                // 换周期会**重建 MutableState**，而本协程的闭包捕获的是创建那一刻的
                // 委托对象。key 不含周期时协程不重启，手势就一直在写**已经被丢弃的旧
                // state**，渲染读的却是新 state —— 表现为「切过周期的图十字光标不出来、
                // 捏合时价格基准冻结失效（量程逐帧抖）」。视窗与纵向刻度**不在此列**：
                // 它们是全局视角偏好，不挂序列 key（见上），委托永远指向活的 state。
                .pointerInput(drawTool, linesEditable, symbolKey, interval.storageKey) {
                    /**
                     * 本场手势有没有横向平移过 —— 松手时据此决定要不要保住纵向取景。
                     *
                     * 只活在协程里（不进 Compose 状态）：它只在 onPan / onGestureEnd 之间传递，
                     * 每帧写 state 会让整张图跟着重组。
                     */
                    var pannedHorizontal = false
                    detectChartGestures(
                        longPressMs = viewConfiguration.longPressTimeoutMillis,
                        touchSlop = viewConfiguration.touchSlop,
                        geo = { liveGeo },
                        drawTool = drawTool,
                        horizontalAnchors = {
                            // 只有解锁态（NONE + linesEditable）才就近抓已有线；工具模式下一律
                            // emptyList —— 抓取来源都没有，天然「只新建」（2026-10-14 口径）。
                            if (drawTool == DrawTool.NONE && linesEditable) {
                                alertAnchors() + hAnchors()
                            } else {
                                emptyList()
                            }
                        },
                        grabPx = alertGrabPx,
                        linesEditable = linesEditable,
                        // 「按下命中的这条是不是已选中的那条」决定这一场是拖动还是选中的候选。
                        // 走取值函数而不是定值：`selectedLine` 是 `mutableStateOf` 的委托，
                        // 这个 lambda 每次调用都读实时值（与 onTrendSeedGet 同款），
                        // 因此它也**不必**进 pointerInput 的 key。
                        currentSelection = { selectedLine },
                        onSelectLine = { selectedLine = it },
                        onHorizontalDrag = { ref, yPx ->
                            // 量程是变动的（缩放/平移），必须走 rememberUpdatedState 读实时值，
                            // 否则这个回调会拿协程启动那一刻的旧量程换算，画出来的线跑偏
                            val price = pixelToPrice(yPx)
                            if (price.isFinite()) {
                                when (ref) {
                                    // 抓到已有的告警线：按同一 id 更新（null = 抓到的是手上那笔草稿）
                                    is LineRef.Alert -> alertDrag(ref.ruleId, price)
                                    is LineRef.Guide -> drawDrag(
                                        DrawGuideLine(
                                            id = ref.lineId,
                                            kind = ChartLineKind.HORIZONTAL,
                                            price = price,
                                            dragging = true,
                                        ),
                                    )
                                    // 落空：按当前工具新建一根。解锁态落空时根本不会走到这里
                                    // （那时单指归画布手势，见 linesEditable）
                                    null -> when (drawTool) {
                                        DrawTool.ALERT -> alertDrag(null, price)
                                        DrawTool.HORIZONTAL -> drawDrag(
                                            DrawGuideLine(
                                                id = 0L,
                                                kind = ChartLineKind.HORIZONTAL,
                                                price = price,
                                                dragging = true,
                                            ),
                                        )
                                        else -> Unit
                                    }
                                }
                            }
                        },
                        onHorizontalCommit = { ref ->
                            when (ref) {
                                is LineRef.Alert -> alertCommit(ref.ruleId)
                                is LineRef.Guide -> drawCommit(ref.lineId)
                                null -> when (drawTool) {
                                    DrawTool.ALERT -> alertCommit(null)
                                    DrawTool.HORIZONTAL -> drawCommit(0L)
                                    else -> Unit
                                }
                            }
                        },
                        trendPixels = ::trendPixels,
                        trendAnchorAt = { xPx, yPx ->
                            val index = indexAt(xPx)
                            val price = pixelToPrice(yPx)
                            if (index.isFinite() && price.isFinite()) TrendAnchor(index, price) else null
                        },
                        trendAnchorPx = ::anchorPx,
                        onTrendSeed = { anchor -> trendSeed = anchor },
                        onTrendSeedGet = { trendSeed },
                        // 直线两敲的十字辅助线：抓着拖（按下不传送），松手不清（见手势尾部）
                        onTrendCrosshair = { trendCrosshair = it },
                        onTrendCrosshairGet = { trendCrosshair },
                        onTrendDrag = { id, a1, a2 ->
                            drawDrag(
                                DrawGuideLine(
                                    id = id,
                                    kind = ChartLineKind.TREND,
                                    anchor1 = a1,
                                    anchor2 = a2,
                                    dragging = true,
                                ),
                            )
                        },
                        onTrendCommit = { id -> drawCommit(id) },
                        onTrendDelete = { id -> drawDelete(id) },
                        onGestureStart = {
                            // 冻结价格基准：整场手势里价格轴不再随可见区间重算（见 priceBase）
                            pannedHorizontal = false
                            priceBase = liveAutoRange
                        },
                        onGestureEnd = {
                            val frozen = priceBase
                            priceBase = null
                            // 横向平移松手**不重新适配**纵向量程（2026-10-26 用户报）：
                            // 冻结的量程若直接交回自动量程，它会按新窗口重算，K 线整块上下跳一下
                            // —— 看着就像「拖到历史时自动把 K 线摆回画布中间」。
                            // 把冻结量程反算成相对新窗口的意图，取景原样留住；
                            // 想回到自动适配仍走右上角「复位」。
                            if (pannedHorizontal && frozen != null) {
                                repinPriceRange(frozen)
                            }
                            pannedHorizontal = false
                        },
                        onPan = { deltaPx ->
                            val bars = liveBarCount
                            val slot = liveGeo.slot(viewport.clamp(bars).visibleBars)
                            // 位移直接折成**小数根**交给 pan()：视窗是浮点的，
                            // 单帧不足一根也照样生效，不需要再攒零头（旧版攒零头正是
                            // 「拖动一格一格跳」的来源）。
                            val deltaBars = ChartGesture.barDelta(deltaPx, slot)
                            if (deltaBars != 0f) {
                                pannedHorizontal = true
                                val moved = viewport.pan(deltaBars, bars)
                                viewport = moved
                                // 视窗左端进了「提前量」就要更早的历史（PRD FR-2.2），
                                // **与拖动方向无关**：数据不足一屏时往左拖也是在看空白。
                                //
                                // 判据收在 [ChartViewport.needsOlder] 一处，缩放走的是同一支
                                // （见 onZoom）—— 原先这里写的是 `deltaBars > 0 && atOldestEdge`，
                                // 两个条件都把「缩小时需要补页」排除在外，用户报的
                                // 「一直缩小不刷新」就是这么来的。
                                //
                                // 这里**只报事件、自己不做去重**：该不该真取下一段，只有 ViewModel
                                // 说了算 —— 它手上才有 (rawBase, raw, hasMore) 这一组「这段序列是什么」。
                                // 2026-10-14 之前的写法有两个坑，合起来就是用户报的
                                // 「切换周期之后再也不补充加载」：
                                //   ① 判据写的是 `startIndex(bars) == 0`，而那是浮点 floor 的结果，
                                //      缩放过的图（可见根数非整数）在左端算出来是 -1，永远命中不了；
                                //   ② 去重标记 `oldestRequested` 挂在图这一侧且**不带 key** ——
                                //      图这一侧不知道序列换了没有，标记一旦「用掉」就再也不放，
                                //      而它又与 ViewModel 的闸门各自独立，两边生命周期对不上。
                                // 现在去重收归 ViewModel（标记与序列同生共死），这边每帧照报，
                                // 重复调用由那边一次性挡掉，代价只是几次状态读取。
                                if (moved.needsOlder(bars)) liveLoadMore()
                            }
                        },
                        onZoom = { barFactor, anchorX ->
                            // 因子直接交给 zoom()：视窗是浮点的，`visibleBars` 不会被取整，
                            // 缓慢捏合的零点几根也能逐帧体现，不需要再攒余量。
                            val zoomed = viewport.zoom(
                                barFactor = barFactor,
                                anchorRatio = anchorX / liveGeo.plotWidthPx,
                                barCount = liveBarCount,
                            )
                            viewport = zoomed
                            // 缩小 = 视窗变宽 = 需要更长的历史：这里不报的话，
                            // 「一直捏合缩小」会一路把左端推成空白却永远不补页。
                            if (zoomed.needsOlder(liveBarCount)) liveLoadMore()
                        },
                        onPriceZoom = { factor ->
                            // 用 autoRange 当基准算钳制边界，不能传当前量程（会越缩越跑）
                            priceZoom = (priceZoom * factor).coerceIn(
                                ValueRange.MIN_SPAN_RATIO.toFloat(),
                                ValueRange.MAX_SPAN_RATIO.toFloat(),
                            )
                        },
                        // 读实时值：手势协程不在缩放时重启，捕获定值会让纵向平移
                        // 一直按手势开始时的倍数换算（放大后越拖越快）。
                        priceZoom = { priceZoom },
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
                drawExtremeLevels(extremes, plot, geo, palette, mainRange)
                if (visibleAlertLines.isNotEmpty()) {
                    drawAlertLines(visibleAlertLines, geo, palette, mainRange, selectedLine)
                }
                // 视觉画线压在告警线之后、蜡烛之上同区：它是用户手写的「记号」，
                // 被红色告警线盖住时无所谓，反过来告警线该让位给刚拖的黄色画线
                if (visibleDrawLines.isNotEmpty()) {
                    drawDrawLines(visibleDrawLines, geo, palette, mainRange, ::anchorPx, selectedLine)
                }
                // 直线工具的十字辅助线：**选中工具就画**（2026-10-14）—— 没动过画在图中央
                // 待命，定完第一点停在种子点上，拖动中逐帧跟手。用户拖着它走、松手定点，
                // 「点在哪儿」在松手前一直看得见。与长按十字光标不同：不吸附整根蜡烛、
                // 不带读数标 —— 落点读数由预览线承担，且它画在种子点之下。
                if (drawTool == DrawTool.TREND) {
                    val crosshairPos = trendCrosshair
                        ?: trendSeed?.let(::anchorPx)
                        ?: Offset(geo.plotWidthPx / 2f, geo.mainTopPx + geo.mainHeightPx / 2f)
                    drawTrendCrosshair(crosshairPos, geo, palette)
                    // 已落种子：预览线从种子连到准星（2026-10-27 起由绘制端按两者实时画，
                    // 手势侧不再逐帧发草稿 —— 移动只调位置，轻点那一刻才发草稿并成交）
                    trendSeed?.let { seed ->
                        val p1 = anchorPx(seed)
                        if (p1.x.isFinite() && p1.y.isFinite() && (crosshairPos - p1).getDistance() > 0.5f) {
                            drawLine(palette.mark, p1, crosshairPos, 3f)
                        }
                    }
                }
                // 直线落第一下的种子点：第一次敲完先亮这个点，第二次按下拖出线、抬手成交
                trendSeed
                    ?.takeIf { drawTool == DrawTool.TREND }
                    ?.let { drawTrendSeedDot(anchorPx(it), palette) }
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
            // ---- 右侧价格列 ----
            // 列宽 = 最宽价签宽（见 `rememberAxisColumnWidth`），住户只有价签，不再有刻度数字
            // （2026-11-23 用户「价格轴宽度和价格签一样宽 + 价格轴上不显示价格段」）。
            // 列本身仍是「上下拖 = 缩放纵轴」的手势区（见 `onPriceAxis`），与是否画刻度无关。
            if (AXIS_TICKS_VISIBLE) {
                PriceAxisLabels(mainRange, geo, density, Modifier.align(Alignment.TopStart))
            }
            // ---- 右轴价格标：所有「这条线多少钱」的读数都在这一处落位 ----
            //
            // 三种标共用右轴那一列：最新价（虚拟的当前价线）、十字光标横线、划线
            // （告警线 / 水平画线）。它们经常压在同一高度，谁盖谁就成了唯一要回答的问题。
            //
            // **长按读数优先于最新价**（2026-11-15 用户）：长按看价时，用户要的就是手指
            // 指着的那个价位；它被现价签盖住，等于这次查看白做。十字光标只在查看时存在、
            // 平时自动让开，所以让它压在上面不会把「现在多少钱」长期藏起来 —— 没在查看时
            // 最新价照旧是那一列里最上面的。
            //
            // 这里不用 Composable 的书写顺序隐式决定层级（那是最脆弱的做法：谁后写谁在上，
            // 加一枚标就可能把现价盖掉），而是收成一张带优先级的清单：落位只裁边、
            // **太近就原地叠住**，再按优先级**从低到高绘制** —— 高的后落笔、压在上面。
            // 挤动式的避让已撤（2026-10-30）：标签离了它那条线，读数看着就像别的价位。
            //
            // 线不画时标签也不画 —— 只藏线不藏数字，右侧会凭空挂着一串来路不明的价位。
            val rightPriceTags = buildList {
                lastPriceTag(series, mainRange, geo, upColor, downColor)?.let { add(it) }
                // 十字光标的横向读数：长按只画一条线而不给价位，用户无从知道线落在哪，
                // 而「这是哪个价位」正是划线的全部意义（全屏里还要据此建预警线）。
                // 它优先级高于最新价，长按时压在上面（见上方清单说明）。
                crosshairPriceTag(crosshair, geo)?.let { add(it) }
                // 可见区间的最高 / 最低价：与其余价标同住右轴那一列，靠那条虚线对回极值那根。
                extremes.forEach { mark ->
                    extremePriceTag(mark, mainRange, geo)?.let { add(it) }
                }
            }
            RightPriceBadges(
                tags = rightPriceTags,
                range = mainRange,
                geo = geo,
                density = density,
                tickSize = tickSize,
            )
            // 划线自己的价牌也住进同一列（2026-11-23-2：与刻度数字、行情读数共用一条右边线），
            // 备注牌仍在左端。
            LineBadges(
                alertLines = visibleAlertLines,
                drawLines = visibleDrawLines,
                range = mainRange,
                geo = geo,
                density = density,
                tickSize = tickSize,
            )
            // 副图纵轴刻度与主图同开关（2026-11-23：一起关掉）。
            if (AXIS_TICKS_VISIBLE) {
                SubAxisLabels(series.subPanes, subRanges, geo, density, Modifier.align(Alignment.TopStart))
            }
            TimeAxisLabels(
                series, plot, geo, interval, density,
                Modifier.align(Alignment.TopStart),
            )
            // 排在时间轴刻度之后：竖线走到哪根，那一列的日期就以实底标压上去
            CrosshairTimeBadge(
                crosshair = crosshair,
                series = series,
                plot = plot,
                geo = geo,
                interval = interval,
                density = density,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // ---- 角标（进入 / 退出全屏）：绘图区左下角 ----
            // **不占左上角**：左上角是指标读数带，放那儿会把整块读数往下顶
            // （当年浮在左上的十字光标弹窗被报过同样的「挤占 k 线数据弹窗」）。
            // 左下角信息密度最低，且离右上角（最新的蜡烛与价格轴）最远。
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
            // 按指标族拆行：MA 一族一行、EMA 一族一行、BOLL 一族一行
            // （分组判据在 ChartSeries.mainReadoutGroups，按角色而不按名字前缀）
            val mainGroups = remember(series, readoutIndex, priceDecimals) {
                series.mainReadoutGroups(readoutIndex, priceDecimals)
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
            // 现价牌钉在读数带右端：读数带左侧读「这一根 K 线 / 这些指标」，
            // 右侧这截（价格轴从绘图区才开始，上面本来空着）正好留给「现在多少钱」。
            // 与左列互不打扰；整块牌子都落在读数带里 —— 压到绘图区会盖住右端那几根最新蜡烛。
            if (lastPriceBoard != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = Spacing.Xxs),
                ) { lastPriceBoard() }
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

            // ---- 底右角的划线显隐开关（眼睛） ----
            // 「图上这一组线要不要画」的总开关。均线那两枚（MA / EMA）已经在指标条上，
            // 图里不再重复 —— 同一个开关摆两处，用户会以为是两件事。
            // 贴着主图底沿：副图再怎么堆，开关都留在被藏的那组线所在的那块图上。
            // 划线模式下不画：那时单指属于划线通道，它是个永远无效的开关
            // （工具下的显隐口径见 visibleAlertLines / visibleDrawLines —— 纯净画布只留草稿）。
            // 其余时候常驻，哪怕当前一条划线都没有：位置固定才认得出它管什么。
            // 要挂 clickable：点它就该切显隐，不该同时被下层画布当成一次拖动。
            val showLineToggle = onToggleAlertLines != null && drawTool == DrawTool.NONE
            if (showLineToggle) {
                val colors = MarketTheme.colors
                val gap = Spacing.Xxs
                val clusterWidth = OVERLAY_BUTTON_SIZE
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = with(density) { (geo.plotWidthPx - cornerInsetPx - clusterWidth.toPx()).toDp() },
                            y = with(density) {
                                ((geo.mainTopPx + geo.mainHeightPx) - cornerInsetPx - OVERLAY_BUTTON_SIZE.toPx()).toDp()
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChartOverlayButton(
                        description = if (alertLinesVisible) "隐藏划线" else "显示划线",
                        onClick = onToggleAlertLines,
                    ) {
                        EyeGlyph(
                            open = alertLinesVisible,
                            tint = if (alertLinesVisible) colors.ink else colors.muted,
                        )
                    }
                }
            }
        }
        // 视角一旦偏离默认取景就亮起「复位」：横纵缩放都是持久化的（切周期、重开详情都带着走），
        // 不给出口用户就回不到默认。往前拖看历史后松手也会亮 —— 横向位置离开了最新价。
        // 复位 = **横纵缩放全部回默认 + 回到最新价格的 K 线**（2026-10-26 用户拍板）；
        // 与「重开详情」的区别：重开只回到最新价，缩放原样保留。按一次即经 onViewChange 落盘。
        // 位置选**绘图区右上角**（2026-11-02 用户拍板）：原来放左下角，紧挨横向拖图与
        // 全屏角标的起手处，手指扫过极易误触 —— 误点一次就把缩放与位置全清掉。
        // 2026-11-21 右侧不留刻度列时它与价签一样压在蜡烛上；11-23 价格列回来后
        // **必须让出那一列**（`end = 列宽 + 内缩`），否则正好盖住最顶上那条刻度数字。
        if (priceZoom != 1f || pricePan != 0f || !viewport.isInitial()) {
            PriceScaleResetBadge(
                onClick = {
                    viewport = ChartViewport.initial()
                    priceZoom = 1f
                    pricePan = 0f
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = with(density) { geo.mainTopPx.toDp() } + CORNER_BUTTON_INSET,
                        end = with(density) {
                            (geo.labelWidthPx + CORNER_BUTTON_INSET.toPx()).toDp()
                        },
                    ),
            )
        }
    }
}

/**
 * 图表骨架：**加载中先摆好的那张空图**。
 *
 * 与真实图表共用 [ChartGeo] 与 [drawGridAndAxes]：读数带占多高、时间轴占多高、
 * 每块副图落在哪，全走同一套公式，数据到了只是把蜡烛**填进去**，不是换一张图。
 * 这也是「先冒一根柱子、再跳出正确的 K 线图」的另一半 —— 中间那段空窗有东西可看，
 * 不会先画一张半成品再整个换掉。
 *
 * 三条刻意的「不画」：
 * - **不画刻度数字**：还不知道任何价位，画出来只能是编的；
 * - **不画蜡烛与读数**：读数为空时那几行全是占位符（`--`），比留白更吵；
 * - **不挂手势**：视窗与纵向刻度是**持久化**的看图偏好，骨架期（`barCount = 0`）
 *   接手势会把 `visibleBars` 夹成 1 写进偏好 —— 数据到了图只剩十几根，还得手动复位。
 *
 * @param subCount 副图块数（按「选了几块」算，与 [ChartGeo.of] 的口径一致）
 * @param readoutLines 主图读数带的行数（MA / BOLL 各一行，见 `DetailUiState.mainReadoutLines`）
 */
@Composable
fun ChartSkeleton(
    subCount: Int,
    readoutLines: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    // 每帧重建这几个 Color 引用代价极低，与 [KlineChart] 同一写法（key 里不能放 MaterialTheme 调用）
    val palette = ChartPalette(
        up = UpGreen,
        down = DownRed,
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        lines = ChartLineColors,
        crosshair = scheme.onSurface,
        mark = scheme.primary,
    )
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 与真实图用同一个读数带高度，否则网格与分隔线会差一行
    val readoutHeightDp = chartReadoutBandDp(readoutLines, hasCandleReadout = true)
    val geo = remember(canvasSize, density, subCount, readoutHeightDp) {
        ChartGeo.of(canvasSize, density, subCount, readoutHeightDp)
    }
    Canvas(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        if (!geo.isUsable) return@Canvas
        clipRect(
            left = 0f,
            top = geo.mainTopPx,
            right = geo.plotWidthPx,
            bottom = geo.mainTopPx + geo.mainHeightPx,
        ) {
            drawGridAndAxes(geo, palette, SKELETON_RANGE, List(subCount) { SKELETON_RANGE })
        }
    }
}

/**
 * 骨架的网格量程：**任取一段等距区间**即可 —— 网格的条数与间距由
 * [ValueRange.gridLines] 的 1-2-5 阶梯决定，取 (0,1) 恰好落成 6 条等距横线，
 * 就是一张「还没有数据的图」该有的样子。数值本身不参与任何绘制（不画刻度）。
 */
private val SKELETON_RANGE = ValueRange(0.0, 1.0)

/**
 * 顶部读数带的高度（dp）：一族指标一行（见 [ChartGeo.readoutBandHeight]），
 * 有 K 线详情小条时再加上小条那条（[CANDLE_READOUT_BAND_DP]）。
 *
 * 真实图（[KlineChart]）与骨架图（[ChartSkeleton]）**必须用同一个公式**：
 * 两边各算各的，读数带就差一截，数据到的瞬间整张图会往下跳一格。
 */
private fun chartReadoutBandDp(readoutLines: Int, hasCandleReadout: Boolean): Float =
    ChartGeo.readoutBandHeight(readoutLines) +
        if (hasCandleReadout) CANDLE_READOUT_BAND_DP else 0f

/**
 * 底右角显隐开关的统一外壳：半透明圆底 + 语义描述，两枚按钮同高同底。
 */
@Composable
private fun ChartOverlayButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .size(OVERLAY_BUTTON_SIZE)
            .clip(Radius.fullShape)
            // 半透明底：它贴在蜡烛上，实心底等于在图里挖了一个洞；
            // 内容本体保持不透明，读「开/关」状态不能跟着一起淡
            .background(colors.washStrong.copy(alpha = 0.5f))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 划线显隐的「眼睛」见 `ui/common/EyeGlyph.kt`（均线面板每行的小眼睛与它同一套图形）。
 */

/** 「复位」小标：横纵缩放回默认 + 回到最新价格的 K 线（[RESET_BADGE_TEXT]）。 */
@Composable
private fun PriceScaleResetBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MarketTheme.colors
    Text(
        text = RESET_BADGE_TEXT,
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
    val crosshair: Color,
    /**
     * 用户画线（水平线 / 直线）：黄（= 设计系统的 accent）。
     *
     * 与告警线（[down] 红）靠**颜色**分工：红 = 会响的告警、黄 = 只是记号的画线。
     * 早期靠线型分工（告警虚线、画线实线），但虚线在密集 K 线上断成一串点、反而不如实线好瞄，
     * 于是三条线一律实线，语义全压到颜色上（2026-10-23，与桌面端同一口径）。
     */
    val mark: Color,
) {
    /**
     * 角色 → 颜色。调色板色位（MA 八位 / EMA 八位 / BOLL 一位）直接按 ordinal 取
     * [lines]（= `ChartLineColors`，两者下标一一对应，见 `LineRole` 的注释）；
     * UP / DOWN / LABEL 是语义色，走主题。
     *
     * 这里刻意**不逐个列出色位** —— 列一遍等于给每加一个色位都留一处会漏改的地方。
     */
    fun roleColor(role: LineRole): Color = when (role) {
        LineRole.UP -> up
        LineRole.DOWN -> down
        LineRole.LABEL -> label
        else -> lines.getOrElse(role.ordinal) { grid }
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
    /** 右侧价格列宽度（画布宽 - 绘图宽）。刻度数字、行情价签、划线价牌都住在这条列里。 */
    val labelWidthPx: Float,
    val plotHeightPx: Float,
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

    /** 画布右缘（含价格列）。刻度与价签一律右对齐到这里，别再各自算一遍。 */
    val canvasRightPx: Float get() = plotWidthPx + labelWidthPx

    fun slot(visibleBars: Float): Float = plotWidthPx / max(1f, visibleBars)

    /** 一根蜡烛的横向中心；窗口起点左侧的蜡烛会落在画布外，由裁剪处理。 */
    fun xOf(index: Int, plot: PlotGeometry): Float =
        (index - plot.start + 0.5f) * slot(plot.visibleBars)

    fun yOf(fraction: Float, top: Float, height: Float): Float = top + fraction * height

    // 实体随槽宽等比，**但槽里必须留缝**（规则见 ChartViewport.kt 的 candleBodyWidth）。
    // 旧写法 `max(1f, slot * 0.66f)` 在槽宽 3~7px 时实体占去大半，相邻两根糊成一条色带
    // （2026-11-21 用户报「缩放时 K 线贴在一起」）。
    fun bodyWidth(visibleBars: Float): Float = candleBodyWidth(slot(visibleBars))

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

        /**
         * 右侧价格列的**下限**宽度（2026-11-23-4 起为 0：列宽完全等于价签宽）。
         *
         * 这一列是**独立的一条**，不从绘图区上浮着压过去：绘图宽 = 画布宽 - 实际列宽。
         * 列里的唯一住户是**行情价签**（最新价 / 极值 / 十字光标 / 划线牌），刻度数字已关
         * （见 `AXIS_TICKS_VISIBLE`）；列本身还是「上下拖 = 缩放纵轴」的手势区（见 `onPriceAxis`）。
         *
         * 为什么把下限降回 0（2026-11-23 用户「价格轴宽度和价格签一样宽」）：
         * 前几轮用 58dp / 70dp / 84dp 当死下限，本意是「留呼吸空间」，实际效果是
         * **价签左边空出一大片、数字仍贴在右缘**，看起来像一条空缝而不是一条轴。
         * 现在宽度 = 最宽价签（文字 + 左右内边距）+ 贴边留白，**左右都零冗余**，
         * 价签色块自己就是这一列的边界。
         *
         * 没有任何样本时（首帧 / 加载中）列宽取 0：那时也不画价签，
         * 绘图区铺满反而是对的；数据回来量到宽度后列才收窄一次。
         */
        const val AXIS_LABEL_MIN_WIDTH_DP = 0f

        /** 价格列最多吃掉画布的这个比例：竖屏窄机 + 大字体时宁可压窄列，也不能把 K 线挤没。 */
        const val AXIS_LABEL_MAX_SHARE = 0.35f
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

        fun of(
            size: IntSize,
            density: Density,
            subCount: Int,
            readoutHeightDp: Float,
            /**
             * 价格列宽度（像素），由调用方按这一列里最宽的文本量出（见 [rememberAxisColumnWidth]）。
             * 0 或负值 = 还没量到（首帧 / 没有任何样本），回落到 [AXIS_LABEL_MIN_WIDTH_DP]。
             */
            axisWidthPx: Float = 0f,
        ): ChartGeo =
            with(density) {
                val measured = size.width > 0 && size.height > 0
                val minLabel = AXIS_LABEL_MIN_WIDTH_DP.dp.toPx()
                val timeAxis = TIME_AXIS_HEIGHT_DP.dp.toPx()
                val readout = readoutHeightDp.dp.toPx()
                val subReadout = SUB_READOUT_DP.dp.toPx()
                // 绘图宽 = 画布宽 - 右侧价格列。列宽先取「量出来的那一份」，再吃一次上限：
                // 窄机 + 大字体时宁可压窄列也不把 K 线压没（上限见 AXIS_LABEL_MAX_SHARE）。
                val desired = if (axisWidthPx > 0f) axisWidthPx else minLabel
                val labelWidth = min(desired, size.width * AXIS_LABEL_MAX_SHARE).coerceAtLeast(0f)
                val plotWidth = max(1f, size.width.toFloat() - labelWidth)
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
                    // 真实让出去的那一截（可能被上限压窄过），刻度与价签都靠它定位
                    labelWidthPx = max(0f, size.width.toFloat() - plotWidth),
                    plotHeightPx = plotHeight,
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
    // 价格列与绘图区的分界线：价格列是一条独立的窄条，没有这条线读起来像块留白
    if (geo.labelWidthPx > 0f) {
        drawLine(
            palette.grid,
            Offset(geo.plotWidthPx, geo.plotTopPx),
            Offset(geo.plotWidthPx, geo.plotHeightPx),
            1f,
        )
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
            drawLine(color, Offset(x, min(highY, lowY)), Offset(x, max(highY, lowY)), 2f)
            continue
        }
        drawLine(color, Offset(x, highY), Offset(x, lowY), 2f)
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
    series.overlay.lines.forEach { line ->
        val path = linePath(line.values, plot, geo, range, geo.mainHeightPx, geo.mainTopPx)
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 2.5f))
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
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
    )
}

/**
 * 可见窗口最高 / 最低价的引线：从极值那一根拉一条很淡的虚线到右轴。
 * 左端咬住极值那根蜡烛、右端接上轴上的价签（见 [extremePriceTag]），一眼看出
 * 「这个数字说的是图上哪个点」。它比最新价那条更淡、也不横贯整张图 ——
 * 左边那半张图不该被两条新线切碎。
 */
private fun DrawScope.drawExtremeLevels(
    marks: List<ExtremeMark>,
    plot: PlotGeometry,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
) {
    marks.forEach { mark ->
        val y = geo.yOf(range.toFraction(mark.price), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        drawLine(
            color = palette.label.copy(alpha = 0.5f),
            start = Offset(geo.xOf(mark.index, plot).coerceAtLeast(0f), y),
            end = Offset(geo.plotWidthPx, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)),
        )
    }
}

/**
 * 已有的告警线，一次一排。
 *
 * 与 [drawLastPrice] 用同一条换算链（`toFraction` → `yOf`），所以线所在的高度与右侧
 * 价格标上的数字严格对应。量程是被缩放过/平移过的，超出主图区就不画 —— 一条跑到读数带
 * 或时间轴上的线只会让人以为刻度坏了。
 *
 * **红 + 实线**（2026-10-23）：颜色就是它的身份（会响的那一类），线型不再承担语义。
 * 正在拖的那根只加粗，不改色 —— 改色会让「红」这件事在拖动中时有时无。
 *
 * **选中**（2026-10-24）多画一道半透明外发光 + 加粗：选中态必须一眼看得出来，
 * 不然「先点一下再拖」这件事在图上完全没有回执（用户点完不知道能不能拖）。
 * 仍然不改色 —— 红的含义是「会响」，不能被选中这个临时状态借走。
 */
private fun DrawScope.drawAlertLines(
    lines: List<AlertPriceLine>,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
    selected: LineRef? = null,
) {
    lines.forEach { line ->
        if (!line.price.isFinite()) return@forEach
        val y = geo.yOf(range.toFraction(line.price), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        val picked = selected is LineRef.Alert && selected.ruleId != null &&
            selected.ruleId == line.ruleId
        if (picked) {
            drawLine(
                color = palette.down.copy(alpha = LINE_SELECTED_HALO_ALPHA),
                start = Offset(0f, y),
                end = Offset(geo.plotWidthPx, y),
                strokeWidth = LINE_SELECTED_HALO_WIDTH,
            )
            // 两端把手 = **改价位唯一的入口**（见 FLAT_GRIP_INSET）：选中态必须把「能抓哪儿」
            // 画出来，否则用户还是会去按线身，然后抱怨「拖图表把线拖走了」。
            drawFlatGrips(geo.plotWidthPx, y, palette.down)
        }
        drawLine(
            color = palette.down,
            start = Offset(0f, y),
            end = Offset(geo.plotWidthPx, y),
            strokeWidth = if (picked || line.dragging) 3.5f else 2.5f,
        )
    }
}

/**
 * 视觉画线（水平线 / 直线）：**黄色实线**，与告警红一眼分开。
 *
 * 两种形状的差别只在几何上（横线 = 一个价位、斜线 = 一条斜率），颜色统一用 [ChartPalette.mark]。
 *
 * **选中**（2026-10-24）铺一道半透明同色外发光、线段加粗、直线的两枚锚点圆点放大 ——
 * 「先单击选中再拖」要求点完立刻有回执，否则用户不知道这一下到底选中了没有。
 */
private fun DrawScope.drawDrawLines(
    lines: List<DrawGuideLine>,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
    anchorPx: (TrendAnchor) -> Offset,
    selected: LineRef? = null,
) {
    lines.forEach { line ->
        val picked = selected is LineRef.Guide && line.id != 0L && selected.lineId == line.id
        val color = palette.mark
        val width = if (picked || line.dragging) 3f else 2f
        val halo = color.copy(alpha = LINE_SELECTED_HALO_ALPHA)
        when (line.kind) {
            ChartLineKind.HORIZONTAL -> {
                if (!line.price.isFinite()) return@forEach
                val y = geo.yOf(range.toFraction(line.price), geo.mainTopPx, geo.mainHeightPx)
                if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
                if (picked) {
                    drawLine(halo, Offset(0f, y), Offset(geo.plotWidthPx, y), LINE_SELECTED_HALO_WIDTH)
                    // 与告警线同一套把手（见 FLAT_GRIP_INSET）：线身只选中，改价抓这两枚点。
                    drawFlatGrips(geo.plotWidthPx, y, color)
                }
                drawLine(color, Offset(0f, y), Offset(geo.plotWidthPx, y), width)
            }

            ChartLineKind.TREND -> {
                val a1 = line.anchor1 ?: return@forEach
                val a2 = line.anchor2 ?: return@forEach
                // 两点定线后向绘图区边界延长，只保留**主图矩形内**那一段：
                // 与手势侧的 trendPixels() 同一个裁剪函数，所以「画出来的线」与
                // 「点得着的线」、「把手落在哪」三者天然一致（2026-11-22）。
                val span = clipLineToRect(
                    a = anchorPx(a1),
                    b = anchorPx(a2),
                    left = 0f,
                    top = geo.mainTopPx,
                    right = geo.plotWidthPx,
                    bottom = geo.mainTopPx + geo.mainHeightPx,
                ) ?: return@forEach
                if (picked) {
                    drawLine(halo, span.first, span.second, LINE_SELECTED_HALO_WIDTH)
                }
                drawLine(color, span.first, span.second, width)
                // 把手**只在选中态出现**（2026-11-22 用户「两个点没选中时不展示」）：
                // 两端改角度、中点整体平移，与水平类线那两枚（drawFlatGrips）同一套语义。
                // 位置就是可见段的两端 —— 锚点在屏幕外时也一定看得见、抓得着。
                if (picked) drawTrendGrips(span.first, span.second, color)
            }
        }
    }
}

/**
 * 选中直线后的三枚把手：两端大、中点小一号。
 *
 * 都由**本色实心圆 + 中心白点**组成：把手大半压在**同色**的斜线上，纯实心圆会跟线融成一体
 * （桌面端 6 倍放大截图为证），白心是「这里能抓」的唯一形状依据。
 *
 * 中点画小一号、且先落笔：整体平移是次要操作，不该在视觉上跟「改角度」抢。
 */
private fun DrawScope.drawTrendGrips(p1: Offset, p2: Offset, color: Color) {
    val ring = Color.White.copy(alpha = 0.92f)
    val r = TREND_GRIP_DOT.toPx()
    val midR = r * 0.78f
    val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    if (mid.x.isFinite() && mid.y.isFinite()) {
        drawCircle(color, radius = midR, center = mid)
        drawCircle(ring, radius = midR * 0.46f, center = mid)
    }
    for (p in listOf(p1, p2)) {
        drawCircle(color, radius = r, center = p)
        drawCircle(ring, radius = r * 0.46f, center = p)
    }
}

/** 直线工具落第一下的种子点：比锚点略大，提示「这里已经敲过一下了」。颜色跟成线同色（黄）。 */
private fun DrawScope.drawTrendSeedDot(center: Offset, palette: ChartPalette) {
    if (!center.x.isFinite() || !center.y.isFinite()) return
    drawCircle(palette.mark, radius = TREND_SEED_DOT.toPx(), center = center)
}

/**
 * 水平类线两端那枚把手（**只在选中态画**，见 [FLAT_GRIP_INSET]）。
 *
 * 本色实心圆 + 中心白点：把手大半压在**同色**的线上，纯实心圆会跟线融成一体 ——
 * 桌面端实测 3.5px 的实心圆在 6 倍放大截图里根本看不出是个点，用户因此不知道线还能拖。
 * 中间那点白是「这里能抓」的唯一形状依据，深浅两套主题下都读得出来。
 */
private fun DrawScope.drawFlatGrips(plotWidthPx: Float, yPx: Float, color: Color) {
    if (!yPx.isFinite() || !plotWidthPx.isFinite()) return
    val inset = FLAT_GRIP_INSET.toPx()
    val r = FLAT_GRIP_DOT.toPx()
    for (cx in floatArrayOf(inset, plotWidthPx - inset)) {
        if (!cx.isFinite()) continue
        drawCircle(color, radius = r, center = Offset(cx, yPx))
        drawCircle(Color.White.copy(alpha = 0.92f), radius = r * 0.46f, center = Offset(cx, yPx))
    }
}

/**
 * 直线工具两敲的十字辅助线：拖动中跟手、松手撤走 —— 与长按十字光标的「松手停住」正好相反
 * （那个要留住读数，这个确认完就该消失）。虚线样式与长按十字光标同款；
 * 落点是**自由像素**（不吸附整根蜡烛）：直线锚点本来就是分数下标，吸附反而落不准想要的价位。
 */
private fun DrawScope.drawTrendCrosshair(point: Offset, geo: ChartGeo, palette: ChartPalette) {
    if (!point.x.isFinite() || !point.y.isFinite()) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val color = palette.crosshair.copy(alpha = 0.7f)
    drawLine(
        color,
        Offset(point.x, geo.mainTopPx),
        Offset(point.x, geo.mainTopPx + geo.mainHeightPx),
        2.5f,
        pathEffect = dash,
    )
    drawLine(
        color,
        Offset(0f, point.y),
        Offset(geo.plotWidthPx, point.y),
        2.5f,
        pathEffect = dash,
    )
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
        if (path != null) drawPath(path, palette.roleColor(line.role), style = Stroke(width = 2f))
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
        2.5f,
        pathEffect = dash,
    )
    // 横线只在主图内跟随手指：落到副图上时画一条横贯全高的线会盖住副图读数
    if (mark.yPx > geo.mainTopPx + geo.mainHeightPx) return
    val y = mark.yPx.coerceIn(geo.mainTopPx, geo.mainTopPx + geo.mainHeightPx)
    drawLine(
        palette.crosshair.copy(alpha = 0.7f),
        Offset(0f, y),
        Offset(geo.plotWidthPx, y),
        2.5f,
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

/**
 * 十字光标处的日期标：贴在时间轴上、与竖线同列。
 *
 * 与横线读数（[crosshairPriceTag] → [RightPriceBadges]，贴右侧价格轴）成对 ——
 * 十字光标的两条线各有一个读数：
 * 横线读价、竖线读时间，用户不必自己数到轴上去比对。
 *
 * 显示条件跟**竖线**走（下标落在可视窗口里），与手指的高度无关：手指划到副图上时
 * 竖线照画，它顶端的读数也不该少。取不到那根蜡烛（下标越出序列末尾的留白）则不画 ——
 * 标一个不属于任何蜡烛的时间比不标更误导。
 */
@Composable
private fun CrosshairTimeBadge(
    crosshair: Crosshair?,
    series: ChartSeries,
    plot: PlotGeometry,
    geo: ChartGeo,
    interval: CandleInterval,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val mark = crosshair ?: return
    if (mark.index !in plot.indices) return
    val candle = series.candles.getOrNull(mark.index) ?: return

    val colors = MarketTheme.colors
    val style = rememberPriceTagStyle()
    val text = ChartModel.formatTime(candle.openTime, interval.minutes)
    val measurer = rememberTextMeasurer()
    val textSize = remember(text, style, density) {
        measurer.measure(AnnotatedString(text), style, density = density).size
    }
    val padH = with(density) { PRICE_BADGE_PAD_H_DP.dp.toPx() }
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    val boxWidth = textSize.width + padH * 2f
    // 以竖线为心中、再夹进绘图区：贴着左右边缘时标会被切掉半个字
    val left = (geo.xOf(mark.index, plot) - boxWidth / 2f)
        .coerceIn(0f, (geo.plotWidthPx - boxWidth).coerceAtLeast(0f))
    val centerY = geo.plotHeightPx + geo.timeAxisHeightPx / 2f
    Text(
        text = text,
        modifier = modifier
            .offset(
                x = with(density) { left.toDp() },
                // 以时间轴带的中线为心：先退掉文字高度的一半，再退掉上内边距
                y = with(density) { (centerY - textSize.height / 2f - padV).toDp() },
            )
            .clip(Radius.xsShape)
            .background(colors.ink)
            .padding(
                horizontal = with(density) { padH.toDp() },
                vertical = with(density) { padV.toDp() },
            ),
        style = style,
        color = colors.paper,
        maxLines = 1,
    )
}

/**
 * 右轴价格标的绘制优先级。数值越大越**后绘制**（压在上面）。
 *
 * 太近就原地叠住、不互相挪 —— 让谁露出来只由这一层决定：
 * · 十字光标 —— 长按正在查看的价位，用户此刻要读的就是它，必须压在最上面；
 * · 最新价 —— 平时那一列的主读数，没有长按读数盖着时它总在上面；
 * · 可见区间最高 / 最低 —— 图上这段的极值，辅助读图。
 *
 * **划线（告警线 / 水平画线）不在这张清单里**（2026-11-04 用户）：它的价位浮在画布左端，
 * 与右轴这一列行情读数分开，见 [LineBadges]。
 */
private const val PRICE_BADGE_PRIORITY_CROSSHAIR = 5
private const val PRICE_BADGE_PRIORITY_LAST = 4
private const val PRICE_BADGE_PRIORITY_EXTREME = 2

/**
 * 右轴价格标的一个候选。
 *
 * [yPx] 是**线 / 手指**所在的高度（已裁进主图）：它既用来反算要显示的价格，
 * 也是标签的摆放位置 —— 标签永远与它所指的那条线同高。
 *
 * [exactPrice] 非 null 时**直接显示**这个价、不再按 [yPx] 反算：线的真实价位只有
 * `alert_rule` / `chart_line` 里那份，线滑出可视区后 yPx 被裁到边上，反算出来的
 * 只会是边缘价（2026-10-30 修的「越界的线价签值不准」）。此时 [yPx] 只剩摆放职责 ——
 * 钉在最近的边上，指「这条线在图外这个方向」。
 */
private class RightPriceTag(
    val yPx: Float,
    val accent: Color?,
    val priority: Int,
    val exactPrice: Double? = null,
)

/**
 * 把一串理想高度落成标签中心：只**裁进主图**（上下各让出半个标签高，贴边不被裁半截），
 * 不做任何避让 —— 太近就叠住，交给绘制顺序决定谁在上面。
 *
 * 纯函数、只吃像素值（不碰 Compose），单测直接喂数值断言。
 */
internal fun placeAxisTags(
    desired: List<Float>,
    heightPx: Float,
    topPx: Float,
    bottomPx: Float,
): List<Float> {
    if (desired.isEmpty() || heightPx <= 0f || bottomPx <= topPx) return desired
    val minCenter = topPx + heightPx / 2f
    val maxCenter = bottomPx - heightPx / 2f
    if (maxCenter < minCenter) return desired
    return desired.map { it.coerceIn(minCenter, maxCenter) }
}

/**
 * 把一条价格 / 一个手指高度包成候选：主图之外的一律不收 ——
 * 那里既没有横线可指，右侧凭空冒一个读数只会误导。
 * 落在主图内、但越出上下沿的会被裁回来（落位时还会再裁进半个标签高）。
 */
private fun rightPriceTag(
    yPx: Float,
    priority: Int,
    geo: ChartGeo,
    accent: Color? = null,
): RightPriceTag? {
    val top = geo.mainTopPx
    val bottom = top + geo.mainHeightPx
    if (bottom <= top || yPx > bottom) return null
    return RightPriceTag(yPx.coerceIn(top, bottom), accent, priority)
}

/**
 * 最新价标：与最新价那条虚线同高同色。
 *
 * 换算链与 `drawLastPrice` 完全一致（`toFraction` → `yOf`），显示条件也一致 ——
 * 量程被缩放/平移把最新价挤出主图时，虚线与标签一起消失，不会剩一个没有线可指的
 * 孤立数字。底色取涨跌色（画蜡烛那两支），所以它与虚线永远同色。
 */
private fun lastPriceTag(
    series: ChartSeries,
    range: ValueRange,
    geo: ChartGeo,
    upColor: Color,
    downColor: Color,
): RightPriceTag? {
    val last: Kline = series.candles.lastOrNull() ?: return null
    val y = geo.yOf(range.toFraction(last.closeDouble()), geo.mainTopPx, geo.mainHeightPx)
    if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return null
    return RightPriceTag(
        yPx = y,
        accent = if (last.close >= last.open) upColor else downColor,
        priority = PRICE_BADGE_PRIORITY_LAST,
    )
}

/**
 * 可见窗口里的最高 / 最低那一根（币安式极值标注）。
 *
 * 标的是「画布上这几根 K 线的高低」，不是整段序列：平移 / 捏合换了一批 K 线，
 * 标就跟着换成新窗口的极值。同价取**较新**的一根，标与线更靠近右侧正在走的部分。
 */
private data class ExtremeMark(val index: Int, val price: Double)

private fun ChartSeries.extremeMarks(window: IntRange): List<ExtremeMark> {
    var hi = -1
    var lo = -1
    for (i in window) {
        val candle = candles.getOrNull(i) ?: continue
        if (hi < 0 || candle.highDouble() >= candles[hi].highDouble()) hi = i
        if (lo < 0 || candle.lowDouble() <= candles[lo].lowDouble()) lo = i
    }
    return buildList {
        if (hi >= 0) add(ExtremeMark(hi, candles[hi].highDouble()))
        if (lo >= 0) add(ExtremeMark(lo, candles[lo].lowDouble()))
    }
}

/**
 * 十字光标横线的读数。显示条件跟**横线**走（落在主图内），与 `drawCrosshair` 一致：
 * 手指划到副图上时横线不画，这里也不该冒出一个孤立的读数。
 */
private fun crosshairPriceTag(crosshair: Crosshair?, geo: ChartGeo): RightPriceTag? =
    crosshair?.let { rightPriceTag(it.yPx, PRICE_BADGE_PRIORITY_CROSSHAIR, geo) }

/**
 * 可见区间极值（最高 / 最低）的右轴价签。
 *
 * 与那条从极值拉过来的虚线成对：线落在图上哪一根、标就落在轴的同一高度。
 * 显示的价走 [RightPriceTag.exactPrice]（就是那根 K 线的高低），不经 y 反算 ——
 * 反算会按刻度四舍五入，标出来的数字就和 K 线读数对不上。
 * 极值被纵向缩放/平移推出取景时不画（钉在边上会冒充「这段的极值」）。
 */
private fun extremePriceTag(mark: ExtremeMark, range: ValueRange, geo: ChartGeo): RightPriceTag? {
    val y = geo.yOf(range.toFraction(mark.price), geo.mainTopPx, geo.mainHeightPx)
    if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return null
    return RightPriceTag(
        yPx = y,
        accent = null,
        priority = PRICE_BADGE_PRIORITY_EXTREME,
        exactPrice = mark.price,
    )
}

/**
 * 右轴那一列价格标的总绘制口。
 *
 * 落位只裁边不避让（[placeAxisTags]）：太近就原地叠住，**按优先级从低到高绘制** ——
 * 同一个 Box 的子节点按声明顺序绘制、后声明的在上，所以按 priority 升序遍历就等于
 * 「优先级高的压在上面」。标签与它所指的线永远同高，读数按 [RightPriceTag.yPx] 反算。
 */
@Composable
private fun BoxScope.RightPriceBadges(
    tags: List<RightPriceTag>,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
) {
    if (tags.isEmpty()) return
    val style = rememberPriceTagStyle()
    val measurer = rememberTextMeasurer()
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    // 所有价格标同一套字号，高度只取决于样式：量一次就够（量最长的一串数字垫底）
    val heightPx = remember(style, density) {
        measurer.measure(AnnotatedString("00000000"), style, density = density).size.height.toFloat() + padV * 2f
    }
    val placed = placeAxisTags(
        desired = tags.map { it.yPx },
        heightPx = heightPx,
        topPx = geo.mainTopPx,
        bottomPx = geo.mainTopPx + geo.mainHeightPx,
    )
    // 按优先级从低到高绘制（后落笔的在上面）；清单自身顺序都不影响层级
    for (index in tags.indices.sortedBy { tags[it].priority }) {
        val tag = tags[index]
        PriceTag(
            priceYPx = tag.yPx,
            centerYPx = placed[index],
            range = range,
            geo = geo,
            density = density,
            tickSize = tickSize,
            modifier = Modifier.align(Alignment.TopStart),
            accent = tag.accent,
            exactPrice = tag.exactPrice,
        )
    }
}

/** 画布左端的划线牌：价位必有一块，备注（EMA 自动名 / 用户备注）有才跟一块。 */
private data class LineBadge(val price: Double, val alert: Boolean, val note: String?)

/**
 * 划线的两块牌：**价位住进右侧价格列**、备注在左端。
 *
 * 右轴那一列住户（自下而上）：网格刻度 → 行情读数（最新价 / 极值 / 十字光标）→ 划线价牌，
 * 全部右对齐到画布右缘，读起来才是「一列数字」。备注仍串在线的左端（图上一眼从左边读起）。
 * EMA 分析画的线名字形如 `8时EMA50`（面板靠它回显），不挂牌的话图上一排红线分不清哪根是哪一档。
 *
 * 落位只裁边不避让：太近就叠住，谁压谁交给书写顺序。
 */
@Composable
private fun BoxScope.LineBadges(
    alertLines: List<AlertPriceLine>,
    drawLines: List<DrawGuideLine>,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
) {
    val style = rememberPriceTagStyle()
    val measurer = rememberTextMeasurer()
    val padH = with(density) { PRICE_BADGE_PAD_H_DP.dp.toPx() }
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    // 贴边留白与行情价签同源（早先是独立的 6dp）：右缘对齐成一条线，不然两类牌子会参差
    val rightInset = with(density) { AXIS_GUTTER_DP.dp.toPx() }
    val colors = MarketTheme.colors
    val decimals = PriceFormatter.decimalsFor(tickSize)
    // 告警线红（会响的那一类）、普通画线黄（只是记号）—— 与桌面端同一套
    val entries = buildList {
        alertLines.forEach { add(LineBadge(it.price, alert = true, note = it.label)) }
        drawLines.forEach {
            if (it.kind == ChartLineKind.HORIZONTAL && it.price.isFinite()) {
                add(LineBadge(it.price, alert = false, note = null))
            }
        }
    }
    for (entry in entries) {
        if (!entry.price.isFinite()) continue
        val y = geo.yOf(range.toFraction(entry.price), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) continue
        val text = PriceFormatter.localeNumber(entry.price, decimals)
        val textSize = remember(text, style, density) {
            measurer.measure(AnnotatedString(text), style, density = density).size
        }
        val chipW = textSize.width + padH * 2f
        val chipH = textSize.height + padV * 2f
        // 中心贴着线；贴边时裁进主图，牌子不被裁掉半截
        val center = if (geo.mainHeightPx > chipH) {
            y.coerceIn(geo.mainTopPx + chipH / 2f, geo.mainTopPx + geo.mainHeightPx - chipH / 2f)
        } else {
            y
        }
        Text(
            text = text,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    // **住进右侧价格列**（2026-11-23-2 用户「价格签应该放在价格轴上」）：
                    // 右缘对齐到**画布右缘**（与刻度数字、行情价签共用同一条右边线），
                    // 不再是 11-04 那版「贴绘图区右缘、浮在 K 线上」。
                    x = with(density) {
                        (geo.canvasRightPx - rightInset - chipW).coerceAtLeast(0f).toDp()
                    },
                    y = with(density) { (center - chipH / 2f).toDp() },
                )
                .clip(Radius.xsShape)
                .background(if (entry.alert) colors.down else colors.accent)
                .padding(horizontal = with(density) { padH.toDp() }, vertical = with(density) { padV.toDp() }),
            style = style,
            color = if (entry.alert) colors.paper else colors.onAccent,
            maxLines = 1,
        )
        val note = entry.note?.takeIf { it.isNotBlank() } ?: continue
        val noteSize = remember(note, style, density) {
            measurer.measure(AnnotatedString(note), style, density = density).size
        }
        val noteH = noteSize.height + padV * 2f
        val noteCenter = if (geo.mainHeightPx > noteH) {
            y.coerceIn(geo.mainTopPx + noteH / 2f, geo.mainTopPx + geo.mainHeightPx - noteH / 2f)
        } else {
            y
        }
        Text(
            text = note,
            modifier = Modifier
                .align(Alignment.TopStart)
                .widthIn(max = with(density) { (geo.plotWidthPx * 0.45f).toDp() })
                .offset(
                    x = 0.dp,
                    y = with(density) { (noteCenter - noteH / 2f).toDp() },
                )
                .clip(Radius.xsShape)
                .background(colors.ink)
                .padding(horizontal = with(density) { padH.toDp() }, vertical = with(density) { padV.toDp() }),
            style = style,
            color = colors.paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 贴在右轴上的价格标。[priceYPx] 决定**显示什么价**，[centerYPx] 决定**贴在哪**。
 *
 * 两者分开是给「贴边被裁」留的余地：中心被夹进主图半个标签高时，读数仍按它所指的
 * 那条线反算。取值走 [ValueRange.fromFraction]——正是 `drawCandles` 里 `toFraction`
 * 的逆运算，所以数字与线所指的价位严格一致，不会因为刻度对齐而差一格。
 */
@Composable
private fun PriceTag(
    priceYPx: Float,
    centerYPx: Float,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    tickSize: BigDecimal?,
    modifier: Modifier = Modifier,
    /**
     * 标签底色。默认中性墨色 —— 十字光标与划线都跟涨跌无关；
     * 最新价传涨跌色，让它与同高的那条虚线一眼能对上。
     */
    accent: Color? = null,
    /**
     * 直接显示的价位（线的真实价格，见 [RightPriceTag.exactPrice]）；
     * null = 按 [priceYPx] 反算（现价 / 十字光标这类「y 就是真的」的标）。
     */
    exactPrice: Double? = null,
) {
    val bottom = geo.mainTopPx + geo.mainHeightPx
    // 落到副图上时不画（上游 rightPriceTag 已挡过一遍，这里防新调用方漏判）
    if (priceYPx > bottom) return
    val anchor = priceYPx.coerceIn(geo.mainTopPx, bottom)
    val fraction = if (geo.mainHeightPx > 0f) (anchor - geo.mainTopPx) / geo.mainHeightPx else 0f
    // 越界的线不能从裁过的 y 反算：那读出来是边缘价，不是这条线的价
    val price = exactPrice ?: range.fromFraction(fraction)
    if (!price.isFinite()) return

    val colors = MarketTheme.colors
    val style = rememberPriceTagStyle()
    val text = PriceFormatter.localeNumber(price, PriceFormatter.decimalsFor(tickSize))
    val measurer = rememberTextMeasurer()
    val textSize = remember(text, style, density) {
        measurer.measure(AnnotatedString(text), style, density = density).size
    }
    val padH = with(density) { PRICE_BADGE_PAD_H_DP.dp.toPx() }
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    // **住进右侧价格列**（2026-11-23 用户「把纵坐标价格区加回来」）：
    // 右缘与刻度数字对齐（`canvasRightPx - gutter`），不再压蜡烛。
    // 列宽已按「这一列里最宽的价签」量出来（见 `rememberAxisColumnWidth`），所以正常情况下
    // 左缘正好落在列内；只有上限被压窄（竖屏窄机 + 大字体）时才向左溢一点 ——
    // 那时宁可压几像素图，也好过把价签画出画布外。
    val tagW = textSize.width + padH * 2f
    val gutterPx = with(density) { AXIS_GUTTER_DP.dp.toPx() }
    Text(
        text = text,
        modifier = modifier
            .offset(
                x = with(density) {
                    (geo.canvasRightPx - gutterPx - tagW).coerceAtLeast(0f).toDp()
                },
                // 以落位高度为中心垂直居中：先退掉文字高度的一半，再退掉上内边距
                y = with(density) { (centerYPx - textSize.height / 2f - padV).toDp() },
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
 * 主图右侧价格刻度（住进价格列，2026-11-23 起重新挂上）。
 *
 * 三条边界规则：
 * - 文字**垂直居中**到网格线上（旧版用 `y - 6f` 硬编码，字高变了就偏）；
 * - 条数按可用高度自适应（见下方 `tickCount`），不再固定 5 条；
 * - 相邻刻度仍挨太近时丢掉下面那条 —— 那是兜底，正常不会再触发全丢。
 *
 * 右对齐到**画布右缘**（`geo.canvasRightPx`）而不是绘图区右缘：这一列是独立的一条，
 * 刻度与价签共用同一条右边线，读起来才是「一列数字」。
 */
@Suppress("unused") // 由 AXIS_TICKS_VISIBLE 控制；保留实现是因为这个开关三起三落过三次
@Composable
private fun PriceAxisLabels(
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val style = rememberAxisTextStyle()
    val measurer = rememberTextMeasurer()
    val labelHeightPx = remember(style, density) {
        measurer.measure(AnnotatedString("0"), style, density = density).size.height.toFloat()
    }
    val minGapPx = labelHeightPx + with(density) { AXIS_LABEL_MIN_GAP_DP.dp.toPx() }
    /**
     * 刻度条数按**可用高度**自适应。
     *
     * 固定 5 条时主图被压矮（横屏 + 多行读数 + 两块副图）会让每条间距小于 [minGapPx]，
     * `lastDrawnY` 那道判据把它们逐条丢掉 —— 表现为右轴一条刻度都没有。
     * 按高度定条数，矮图宁可只给 2~3 条，也不给 0 条。
     */
    val tickCount = remember(geo.mainHeightPx, minGapPx) {
        (geo.mainHeightPx / minGapPx).toInt().coerceIn(2, 8)
    }
    // 小数位跟着步长走：step ≥ 1 一律整数，刻度读起来才是「8000 / 9000」
    val decimals = remember(range, tickCount) { decimalsForStep(range.gridStep(tickCount)) }

    var lastDrawnY = Float.NEGATIVE_INFINITY
    // 顶/底那条刻度贴着边界时**整条丢掉**，不夹进主图：
    // - 上半截会探进读数带（看起来像被切了一半）；
    // - 下半截会与副图顶部那条刻度撞在一起（主图最底一条和副图最高一条几乎同一 y）。
    // 夹取只能解决前者、反而把后者逼得更近，所以宁愿少一条也不叠字。
    val halfHeight = labelHeightPx / 2f
    val topBound = geo.mainTopPx + halfHeight
    val bottomBound = geo.mainTopPx + geo.mainHeightPx - halfHeight
    range.gridLines(tickCount).forEach { value ->
        val y = geo.yOf(range.toFraction(value), geo.mainTopPx, geo.mainHeightPx)
        if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
        if (y < topBound || y > bottomBound) return@forEach
        // 必须取绝对值：gridLines 按数值升序，而像素 y 随数值增大而减小（屏幕 y 轴向下）。
        // 不取绝对值的话 y - lastDrawnY 恒为负，除第一条外全被当成「挨太近」丢掉。
        if (abs(y - lastDrawnY) < minGapPx) return@forEach
        lastDrawnY = y
        AxisLabel(
            text = PriceFormatter.localeNumber(value, decimals),
            rightPx = geo.canvasRightPx,
            centerYPx = y,
            labelHeightPx = labelHeightPx,
            density = density,
            style = style,
            modifier = modifier,
        )
    }
}

/**
 * 价格列里的一行刻度文字：**右对齐到 [rightPx]**，垂直居中到 [centerYPx]。
 * 用固定 [labelHeightPx] 折半来定位，避免依赖 TextStyle 的行高推断。
 */
@Composable
private fun AxisLabel(
    text: String,
    rightPx: Float,
    centerYPx: Float,
    labelHeightPx: Float,
    density: Density,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val widthPx = remember(text, style, density) {
        measurer.measure(AnnotatedString(text), style, density = density).size.width.toFloat()
    }
    val gutterPx = with(density) { AXIS_GUTTER_DP.dp.toPx() }
    Text(
        text = text,
        modifier = modifier.offset(
            x = with(density) { (rightPx - gutterPx - widthPx).coerceAtLeast(0f).toDp() },
            y = with(density) { (centerYPx - labelHeightPx / 2f).toDp() },
        ),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 副图右侧价格刻度（与主图同一列、同一套间距判据）。
 *
 * 条数按这块副图的高度定（矮块 2 条、高块 3 条）：副图普遍矮，画满会糊；
 * 原先固定「首尾两条」，块一矮第二条就被 minGap 丢掉，只剩孤零零一个数字。
 */
@Suppress("unused") // 与主图刻度同开关（AXIS_TICKS_VISIBLE）
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
    val gutterPx = with(density) { AXIS_GUTTER_DP.dp.toPx() }

    panes.forEachIndexed { index, pane ->
        val range = ranges.getOrNull(index) ?: return@forEachIndexed
        val top = geo.subTopOf(index)
        val count = (geo.subHeightPx / minGapPx).toInt().coerceIn(2, 3)
        var lastDrawnY = Float.NEGATIVE_INFINITY
        range.gridLines(count = count).forEach { value ->
            val y = geo.yOf(range.toFraction(value), top, geo.subHeightPx)
            if (abs(y - lastDrawnY) < minGapPx) return@forEach
            lastDrawnY = y
            val label = if (pane.compactAxis) PriceFormatter.formatCompact(value)
            else PriceFormatter.localeNumber(value, PriceFormatter.DEFAULT_DECIMALS)
            val labelWidthPx = remember(label, style, density) {
                measurer.measure(AnnotatedString(label), style, density = density).size.width.toFloat()
            }
            Text(
                text = label,
                modifier = modifier.offset(
                    x = with(density) {
                        (geo.canvasRightPx - gutterPx - labelWidthPx).coerceAtLeast(0f).toDp()
                    },
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
 * - **长按** → 十字光标（松手**停在**那根上，下一次平移/缩放/轻点才把它收走）。
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
 * 划线工具（[drawTool]）激活时单指被图表征用，既不平移刻度也不出十字光标，
 * **且不需要长按**——那是这个工具下唯一的目的，再要求长按只是多余的一步：
 * · ALERT/HORIZONTAL：纵向拖动直接按当前工具**新建**一根（不就近抓已有线，2026-10-14）；
 * · TREND：单指负责两敲落锚 —— 选中工具准星即在图中央待命，按下抓住（不传送）、
 *   拖动位移加到准星上、松手定点（种子此刻才落），第二敲同理出预览、抬手成交；
 *   整体平移已有直线不再是这个工具的职责（归解锁态「选中后拖」）；
 * · ERASE：不拖线，只在松手时删掉按下的那一点命中的线（滑走即作废）。
 * 双指缩放保持不变：画线时同样要能缩放看清细节。
 *
 * **解锁态（[drawTool] == NONE 且 [linesEditable]）是两拍**（2026-10-24）：
 * 单击选中要动的那条线 → 按住它拖动。命中未选中的线时这一场只当「点击候选」，
 * 没滑走、没长按才落选中；命中已选中的那条则当场接管。落空是空白 → 松手取消选中。
 * 长按因此空出来了，还给十字光标（看读数）。
 */
private suspend fun PointerInputScope.detectChartGestures(
    longPressMs: Long,
    touchSlop: Float,
    /**
     * 绘图区几何用**取值函数**而不是三个 Float 入参。
     *
     * 入参是启动那一刻的定值：这个协程的 key 只有「划线工具」，尺寸变了不会重启，
     * 用定值就会拿旧尺寸换算落点（旋屏后划线直接跑偏）。取函数每次事件都读实时值。
     */
    geo: () -> ChartGeo,
    /** 当前划线工具：决定单指走水平拖动通道还是直线落锚通道。 */
    drawTool: DrawTool,
    /**
     * 告警/水平线的纵向像素落点（按当前工具由调用方选好来源），按下那一刻就近抓取。
     * 解锁态会把两类一起给过来 —— 抓完之后靠 [HorizontalAnchor.ref] 分流。
     */
    horizontalAnchors: () -> List<HorizontalAnchor>,
    /** 抓住已有线的容差（像素）。超出就当「在这里新建一根」。 */
    grabPx: Float,
    /**
     * 水平通道拖线：回调「抓到的哪条线」与未换算的 y 像素。
     *
     * [LineRef] 非空 = 抓到了已有的一条（解锁态与划线工具下都会出现）；
     * **null = 落空**，按当前工具就该在这里新建一根 —— 解锁态不会走到这一支
     * （那时落空是画布手势，单指交给平移/十字光标，见 [linesEditable]）。
     */
    onHorizontalDrag: (LineRef?, Float) -> Unit,
    /** 手指离开且本场手势拖过线时回调一次，带同一个 ref，调用方据此落库。 */
    onHorizontalCommit: (LineRef?) -> Unit,
    /**
     * **解锁态**：图上已有的线要**先单击选中，再按住拖动**（不必先选工具）。
     *
     * 2026-10-24 改口径。此前是「按下就近抓线但先挂起，按住满长按时长才转正」，
     * 病灶有两个：① 慢半秒才给反馈，看着像没反应；② 与长按十字光标共用「时长 + slop」
     * 判定，按在线上就再也长按不出读数。现在拆成两个不同时刻的动作：
     * · **单击**（按下即抬、没滑出 slop）→ 选中这一条（由 [onSelectLine] 落地）；
     *   单击空白处 → 取消选中；
     * · **按住已选中的那一条** → 当场接管，整场跟手拖动（水平类改价位、直线整根平移）。
     * 没选中就按住拖动 = 普通画布手势（平移），线纹丝不动 —— 误划再也拖不走线。
     * 落空则照旧还给画布（平移、双指缩放、长按十字光标）。
     * 锁定态传 false —— 锁定 = 整张图只读，已有的线一根都动不了。
     *
     * 「抓哪根」仍放在画布手势之前的 down 定性：拖动半径只有 [grabPx]，
     * 拖动中再判会跟着手指跑。
     */
    linesEditable: Boolean = false,
    /** 读当前选中的那条线（`null` = 没选中）。与 [onSelectLine] 一起构成选中态的唯一出入口。 */
    currentSelection: () -> LineRef? = { null },
    /** 写入选中态（`null` = 取消选中）。调用方持有它，并交给绘制端画「已选中」的高亮。 */
    onSelectLine: (LineRef?) -> Unit = {},
    /**
     * 已有直线**在当前绘图区里可见的那一段**两端（裁剪后的像素），按下那一刻用它就近抓取。
     *
     * 给可见段而不是原始锚点：锚点可能被平移到屏幕外，那时既点不中也画不出把手。
     */
    trendPixels: () -> List<TrendLinePixels>,
    /** 落点像素 → 直线锚点（下标+价格）；换算失败（量程/尺寸异常）返回 null。 */
    trendAnchorAt: (Float, Float) -> TrendAnchor?,
    /** 直线锚点 → 像素：松手时判「第二下是否落得太近」用。 */
    trendAnchorPx: (TrendAnchor) -> Offset,
    /** 写入/清除种子锚点（null = 清除）。种子活在调用方，供绘制端亮出种子点。 */
    onTrendSeed: (TrendAnchor?) -> Unit,
    /** 读当前种子锚点。 */
    onTrendSeedGet: () -> TrendAnchor?,
    /**
     * 直线工具的十字辅助线落点（像素）：选中工具即常驻（null = 画图中央/种子点待命），
     * down **只抓取不传送**（实体化待命位为基准），拖动 = 基准 + 手指位移，**松手不清**
     * （留原地等下一次拖动）。绘制端按它画竖线+横线。
     */
    onTrendCrosshair: (Offset?) -> Unit,
    /** 读当前准星落点（null = 还在待命位）：down 抓取时按它实体化基准，不传送。 */
    onTrendCrosshairGet: () -> Offset?,
    /** 直线拖动中每帧回调整根预览（id 为 0 = 新建中）。 */
    onTrendDrag: (Long, TrendAnchor, TrendAnchor) -> Unit,
    /** 直线成交：id 为 0 = 两敲新建成交，非 0 = 平移已有线。调用方拿最后一帧预览落库。 */
    onTrendCommit: (Long) -> Unit,
    /** 直线删除/放弃：撤销一笔没建成的新线（第二下落得太近 / 种子半路没了）。 */
    onTrendDelete: (Long) -> Unit,
    /** 每次新手势按下时回调一次，用于清掉上一场手势遗留的累积余量。 */
    onGestureStart: () -> Unit,
    /** 每次手势结束时回调一次，用于把手势期间冻结的量交还给实时值。 */
    onGestureEnd: () -> Unit,
    onPan: (deltaPx: Float) -> Unit,
    onZoom: (barFactor: Float, anchorX: Float) -> Unit,
    onPriceZoom: (factor: Float) -> Unit,
    /**
     * 当前纵向缩放倍数（1f = 自动量程，< 1 = 放大）。
     *
     * 走取值函数而不是定值：手势协程的 key 不含缩放倍数，捕获成定值会一直用
     * 手势开始那一刻的倍数换算纵向平移，放大后拖动画布就会越拖越快。
     */
    priceZoom: () -> Float,
    onPricePan: (deltaFraction: Float) -> Unit,
    onCrosshair: (Offset?) -> Unit,
) {
    // 水平类线两端把手的几何（见 FLAT_GRIP_INSET / onFlatGrip）：只跟屏幕密度有关，
    // 每场手势都重算一次没有意义，upstream 直接取一次。
    val flatGripInsetPx = FLAT_GRIP_INSET.toPx()
    val flatGripHitPx = FLAT_GRIP_HIT.toPx()
    val trendGripHitPx = TREND_GRIP_HIT.toPx()
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
        // ——— 单指这一场干什么，全部在 **down 这一刻** 定性 ———
        //
        // 三条通道（划线工具 / 解锁态拖线 / 删除模式）都在这里定，理由是同一个：
        // [awaitFirstDown] 已经把 down 事件消费掉，循环里第一帧 [awaitPointerEvent]
        // 拿到的其实是**第一个 MOVE**。手指一动就滑出抓取半径（[grabPx] 才 24dp），
        // 于是「抓哪根已有的线」就被判错了，甚至凭空新建一根。
        // 轻点更快：第一帧可能已是 UP，循环体一帧 pressed 都见不到。
        // 区别在于**接管时机**：划线工具与删除模式当场接管；解锁态要看**有没有选中**——
        // 命中的是已选中的那条就当场接管，是别的线则只当「点击候选」（松手才选中，见循环尾部）。
        val downY = first.position.y.coerceIn(plot0.mainTopPx, plot0.mainTopPx + plot0.mainHeightPx)
        /** 本场单指抓到的线（解锁态与划线工具下都可能）；null = 没抓到。 */
        var horizontalGrab: LineRef? = null
        /** 本场单指走不走水平通道：拖已有的一条，或按当前工具在这个价位新建一根。 */
        var horizontalActive = false
        /**
         * 抓住已有水平类线时「线在手指上方多少像素」（正 = 线在手指上方）。
         * 拖动时把它加回落点，线只跟位移走 —— 否则 24dp 的抓取容差会变成「按下就跳到指尖」。
         * 新建（落空）时恒为 0。
         */
        var grabOffsetY = 0f
        /** 直线通道下这一场的用途；null = 没走这条通道。 */
        var trendMode: TrendMode? = null
        /** 正在整体平移的已有直线 id（0 只会在「新建」的预览里出现，成交必为正 id）。 */
        var trendGrabbedId = 0L
        /** 抓取那一刻线两端的像素与按下手指位置：平移按「端点 + 手指位移」整根走。 */
        var trendBase: Pair<Offset, Offset>? = null
        /** [TrendMode.ANCHOR] 抓的是哪一端（true = 第一点），以及钉住不动的另一端（数据坐标）。 */
        var trendGripIsA = true
        var trendFixedAnchor: TrendAnchor? = null
        var trendDown = Offset.Zero
        /** 本场最近一次落点（已夹进绘图区）；第二敲靠它判「是不是落得太近」。 */
        var trendLast = Offset.Zero
        /**
         * 直线场「抓住准星拖」的基准：down 那一刻准星与手指的位置。
         * 之后每帧 准星 = down 时的准星 + 手指位移 —— 按下**不传送**，抓着拖。
         */
        var trendCrosshairBase = Offset.Zero
        var trendDragFrom = Offset.Zero
        /**
         * 直线场这一下是不是「移动」（手指离按下处超过 slop，或落过第二根手指）。
         *
         * 2026-10-27 口径：**移动只调准星位置，轻点才落点** —— 松手时它为 true 就什么都不落，
         * 准星（与第二点的预览线）留在原地；为 false 才按准星当前位置定种子 / 成交。
         * 不能复用下面的 `travelled`：划线场在循环里 `continue` 得早，那个累加根本走不到。
         */
        var trendMoved = false
        /**
         * 本场单击命中的那条**尚未选中**的线：松手时（没滑走、没长按）才选中它。
         *
         * 为什么不在 down 就选中：这样「按住线横向平移画布」会顺手把线选走 ——
         * 选中是带视觉与后续拖动的承诺，必须由一次真正的「点击」产生。
         * 滑出 slop 或落下第二根手指即作废（见循环内），这一场就只是平移/缩放。
         */
        var pendingSelect: LineRef? = null
        /** 本场按在空白处：松手时取消选中（同样要「没滑走、没长按」）。 */
        var tapOnBlank = false

        when {
            // ① 划线工具：单指只服务那一条通道，落点就是「在这个价位新建一根」。
            //    **不再就近抓已有线**（2026-10-14）：编辑旧线已由解锁态「选中后拖」覆盖，
            //    工具模式下再抓线只会与「纯净画布上落新线」的预期打架 ——
            //    而且纯净画布里旧线根本不可见，抓一根看不见的线等于盲操作。
            drawTool == DrawTool.ALERT || drawTool == DrawTool.HORIZONTAL -> {
                horizontalActive = true
            }
            // ② 解锁态：按下就近找**哪根线**（不必先选工具），然后看它选没选中 ——
            //    · 已选中 → 当场接管，整场跟手拖（不用长按，按下去就走）；
            //    · 没选中 → 只记「点击候选」，松手且没滑走才选中它（2026-10-24 口径）；
            //    · 落空 → 松手取消选中，拖动照旧是平移画布。
            //    刻度区与副图区不参与 —— 那两处起笔本来就另有含义
            //    （缩放价格 / 交还页面翻页）。
            //
            //    水平类（告警线、水平线）与直线**放在一起比距离**：两条在交点附近都够得着
            //    时，各判一次再按「先水平后斜线」定优先级，会出现「点着看得见的那条、
            //    拖走的却是底下那条」。
            drawTool == DrawTool.NONE && linesEditable && !onPriceAxis && !onSubPane -> {
                val anchors = horizontalAnchors()
                val flat = anchors.nearestHorizontal(downY, grabPx)
                val trend = trendPixels().nearestTrend(first.position, grabPx)
                // 谁更近抓谁；距离相同时（理论上不会）让水平类先，与旧口径一致
                val nearestTrendHit =
                    if (trend != null && (flat == null || trend.second < flat.second)) trend else null
                val hit = nearestTrendHit?.let { LineRef.Guide(it.first.id) } ?: flat?.first
                /**
                 * 命中水平类线时它在屏幕上的高度（`null` = 命中的是直线）。
                 *
                 * 用 `anchors` 反查而不是从 `nearestHorizontal` 拿，是因为 `hit` 已经过
                 * 「谁更近」的取舍 —— 两处各自判一次就会分叉（点在交叉点附近时尤其明显）。
                 */
                val flatY = hit?.let { target -> anchors.firstOrNull { it.ref == target }?.yPx }
                /** 已选中的那条直线（**可见段**两端），只有它才判把手。 */
                val selectedTrend = nearestTrendHit?.takeIf { hit == currentSelection() }?.first
                /** 水平类：这一下是否抓住了两端把手（[onFlatGrip]）。 */
                val flatGrip = flatY != null && hit == currentSelection() &&
                    onFlatGrip(first.position, flatY, plot0.plotWidthPx, flatGripInsetPx, flatGripHitPx)
                /**
                 * 直线：抓到的是哪枚把手（[onTrendGrip]：两端改角度、中点整体平移）。
                 *
                 * 抓把手才算「拖线」；按在线身只是选中。直线在屏幕上贯穿整个绘图区，
                 * 若按线身就算抓到，双指缩放时第一根手指压在线上就会把线拖走
                 * （2026-11-22 用户「纵坐标缩放，直线会漂移」）。
                 */
                val trendGrip = if (flatY == null) {
                    selectedTrend?.let {
                        onTrendGrip(first.position, it.p1, it.p2, trendGripHitPx)
                    }
                } else {
                    null
                }
                when (lineTouch(hit, currentSelection(), dragAllowed = flatGrip || trendGrip != null)) {
                    LineTouch.Drag -> {
                        if (selectedTrend != null && trendGrip != null) {
                            trendGrabbedId = selectedTrend.id
                            trendDown = first.position
                            if (trendGrip == TrendGrip.ANCHOR_A || trendGrip == TrendGrip.ANCHOR_B) {
                                // 固定端 = **另一端可见点**对应的锚点（不是原始 anchor1/2）：
                                // 原始锚点可能早被平移到屏幕外，拿它当旋转轴会让跟手端与手指
                                // 之间多出一段杠杆，拖起来线跑得比手指快。
                                val fixedPx = if (trendGrip == TrendGrip.ANCHOR_A) {
                                    selectedTrend.p2
                                } else {
                                    selectedTrend.p1
                                }
                                val fixed = trendAnchorAt(fixedPx.x, fixedPx.y)
                                if (fixed != null) {
                                    trendGripIsA = trendGrip == TrendGrip.ANCHOR_A
                                    trendFixedAnchor = fixed
                                    trendMode = TrendMode.ANCHOR
                                }
                            } else {
                                trendBase = selectedTrend.p1 to selectedTrend.p2
                                trendMode = TrendMode.MOVE
                            }
                        } else {
                            // 抓取半径有 24dp，手指未必正落在线上：记下「线在手指上下多少」，
                            // 拖动时补回去，线只跟位移走、不往手指上跳。
                            grabOffsetY = (anchors.firstOrNull { it.ref == hit }?.yPx ?: downY) - downY
                            horizontalActive = true
                            horizontalGrab = hit
                        }
                    }

                    LineTouch.Select -> pendingSelect = hit
                    LineTouch.None -> tapOnBlank = true
                }
            }
            // ③ 直线工具：种子在就敲第二下，否则这一场负责「拖准星定第一点」。
            //    同样**不再抓已有线平移**（2026-10-14，理由同 ①）：平移整根直线也是编辑，
            //    归解锁态「选中后拖」；工具下单指只管「抓住准星拖 → 松手定点」。
            drawTool == DrawTool.TREND -> {
                // ❗按下**不传送**准星（2026-10-14 用户二稿）：点哪儿准星就跳哪儿不对 ——
                // 「点击是为了拖准星」，所以 down 只记「抓取」基准（准星原位 + 手指位置），
                // 之后 MOVE 把手指位移原样加到准星上（见循环 SEED/PLACE2 分支）。
                // 准星此刻还在待命位（null = 图中央 / 种子点）的话先把它实体化成那个位置：
                // 绘制结果不变，但手势侧从此有具体坐标可以加位移。
                val cur = onTrendCrosshairGet()
                    ?: onTrendSeedGet()?.let(trendAnchorPx)
                    ?: Offset(plot0.plotWidthPx / 2f, plot0.mainTopPx + plot0.mainHeightPx / 2f)
                onTrendCrosshair(cur)
                trendCrosshairBase = cur
                trendDragFrom = first.position
                trendLast = cur
                // 预览线（种子 → 准星）由绘制端按两者实时画，手势侧不再逐帧发草稿；
                // 草稿只在「轻点成交」那一刻发一帧（见收尾），移动松手后不会留下悬空草稿。
                trendMode = if (onTrendSeedGet() != null) TrendMode.PLACE2 else TrendMode.SEED
            }
        }
        /**
         * 本场单指已经属于图表（而不是交给父级滚动容器）。
         *
         * 解锁态下只有「命中的是已选中的那条」才在 down 就为真；没选中时这一场先是候选的
         * 平移/单击，`owned` 始终为 false（单指照旧走下面的平移 / 长按十字光标）。
         */
        var owned = drawTool != DrawTool.NONE || horizontalActive || trendMode != null
        // 立刻消费 down：图表落在 verticalScroll 的父容器里，
        // 不抢占的话父容器会先开一个滚动手势，纵向拖动就变成翻页而不是调刻度。
        // 副图区起笔则反着来 —— 翻页正是我们要的，down 留给父容器。
        // 划线工具激活、或这一场已经抓到线时例外：那时单指只服务于那根线，
        // 在哪儿起笔都必须抢。
        if (!onSubPane || owned) first.consume()
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
        /**
         * 本场水平通道有没有「挂上去」—— down 那一下发出首帧即置位（见下方），
         * 所以单击与拖动一视同仁。没挂上就不回调落库。
         */
        var dragActive = false
        // 单指方向锁：null 表示还没定，锁定后本次手势不再改
        var axis: ChartGesture.Axis? = null
        var axisAccumX = 0f
        var axisAccumY = 0f
        /**
         * 水平通道在 **down 这一刻**先发一帧：单击也要能落线。
         *
         * 只靠循环里那一帧是不够的 —— 轻点时 down 之后紧接着 up，循环首帧 `pressed`
         * 已经空了（`break` 在循环体之前），body 一次都不进，「敲一下放一根」就永远
         * 落不了库，用户必须先拖动一下才行。这里补的这一帧与循环里那帧同源
         * （同样的夹取、同一个回调），于是「点一下不动」与「点一下再拖」只差后续帧数，
         * 落点语义完全一致 —— 不会出现「单击落的位置和拖动落的位置不一样」。
         *
         * ❗**只在落空新建时发**（`horizontalGrab == null`，2026-10-24）：
         * 抓住已有的线时这一帧会把价位直接设成手指所在处 —— 而选中之后「点一下线」
         * 只是确认、不是要挪它，发首帧就等于让线往指尖跳一格并落库。
         * 拖动要从循环里第一帧 MOVE 起算（那时手指确实动了），配合 [grabOffsetY]
         * 线只跟位移走。
         */
        if (horizontalActive && horizontalGrab == null) {
            dragActive = true
            onHorizontalDrag(null, downY)
        }
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
            val handToPage = onSubPane && drawTool == DrawTool.NONE && !longPressActive &&
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
                // 双指落下 = 这场手势是缩放：上一次留下的十字光标到此为止。
                // 长按中加第二根手指（十字光标仍在跟手）不在此列 —— 那场手势还是它。
                if (pressed.size >= 2 && !longPressActive) onCrosshair(null)
                // ❗双指 = 缩放，从这一刻起**不再是「拖线」**（2026-11-22）。
                // 不清的话，抬掉一根手指后 pressed.size 回到 1，`owned && size==1` 那一支
                // 立刻接着把线拖走 —— 用户两只手缩放纵轴，看到的就是「直线漂移」；
                // 松手时还会 `onTrendCommit` 落库，把漂移记死。
                // 此时锚点一次都没改过（拖动只在 MOVE/ANCHOR 的逐帧分支里发预览），
                // 所以直接丢弃、不发 commit 就是干净的。
                if (pressed.size >= 2 && !longPressActive) {
                    if (trendMode == TrendMode.MOVE || trendMode == TrendMode.ANCHOR) {
                        trendMode = null
                        trendBase = null
                        trendFixedAnchor = null
                    }
                    // 水平类只停「继续跟手」，**不清 horizontalGrab**：收尾的 commit 还指着
                    // 原来那条线，价格一格没动过就是一次空写；清成 null 会被当成「草稿落库」，
                    // 凭空多落一根线。
                    horizontalActive = false
                    owned = false
                }
            }

            // 单击候选（2026-10-24）：滑出 slop 或落下第二根手指就作废 ——
            // 那一下是平移 / 缩放，不是点击，不能顺手把线选走。
            // 长按也会把它作废（见下面 longPressActive 处）：长按的语义是「看读数」。
            if ((pendingSelect != null || tapOnBlank) &&
                (travelled > touchSlop || pressed.size >= 2)
            ) {
                pendingSelect = null
                tapOnBlank = false
            }
            // 直线工具下落第二根手指（缩放）同样不是「轻点」：松手不许落点
            if (pressed.size >= 2) trendMoved = true

            // 划线 / 拖线分支必须排在十字光标判定之前：这两类手势里都不出十字光标。
            // `owned` 只在 down 那一刻定性（划线工具 / 命中已选中的线），
            // 解锁态没选中任何线时为 false，单指照旧走下面的平移 / 长按十字光标。
            if (owned && pressed.size == 1) {
                val plot = geo()
                val raw = primary.position
                val x = raw.x.coerceIn(0f, plot.plotWidthPx)
                val y = raw.y.coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                trendLast = Offset(x, y)
                // `trendMode != null` 而不是 `drawTool == TREND`：解锁态抓到的直线也走这条路
                // （趋势线整体平移与直线工具下的平移是同一件事，没有第二套实现）
                if (trendMode != null) {
                    when (trendMode) {
                        // 抓住准星拖（down 已记基准，见定性处）：准星 = down 时准星 + 手指位移。
                        // 移动**只调位置**（2026-10-27）：超过 slop 即记 trendMoved，松手不落点；
                        // 落点交给下一次轻点（见收尾）。两敲共用这一段，区别只在收尾。
                        TrendMode.SEED, TrendMode.PLACE2 -> {
                            if ((raw - trendDragFrom).getDistance() > touchSlop) trendMoved = true
                            val cx = (trendCrosshairBase.x + (raw.x - trendDragFrom.x))
                                .coerceIn(0f, plot.plotWidthPx)
                            val cy = (trendCrosshairBase.y + (raw.y - trendDragFrom.y))
                                .coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                            trendLast = Offset(cx, cy)
                            onTrendCrosshair(trendLast)
                        }
                        TrendMode.MOVE -> {
                            val base = trendBase
                            if (base != null) {
                                val dx = x - trendDown.x
                                val dy = y - trendDown.y
                                val a1 = trendAnchorAt(base.first.x + dx, base.first.y + dy)
                                val a2 = trendAnchorAt(base.second.x + dx, base.second.y + dy)
                                if (a1 != null && a2 != null) onTrendDrag(trendGrabbedId, a1, a2)
                            }
                        }
                        // 抓端点改角度：只有这一端跟手，另一端用抓取那一刻的锚点原样钉住
                        TrendMode.ANCHOR -> {
                            val fixed = trendFixedAnchor
                            val moving = trendAnchorAt(x, y)
                            if (fixed != null && moving != null) {
                                if (trendGripIsA) onTrendDrag(trendGrabbedId, moving, fixed)
                                else onTrendDrag(trendGrabbedId, fixed, moving)
                            }
                        }
                    }
                    continue
                }
                // 抓哪根已在**按下那一刻**定死（见循环前的说明），这里只逐帧跟手。
                // dragActive 与首帧落点：划线工具新建在 down 时就给过（单击落线靠那一帧）；
                // 拖已有的线没有 down 帧 —— 这里的第一帧 MOVE 才算「真的开始拖」，
                // 落点补上 [grabOffsetY]（线不往指尖跳）。置位是幂等的，两边都成立。
                dragActive = true
                onHorizontalDrag(
                    horizontalGrab,
                    (y + grabOffsetY)
                        .coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx),
                )
                continue
            }
            if (!longPressActive && pressed.size == 1) {
                val held = primary.uptimeMillis - first.uptimeMillis
                if (held >= longPressMs && travelled <= touchSlop) {
                    longPressActive = true
                    // 长按 = 看读数，不是「选线」：按在线上按住不动时不再顺手选中它
                    // （选中要由一次真正的点击产生，否则松手后线莫名变成可拖状态）
                    pendingSelect = null
                    tapOnBlank = false
                }
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
                // 位移一旦超过 slop，长按就再也不可能成立（见上方的长按条件）——
                // 这场手势是平移/翻页：遗留的十字光标到此为止，不陪着图一起滑
                if (travelled > touchSlop) onCrosshair(null)
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
                                //
                                // 比例要乘回当前缩放倍数：pricePan 相对的是未缩放量程，
                                // 手指走的是屏幕上缩放后的量程（见 ChartGesture.pricePanFraction）。
                                onPricePan(
                                    ChartGesture.pricePanFraction(deltaY, height, priceZoom()),
                                )
                            }
                        }
                    }

                    null -> Unit
                }
            }
        }
        // 松手一律落库（新线新建、已有线改价）。**拖动不再能删线**（2026-10-24 撤）：
        // 拖动是「改线」的手势，改到一半被当成删除反直觉；删除已归选中后的右侧编辑栏。
        if (dragActive) {
            onHorizontalCommit(horizontalGrab)
        }
        // 直线工具两敲（2026-10-27 口径）：**移动只调准星，轻点才落点**。
        // 这一场是移动（trendMoved）⇒ 什么都不落，准星留在原地等下一次轻点确认；
        // 轻点 ⇒ 按准星当前位置（trendLast，down 已就位）定种子 / 成交第二点。
        if (trendMoved && (trendMode == TrendMode.SEED || trendMode == TrendMode.PLACE2)) {
            trendMode = null
        }
        when (trendMode) {
            TrendMode.SEED -> trendAnchorAt(trendLast.x, trendLast.y)?.let(onTrendSeed)
            null -> Unit
            TrendMode.PLACE2 -> {
                val seed = onTrendSeedGet()
                val seedPx = seed?.let(trendAnchorPx)
                val end = trendAnchorAt(trendLast.x, trendLast.y)
                if (seed == null || seedPx == null || end == null) {
                    onTrendSeed(null)
                    onTrendDelete(0L)
                } else if ((trendLast - seedPx).getDistance() < grabPx) {
                    // 准星还压在种子上就点了 = 两点定不出一条线：撤销这一笔（清种子并收起工具）
                    onTrendSeed(null)
                    onTrendDelete(0L)
                } else {
                    // 成交回调拿「最后一帧草稿」落库：轻点这一刻补发一帧再提交
                    onTrendDrag(0L, seed, end)
                    onTrendCommit(0L)
                    onTrendSeed(null)
                }
            }
            // 平移与改角度都以「松手落库」收尾（拖动过程中只发预览，见 onTrendDrag）
            TrendMode.MOVE, TrendMode.ANCHOR -> onTrendCommit(trendGrabbedId)
        }
        // 单击落定（2026-10-24）：命中一条还没选中的线 → 选中它；按在空白 → 取消选中。
        // 循环里那道作废判定用的是**上一帧**的 travelled，快速一滑（只有一帧 MOVE 就抬手）
        // 会漏判 —— 所以这里再用最终累加值判一次：位移超过 slop 的一律不算点击。
        when {
            travelled > touchSlop -> Unit
            pendingSelect != null -> onSelectLine(pendingSelect)
            tapOnBlank -> onSelectLine(null)
            else -> Unit
        }
        // 直线工具的十字准星**松手不清**（2026-10-14）：选中工具它就在（没动过画图中央），
        // 松手 = 定点，准星留在原地等下一次拖动；第二敲成交后工具收起，
        // 状态随 drawTool 变化由 remember key 作废，绘制端也不再画。
        // 松手**不清**十字光标：长按把它停在最后那根上，价格标、日期标与 K 线详情小条
        // 一并留在那里 —— 手指一抬读数就消失的话根本来不及看。它交给「画布上的其他操作」
        // 收走：平移/双指缩放在手势中清（见上），轻点与其他手势在这里清。
        if (!longPressActive) onCrosshair(null)
        onGestureEnd()
    }
}

/**
 * 解锁态下「按下点到了什么」→ 这一场单指的用途（2026-10-24「先单击选中」口径）。
 */
internal enum class LineTouch {
    /** 命中的就是已选中的那条：当场接管，整场跟手拖动（不用长按）。 */
    Drag,

    /** 命中了一条没选中的线：本场只当点击候选，松手（没滑走）才选中它。 */
    Select,

    /** 没命中：松手取消选中，拖动照旧是平移/缩放画布。 */
    None,
}

/**
 * 一条线能不能被选中：**草稿不算**（告警线的 `ruleId == null`、画线的 `id == 0`）。
 *
 * 它们只存在于「手上正拖着的那一笔」，而那次手势里不会再有第二次按下；
 * 何况把草稿当目标会让选中态指向一条随时会被落库改名换姓的临时对象。
 */
internal fun LineRef.selectable(): Boolean = when (this) {
    is LineRef.Alert -> ruleId != null
    is LineRef.Guide -> lineId != 0L
}

/**
 * 按下命中 [hit]、当前选中 [selected] 时，这一场单指的用途。
 *
 * 判据只有两条：**只有点到已选中的那条才直接拖**，其余命中一律先「选中」。
 * 拖动因此不再要求长按（长按还给十字光标），但也必须先点一下 ——
 * 一次平移的起手不会再顺手把路过的线拖走。
 *
 * [dragAllowed] 是「这条命中算不算真的抓到了可拖的地方」，默认 true。
 * 水平类线（告警线 / 水平线）**只有两端把手算抓到**（见 [onFlatGrip]）：
 * 线身横贯整个绘图区，若按住线身就算抓到，上下平移图表时起手压到线就会把线拖走 ——
 * 2026-11-21 用户报的「上下移动图表，直线穿过的位置变了」正是这个。
 * 传 false 时降级成 [LineTouch.Select]（本场只当点击，拖动交给画布平移）。
 */
internal fun lineTouch(
    hit: LineRef?,
    selected: LineRef?,
    dragAllowed: Boolean = true,
): LineTouch = when {
    hit == null || !hit.selectable() -> LineTouch.None
    hit == selected && dragAllowed -> LineTouch.Drag
    else -> LineTouch.Select
}

/**
 * 落点是否抓住了水平类线两端那枚把手（圆心在 [insetPx] 与 `plotWidthPx - insetPx`，同高 [yPx]）。
 *
 * 纯函数、不碰 Compose：这是「拖动线」唯一的入口，判错了要么「拖图表把线拖走」、
 * 要么「抓半天拖不动」，两种故障方向相反、用户都很难描述清楚，值得直接单测
 * （见 `FlatGripTest`）。半径按欧氏距离判，不是方形，指尖斜着落下也算抓到。
 */
internal fun onFlatGrip(
    point: Offset,
    yPx: Float,
    plotWidthPx: Float,
    insetPx: Float,
    hitPx: Float,
): Boolean {
    if (!point.x.isFinite() || !point.y.isFinite() || !yPx.isFinite()) return false
    if (!plotWidthPx.isFinite() || !insetPx.isFinite() || !hitPx.isFinite()) return false
    if (hitPx <= 0f || plotWidthPx <= 0f) return false
    val dy = point.y - yPx
    if (abs(dy) > hitPx) return false
    if (hypot(point.x - insetPx, dy) <= hitPx) return true
    return hypot(point.x - (plotWidthPx - insetPx), dy) <= hitPx
}

/** 抓到的是直线上的哪一处：两端改角度，中点整体平移。 */
internal enum class TrendGrip {
    ANCHOR_A,
    ANCHOR_B,
    MID,
}

/**
 * 落点是否抓住了选中直线的把手（两端锚点 + 中点），以及抓的是哪个。
 *
 * 与 [onFlatGrip] 同一口径：**这是直线唯一的编辑入口**。直线在屏幕上向左右沿无限延长
 * （见 `drawDrawLines`），按线身判命中等于给整张图斜切一条带子 —— 已在选中态时
 * 纵向拖动量程、起手压在带上就会把线拖走（2026-11-22 用户「缩放纵坐标直线会漂移」，
 * 桌面端同一处此前已按把手化修过）。
 *
 * 端点优先于中点：两点挨得很近（短线段）时中点必然也落在命中圈里，
 * 而「改角度」比「整体挪」常用，也让重画一句的成本更低。
 */
internal fun onTrendGrip(
    point: Offset,
    p1: Offset,
    p2: Offset,
    hitPx: Float,
): TrendGrip? {
    if (hitPx <= 0f || !hitPx.isFinite()) return null
    if (!point.x.isFinite() || !point.y.isFinite()) return null
    if (!p1.x.isFinite() || !p1.y.isFinite() || !p2.x.isFinite() || !p2.y.isFinite()) return null
    val da = hypot(point.x - p1.x, point.y - p1.y)
    val db = hypot(point.x - p2.x, point.y - p2.y)
    if (da <= hitPx && da <= db) return TrendGrip.ANCHOR_A
    if (db <= hitPx) return TrendGrip.ANCHOR_B
    val midX = (p1.x + p2.x) / 2f
    val midY = (p1.y + p2.y) / 2f
    // 中点再收一档：它落在图中央，而「在主图中央上下拖」正是调价格量程的常用起手位，
    // 命中圈给太大等于把半个屏幕变成「一按就拖线」（2026-11-22 用户「纵坐标缩放直线漂移」）。
    return if (hypot(point.x - midX, point.y - midY) <= hitPx * MID_HIT_SHARE) {
        TrendGrip.MID
    } else {
        null
    }
}

/** 中点把手的命中半径相对两端把手的比例（见 [onTrendGrip]）。 */
private const val MID_HIT_SHARE = 0.72f

/**
 * 一条已有**水平类**的线（告警线 / 水平线）在主图上的纵向像素落点，手势按它决定抓哪根。
 *
 * [ref] 带上这条线属于哪个域：解锁态下单指只有一个水平通道，落库回调要靠它分流。
 */
internal data class HorizontalAnchor(val ref: LineRef, val yPx: Float)

/** 直线工具下单指这场的定性。 */
private enum class TrendMode {
    /** 还没种子：拖动只挪准星；**轻点**才在准星处落种子锚点。 */
    SEED,
    /** 已有种子：拖动只挪准星（预览线跟着走）；**轻点**才成交（准星压在种子上则撤销）。 */
    PLACE2,
    /** 抓住已有直线的**中点把手**：整根随手指平移。 */
    MOVE,
    /** 抓住已有直线**某一端把手**：这一端跟手、另一端钉住 —— 改角度。 */
    ANCHOR,
}

/** 一条已有直线当前的两端像素落点，手势按它就近抓取与平移。 */
internal data class TrendLinePixels(val id: Long, val p1: Offset, val p2: Offset)

/**
 * 把过 [a]、[b] 的**无限长直线**裁进矩形，返回矩形内那一段的两个端点；不相交返回 null。
 *
 * 注意是**无限直线**，不是线段：趋势线在屏幕上是贯穿到底的（两个锚点只是定斜率），
 * 所以 t 的初值取 ±∞ 而不是 [0,1] —— 这是上一版最大的错处（裁线段 ⇒ 线只画到第二个锚点，
 * 与「向两侧延长」的绘制语义打架，命中带也跟着错）。
 *
 * 用 Liang–Barsky 参数裁剪：不必先把斜率算出来，所以**近竖直的线也不会炸**（旧实现里
 * `(p2.y - p1.y) / dx` 在 `dx ≈ 0` 时得单独走一支，那一支又只按高度延长、与横向延长不同源）。
 *
 * 这一条是直线几何的唯一来源 —— 绘制段、命中带、把手位置全都从它出来。
 * 三者各算一次是过去几轮反复出错的根子（画得到点不着、把手画在屏幕外）。
 */
internal fun clipLineToRect(
    a: Offset,
    b: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Pair<Offset, Offset>? {
    if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return null
    if (!(right > left) || !(bottom > top)) return null
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return null

    var t0 = Float.NEGATIVE_INFINITY
    var t1 = Float.POSITIVE_INFINITY
    // p < 0 是「从这个边界走出去」，p > 0 是「从这个边界走进来」
    fun clip(p: Float, q: Float): Boolean {
        if (abs(p) < 1e-6f) return q >= 0f
        val r = q / p
        if (p < 0f) {
            if (r > t1) return false
            if (r > t0) t0 = r
        } else {
            if (r < t0) return false
            if (r < t1) t1 = r
        }
        return true
    }
    if (!clip(-dx, a.x - left)) return null
    if (!clip(dx, right - a.x)) return null
    if (!clip(-dy, a.y - top)) return null
    if (!clip(dy, bottom - a.y)) return null
    if (!(t1 > t0) || t0.isInfinite() || t1.isInfinite()) return null
    val s = Offset(a.x + dx * t0, a.y + dy * t0)
    val e = Offset(a.x + dx * t1, a.y + dy * t1)
    if (!s.x.isFinite() || !s.y.isFinite() || !e.x.isFinite() || !e.y.isFinite()) return null
    return s to e
}

/**
 * 按「点到线段的距离」就近取一条直线，超出 [slopPx] 返回 null（= 落一颗新种子）。
 *
 * 传进来的 [TrendLinePixels] 是**裁剪到主图矩形的可见段**（见 `trendPixels`），
 * 所以「点在延长段上」也算命中 —— 那一段本来就画在屏幕上，点它理应有回执。
 */
internal fun List<TrendLinePixels>.closestTrend(point: Offset, slopPx: Float): TrendLinePixels? =
    nearestTrend(point, slopPx)?.first

/** 同 [closestTrend]，但把距离也带出来（解锁态要与水平类的线比远近，见 [nearestHorizontal]）。 */
internal fun List<TrendLinePixels>.nearestTrend(
    point: Offset,
    slopPx: Float,
): Pair<TrendLinePixels, Float>? = map { it to distanceToSegment(point, it.p1, it.p2) }
    .filter { it.second <= slopPx }
    .minByOrNull { it.second }

/** 点到线段 ab 的最短距离（垂足在线段外时取较近的端点）。 */
internal fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val wx = point.x - a.x
    val wy = point.y - a.y
    val c1 = vx * wx + vy * wy
    if (c1 <= 0f) return (point - a).getDistance()
    val c2 = vx * vx + vy * vy
    if (c2 <= c1) return (point - b).getDistance()
    val t = c1 / c2
    return (point - Offset(a.x + vx * t, a.y + vy * t)).getDistance()
}

/**
 * 按 y 像素就近取一条水平类的线，超出 [slopPx] 返回 null（= 在这里新建一根）。
 *
 * 只做纵向距离：划线要改的是价格，手指横向落在哪儿无关紧要 ——
 * 判进绘图区的话，贴着线右端点一下就变成新建一根了。
 */
internal fun List<HorizontalAnchor>.closestHorizontal(yPx: Float, slopPx: Float): LineRef? =
    nearestHorizontal(yPx, slopPx)?.first

/**
 * 同 [closestHorizontal]，但把**距离**也带出来。
 *
 * 解锁态下水平类的线与直线同场竞争：「按下的那一点离哪条更近就抓哪条」——
 * 一条水平线与一条斜线在交点附近都够得着，只有比过距离才知道该抓谁，
 * 各自判一次再按「先水平后直线」定优先级会出现「点着看得见的那条、拖走的却是底下那条」。
 */
internal fun List<HorizontalAnchor>.nearestHorizontal(
    yPx: Float,
    slopPx: Float,
): Pair<LineRef, Float>? = map { it.ref to abs(it.yPx - yPx) }
    .filter { it.second <= slopPx }
    .minByOrNull { it.second }

/** 「复位」小标的文案（用于实测宽度，与 [PriceScaleResetBadge] 共用一份）。 */
private const val RESET_BADGE_TEXT = "复位"

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

/**
 * 价格列里文字距画布右缘的留白（dp）。
 *
 * 列本身是**独立的一条**（宽按这一列最宽的文本量出，见 [ChartGeo.AXIS_LABEL_MIN_WIDTH_DP]），
 * 本值只管这条列内部再退几像素 —— 刻度数字、行情价签、划线价牌共用它，右缘才对得齐。
 *
 * 历史：2026-11-21 曾取消整列（价签浮在图上、绘图铺满），2026-11-22 连刻度数字一起收掉；
 * 2026-11-23 用户「把纵坐标价格区加回来」，整列连同刻度一起恢复。
 */
private const val AXIS_GUTTER_DP = 2f

/**
 * 同一列里两条刻度文字的最小间距（dp）：不足 [刻度字高 + 本值] 就把下面那条丢掉。
 * 只是兜底 —— 条数已经按可用高度算过（见 PriceAxisLabels 的 tickCount）。
 */
private const val AXIS_LABEL_MIN_GAP_DP = 4f

/**
 * 单族指标读数固定一行（如一整排 MA 挤在一行、BOLL 三条挤在一行）。
 * 多族指标不再挤同一行，而是各占一行（见调用处按族拆分的 Column）；
 * 某一族过宽时宁可省略尾部，也不在本行内换行。
 */
private const val READOUT_MAX_LINES = 1

/** 指标读数带的字号：比 labelSmall(11sp) 再小一档，让整族数值稳定落在一行。 */
private val READOUT_FONT_SIZE = 9.sp

/**
 * 右侧纵轴**刻度数字**的字号（2026-11-23 第三轮：9 → 10sp，用户「价格轴太窄了」—— 字太小同样读作窄）。
 *
 * 2026-11-23 第四轮起**只归刻度**：价签字号独立成 [PRICE_TAG_FONT_SIZE]。
 * 两者曾经共用一个常量（「牌子的小字比列里的刻度还小，读起来像两张不同层级的贴纸」），
 * 但刻度现在整档关掉（[AXIS_TICKS_VISIBLE]），继续共用只会让「缩价签」顺手把刻度也缩掉，
 * 将来重新打开刻度又得二选一。分开之后各调各的，互不牵连。
 */
private val AXIS_FONT_SIZE = 10.sp

/**
 * 价格签（最新价 / 十字光标 / 极值 / 划线牌 / 划线备注 / 日期标）的字号。
 *
 * 10 → 9sp（2026-11-23 第四轮用户「价格签太大了，缩小点」）：上一轮为了消除「细弱感」
 * 把牌子从 8sp 提到 10sp，同时把内边距一起加厚，结果整枚色块偏胖，右侧那一列跟着变宽、
 * 把绘图区压窄。现在回调到 9sp + 收薄内边距，牌面小一档、列宽也跟着收（列宽是量出来的，
 * 见 [rememberAxisColumnWidth]，不需要另改死值）。
 */
private val PRICE_TAG_FONT_SIZE = 9.sp

/**
 * 右侧价格列里**是否渲染刻度数字**（主图网格步长值 + 副图两端值）。
 *
 * 关（2026-11-23-4 用户「价格轴上不显示价格段」）。这一列从此只住价签：
 * 最新价 / 可见区间极值 / 十字光标 / 划线牌 —— 都是「指着哪就是哪」的读数。
 *
 * 三起三落的历史，别再当意外：
 * 1. 2026-11-22 用户「不显示纵轴价格间隔」⇒ 连 `PriceAxisLabels` / `AxisLabel` 一起删了；
 * 2. 2026-11-23 用户「把纵坐标价格区加回来」⇒ 刻度与价格列一起恢复；
 * 3. 2026-11-23 同日用户「价格轴上不显示价格段」⇒ 价格列留、刻度关。
 *
 * 因此这次**不删函数**（[PriceAxisLabels] / [SubAxisLabels] 原样保留，只由本开关控制）：
 * 删了再加回的成本，远高于留一个布尔常量。打开它时列宽会自动把刻度文本算进去
 * （见 `rememberAxisColumnWidth` 里的 `widestAxis`），不需要改第二处。
 */
private const val AXIS_TICKS_VISIBLE = false

/**
 * 刻度文字的小数位：**由步长决定**，与 tickSize 无关。
 *
 * step = 1000 → 0 位（8000、9000）；step = 0.5 → 1 位；step = 0.000002 → 6 位。
 * 纯函数，直接单测（见 `AxisDecimalsTest`）：这一步决定「刻度看起来像不像刻度」。
 */
internal fun decimalsForStep(step: Double): Int {
    if (!step.isFinite() || step <= 0) return 0
    val exponent = floor(log10(step)).toInt()
    return (-exponent).coerceIn(0, 8)
}

/**
 * **刻度**文字样式（主图价格刻度、副图刻度、时间轴刻度）。
 *
 * 2026-11-23 第四轮起不再兼管价格签 —— 价签有自己的 [rememberPriceTagStyle]（9sp）。
 * 这里的 10sp 只在 [AXIS_TICKS_VISIBLE] 打开时才会真正上屏。
 */
@Composable
private fun rememberAxisTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = AXIS_FONT_SIZE,
        lineHeight = 13.sp,
        letterSpacing = 0.sp,
    )

/**
 * 价格标（最新价/十字光标/告警线/日期标）文字：**独立一档 [PRICE_TAG_FONT_SIZE]（9sp）**。
 *
 * 不再从 [rememberAxisTextStyle] `copy` 出来 —— 刻度（10sp）现在整档关掉，
 * 继续挂在它身上等于「价签的字号由一段根本不画的文字决定」（2026-11-23 第四轮拆开）。
 * 行高同步收到 11sp：色块的厚度 = 行高 + 上下内边距，字号缩了行高不缩，
 * 牌子还是原来那么厚，看着仍然「大」。
 */
@Composable
private fun rememberPriceTagStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = PRICE_TAG_FONT_SIZE,
        lineHeight = 11.sp,
        letterSpacing = 0.sp,
    )

/**
 * 量列宽时主图刻度的条数样本：取 `PriceAxisLabels` 允许的**最大**条数（那里是 `coerceIn(2, 8)`）。
 *
 * 条数越多 ⇒ `gridStep` 越小 ⇒ 小数位越多 ⇒ 文本越长。拿最大值当样本，列宽就与
 * 「这一刻主图有多高」解耦：横屏/竖屏切换导致条数在 2~8 之间变时，列宽不会跟着跳。
 */
private const val AXIS_SAMPLE_TICKS_MAIN = 8

/**
 * 价格列宽度的最终夹取。**纯函数**：不含任何 Compose 依赖，直接单测（见 `AxisColumnWidthTest`）。
 *
 * 取两类的较宽者：
 * - 价签（最新价 / 极值 / 划线牌）是**实心底块**，文字左右各多一个 [badgePadH]；
 * - 刻度数字是裸文字，但它可能比价签更长（步长小 ⇒ 小数位多）。
 *
 * 最后加一个贴边留白 [gutter]（右缘对齐用），再夹进 `[minWidthPx, maxWidthPx]`。
 * 上限先跟下限取大，避免画布极窄时 `coerceIn` 抛 `IllegalArgumentException`。
 */
internal fun axisColumnWidthPx(
    widestTagTextPx: Float,
    widestAxisTextPx: Float,
    badgePadH: Float,
    gutter: Float,
    minWidthPx: Float,
    maxWidthPx: Float,
): Float {
    val tagWidth = widestTagTextPx + badgePadH * 2f
    val content = max(tagWidth, widestAxisTextPx) + gutter
    return content.coerceIn(minWidthPx, maxWidthPx.coerceAtLeast(minWidthPx))
}

/**
 * 价格列该占多宽：**按这一列里真正要显示的最宽那段文字量出来**，不用死值
 * （58dp 装不下 `60,701.58`，价签左溢压 K 线 —— 2026-11-23 用户报）。
 *
 * 三条口径必须与住户一致，否则「量出来的宽」和「真画出来的宽」会对不上：
 * - 价签（[PriceTag] / [LineBadges]）小数位跟 `tickSize` 走，字号是 [rememberPriceTagStyle]；
 * - 主图刻度（[PriceAxisLabels]）小数位跟**步长**走，字号是 [rememberAxisTextStyle]；
 * - 副图刻度（[SubAxisLabels]）`compactAxis` 的走 K/M/B 缩写，其余固定两位小数。
 *
 * 入参用**自动量程**（不是叠加了纵轴缩放的量程）：`priceZoom` 一变步长就变，
 * 拿缩放后的量程算宽会让列宽逐帧抖，整张图横向跳。
 */
@Composable
private fun rememberAxisColumnWidth(
    range: ValueRange?,
    lastPrice: Double?,
    subRanges: List<ValueRange>,
    subPanes: List<SubPaneData>,
    tickSize: BigDecimal?,
    canvasWidthPx: Float,
    density: Density,
): Float {
    val measurer = rememberTextMeasurer()
    val axisStyle = rememberAxisTextStyle()
    val tagStyle = rememberPriceTagStyle()
    // 价签样本：最新价 + 量程两端（划线价牌落在两者之间，字符数不会更长）
    val tagTexts = remember(range, lastPrice, tickSize) {
        val decimals = PriceFormatter.decimalsFor(tickSize)
        buildList {
            lastPrice?.takeIf { it.isFinite() }?.let { add(PriceFormatter.localeNumber(it, decimals)) }
            if (range != null && range.high.isFinite() && range.low.isFinite()) {
                add(PriceFormatter.localeNumber(range.high, decimals))
                add(PriceFormatter.localeNumber(range.low, decimals))
            }
        }
    }
    // 刻度样本：主图两端 + 每块副图两端，各自口径与住户一致
    val axisTexts = remember(range, subRanges, subPanes) {
        buildList {
            if (range != null && range.high.isFinite() && range.low.isFinite()) {
                val decimals = decimalsForStep(range.gridStep(AXIS_SAMPLE_TICKS_MAIN))
                add(PriceFormatter.localeNumber(range.high, decimals))
                add(PriceFormatter.localeNumber(range.low, decimals))
            }
            subRanges.forEachIndexed { index, sub ->
                if (!sub.high.isFinite() || !sub.low.isFinite()) return@forEachIndexed
                if (subPanes.getOrNull(index)?.compactAxis == true) {
                    add(PriceFormatter.formatCompact(sub.high))
                    add(PriceFormatter.formatCompact(sub.low))
                } else {
                    // 与 `SubAxisLabels` 一字不差：非 compact 的副图刻度**固定两位小数**，
                    // 跟着步长算会量出更长的文本、把列白撑宽一截。
                    add(PriceFormatter.localeNumber(sub.high, PriceFormatter.DEFAULT_DECIMALS))
                    add(PriceFormatter.localeNumber(sub.low, PriceFormatter.DEFAULT_DECIMALS))
                }
            }
        }
    }
    return remember(tagTexts, axisTexts, axisStyle, tagStyle, canvasWidthPx, density) {
        val widestTag = tagTexts.maxOfOrNull { text ->
            measurer.measure(AnnotatedString(text), tagStyle, density = density).size.width
        } ?: 0
        // 刻度不渲染时**不许它参与量宽**：否则列会被一段根本不画的文字撑宽，
        // 「价格轴和价格签一样宽」立刻破掉（2026-11-23-4）。
        val widestAxis = if (AXIS_TICKS_VISIBLE) {
            axisTexts.maxOfOrNull { text ->
                measurer.measure(AnnotatedString(text), axisStyle, density = density).size.width
            } ?: 0
        } else {
            0
        }
        with(density) {
            axisColumnWidthPx(
                widestTagTextPx = widestTag.toFloat(),
                widestAxisTextPx = widestAxis.toFloat(),
                badgePadH = PRICE_BADGE_PAD_H_DP.dp.toPx(),
                gutter = AXIS_GUTTER_DP.dp.toPx(),
                minWidthPx = ChartGeo.AXIS_LABEL_MIN_WIDTH_DP.dp.toPx(),
                maxWidthPx = canvasWidthPx * ChartGeo.AXIS_LABEL_MAX_SHARE,
            )
        }
    }
}

/** 指标读数带的行高：与 [ChartGeo.READOUT_LINE_DP] 对齐，用来算读数带总高。 */
private val READOUT_LINE_HEIGHT = 12.sp

/**
 * K 线详情小条（[KlineChart] 的 candleReadout 槽）预留的高度：
 * 两行 9sp 文本（行高 11）+ 行距 2 + 与读数带之间的留白 4。
 * 小条本体是调用方传进来的 composable，画布猜不到它的尺寸，只能按约定夹表。
 */
private const val CANDLE_READOUT_BAND_DP = 28f

/**
 * 价格标（最新价/十字光标/告警线/划线牌/日期标共用）的水平内边距（dp）。
 *
 * 5.5 → 4.0（2026-11-23 第四轮用户「价格签太大」）：上一轮为了让 10sp 的字不顶满块边，
 * 把内边距从 3.5 加到 5.5，结果牌面整体偏胖 —— 左右各 5.5dp 等于给每枚价签多挂了 3dp 宽度，
 * 而这一列宽是按牌宽量出来的（见 `rememberAxisColumnWidth`）⇒ 列宽跟着胖、绘图区跟着窄。
 * 字号回 9sp 之后 4.0dp 足够让数字不贴边。
 */
private const val PRICE_BADGE_PAD_H_DP = 4f

/** 价格标的垂直内边距（dp）：与 [PRICE_BADGE_PAD_H_DP] 同批从 2 收到 1.5，色块随之变薄。 */
private const val PRICE_BADGE_PAD_V_DP = 1.5f


/*
 * 图上三类线的透明度口径：**不淡**。
 *
 * 2026-10-23 之前静态告警线压到 0.45、静态画线压到 0.75，理由是「参照线不该跟 K 线抢视线」。
 * 但线型统一成实线之后，唯一的分工只剩颜色（红 = 告警 / 黄 = 画线），一淡颜色就先糊：
 * 红会退成粉、黄会退成土黄，两种线在密集 K 线上反而分不出来了。于是全部实色，
 * 「手里那根最实」改用**加粗**表达（见 drawAlertLines / drawDrawLines）。
 */

/**
 * 直线把手（**只在选中态画**，见 [drawTrendGrips]）：两端圆点的半径。
 *
 * 未选中时一个点都不画（2026-11-22 用户）：两点是「这根线能编辑」的信号，
 * 平时挂着看着像数据标记，也让人以为可以直接拖 —— 而拖动线身拖走线正是要修的病。
 */
private val TREND_GRIP_DOT = 4.5.dp

/**
 * 直线把手的命中半径（dp）。
 *
 * ❗**故意比水平类那两枚（[FLAT_GRIP_HIT] 18dp）更小**（2026-11-22 真机第二轮）。
 * 20dp 时半径约 55px，中点那枚又正好落在屏幕中央 —— 「在主图中央上下拖」本是调价格
 * 量程的常用起手位，结果一按就进「整体平移」，用户看到的就是「纵坐标缩放，直线会漂移」。
 * 把手本来就有白心圆做视觉目标，12dp（直径约 7mm）足够指尖找到，宁可贵在「抓不住就再点一次」，
 * 也不要贵在「想缩量程却把线拖走了」。
 */
private val TREND_GRIP_HIT = 12.dp

/** 第一敲种子点的半径（dp）：比锚点大一档，提示「已经敲过一下了」。 */
private val TREND_SEED_DOT = 4.5.dp

/**
 * 选中一条**水平类**线（告警线 / 水平线）之后，两端那枚把手的位置与大小。
 *
 * 为什么必须有把手、而不许「按住线身拖」（2026-11-21 用户「上下移动图表，直线穿过的位置变了」）：
 * 水平线横贯整个绘图区，[ALERT_GRAB_WIDTH]（24dp）的抓取带等于把画布横切成一条带子 ——
 * 已选中的线上下拖动时，起手落在带子里就把线拖走了，用户却以为自己在平移图表
 * （桌面端同一处实测：线价 57500 → 56288.84，而纵向平移量一格没动）。
 * 现在**线身只负责「选中」**，改价只有抓住两端这枚点这一条路 ——
 * 于是「按在图上的任何位置拖动」都只平移画布。与桌面 `PRICE_HANDLE_INSET` 同一口径。
 */
private val FLAT_GRIP_INSET = 10.dp

/** 把手的圆点半径（dp）：比直线锚点（3dp）大一档，它就是「这里能抓」的形状。 */
private val FLAT_GRIP_DOT = 5.dp

/** 把手的命中半径（dp）。触摸比鼠标慷慨：指尖落点本身就有 ~8dp 误差。 */
private val FLAT_GRIP_HIT = 18.dp

/**
 * 选中一条线时的外发光：半透明同色、更宽，铺在本色线**下面**。
 *
 * 用「发光」而不是换色：红/黄本身就是语义（会响 / 用户记号），选中只是临时状态，
 * 借走颜色会让「这条线会不会响」在选中期间说不清。
 */
private const val LINE_SELECTED_HALO_ALPHA = 0.22f

/** 外发光的线宽（px）：比本色线的 2.5px 宽出一圈，看着像一层柔光。 */
private const val LINE_SELECTED_HALO_WIDTH = 9f

/** 全屏角标按钮的边长。 */
private val CORNER_BUTTON_SIZE = 26.dp

/**
 * 图内浮层（左下角「全屏」角标、右上角「复位」小标、右下角的显隐开关组）距绘图区边缘的内缩距离。
 * 几处浮层同用这一档值，贴角才对称。
 */
private val CORNER_BUTTON_INSET = 12.dp

/**
 * 划线的抓取容差：按下点离某根线在这个范围内就算抓它，否则新建一根。
 *
 * 比手指触摸半径略大 —— 屏幕上那根 1.5dp 的实线根本按不准，
 * 容差太小会让「微调已有预警」变成「凭空多出一条新预警」。
 */
private val ALERT_GRAB_WIDTH = 24.dp

/** 底右角显隐开关（「MA」按钮与划线眼睛）的外圈边长，两者同高。 */
private val OVERLAY_BUTTON_SIZE = 26.dp

/** 眼睛图标本身的大小（小于外圈背景，留出内边距）由 `EyeGlyph` 的默认值给出。 */
