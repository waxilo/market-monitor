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
 * 为什么要有这个类型：解锁态下「长按哪根就拖哪根」是**一个**手势通道，
 * 而落库回调（[KlineChart.onHorizontalDrag] / [KlineChart.onLineDropDelete]）只有一条 ——
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
    /** 指标划线规则挂的线在虚线左端画名称胶囊（如「1h MA30」）；手动预警价为 null。 */
    val label: String? = null,
)

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
 * 全屏右侧竖栏选中的划线工具。
 *
 * 一次只激活一种：单指手势在同一时刻只能有一种语义（拖价格线 / 拖水平线 / 落直线锚点），
 * 这与当年「划线开关」一个布尔的处境相同，只是把「划什么」从隐式变成显式单选。
 *
 * [ERASE] 是同一组里的第四枚：它不划线，而是**点线即删**（告警线 / 水平线 / 直线通吃）。
 *
 * 删除**只此一条路**。2026-10-12 之前还有第二条 —— 把线拖到画布右上角的垃圾桶上松手。
 * 那条路要求用户先知道有这回事、还得精准拖到一个角落（实际反馈就是「划的线无法删除」）；
 * 竖栏补上「点垃圾桶、再点线」之后，右上角那个落点成了同一件事的第二套交互，
 * 还平白压住图表右上角，于是整条撤掉：拖线松手一律落库，删除统一走 [ERASE]。
 */
enum class DrawTool { NONE, ALERT, HORIZONTAL, TREND, ERASE }

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
 * 这套视角是**全局一份**的看图习惯（与均线、周期条同口径）：切周期、换标的、重启
 * 都保持。图表自身不落盘 —— 实例首帧从 [KlineChart.initialView] 取值，之后每次
 * 变化经 `onViewChange` 交还调用方（详情页由 ViewModel 同步持有 + 防抖写 DataStore）。
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
     * 就是另一根蜡烛了。视角（[initialView]）不在此列 —— 它是全局看图习惯。
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
     * 指标划线里选了「不告警」的那几条：按各自配置的 K 线周期逐根画出的细灰参考曲线
     * （跟着指标周期走，不是定死的水平线）。不参与拖拽与删除，只随 [alertLinesVisible]
     * 那只眼睛显隐。划线模式下不画——那时单指属于告警线，图上再叠一层拖不动的灰线只会引人去拖它。
     */
    indicatorGuides: List<IndicatorGuideLine> = emptyList(),
    /**
     * 指标线（OFF）模式均线带在图上的锚点参考线：现价上方/下方最近成员各一条
     * **灰色水平线**（引擎按集合择近 + 穿越后静默换锚算好，始终至多两条、不会响）。
     * 与 [indicatorGuides] 同样只随眼睛显隐、不参与拖拽；划线模式下同样不画。
     */
    bandGuides: List<BandGuideLine> = emptyList(),
    /**
     * 当前激活的划线工具（全屏右侧竖栏单选）。非 NONE 时：单指只服务那一条通道，
     * **不再**平移价格刻度，也不会出十字光标；且**只新建、不抓已有线**（2026-10-14）——
     * 编辑旧线归解锁态「长按拖动」，工具模式下画布同时切换成纯净态（已划的线全部让位）。
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
    /** 删除模式（[DrawTool.ERASE]）点中一条视觉画线：调用方按 chart_line 主键删除。 */
    onDrawLineDelete: (Long) -> Unit = {},
    /**
     * **解锁态**：`drawTool == NONE` 时也能**长按**图上已有的线拖动它（不必先选工具）。
     *
     * true 时按下就近抓线但先挂起：按住不动够长按时长才开始拖（告警线 / 水平线改价位、
     * 直线整根平移）；没按够就滑走 = 普通画布手势，线纹丝不动（2026-10-15 拍板，
     * 防滑动误触把路过的线拖走）。落空与挂起作废都照旧还给画布
     * （平移、双指缩放、长按十字光标）。false（锁定态、竖屏）= 整张图只读，
     * 线一根都动不了 —— 竖屏正是 false：那里只显不可编，改线一律进全屏。
     */
    linesEditable: Boolean = false,
    /**
     * 画布右侧紧邻着一块「删除落点」（全屏的划线竖栏）：拖线时把线拖到画布**右缘之外**松手即删。
     *
     * 判据是落点 x 越出画布宽度 —— 竖栏正好从那里开始（它是 Row 里分出来的一栏，
     * 不浮在图上），所以这一条成立的前提就是「竖栏在画布右边」。
     * 由调用方声明而不是图表自己找：表盘只知道自己多大，不知道右边摆了什么。
     */
    lineDropToTrailingRail: Boolean = false,
    /**
     * 把线拖到 [lineDropToTrailingRail] 那块落点上松手：调用方删掉它，**不落库**。
     *
     * ref 为 null = 「本场是在新建一根、却落到了垃圾桶上」——等同放弃这一笔，
     * 调用方按当前工具清掉对应草稿（告警线草稿 / 画线草稿）。
     */
    onLineDropDelete: (LineRef?) -> Unit = {},
    /**
     * 划线是否画在图上（由右下角的小眼睛控制）。仅在非划线模式下生效 ——
     * 划线时线必须可见，否则是在拖一根看不见的线。
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
    /**
     * 删除模式（[DrawTool.ERASE]）点中一条告警线：调用方删除该 id 对应的规则
     * （id 为 null = 手上一笔还没落库的新线，等同于放弃）。
     */
    onAlertLineDelete: (Long?) -> Unit = {},
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
     * 这是**全局一份**的看图习惯：切周期、换标的都保持，初值来自 [initialView]，
     * 变化经 [onViewChange] 交回调用方落盘 —— 重启不重置。因此 `remember` **不挂** key：
     * 挂上就会在换标的/换周期时把视角弹回默认，正是这次要修掉的老行为。
     * 序列长短不一没关系 —— 越界由 [ChartViewport.clamp] 在每次查询时兜底，
     * 存进来的只是「意图」，不是「当前序列下的有效值」。
     */
    val barCount = series.size
    var viewport by remember { mutableStateOf(initialView.viewport) }
    var crosshair by remember(symbolKey, interval.storageKey) { mutableStateOf<Crosshair?>(null) }
    // 下标变化就同步给调用方的 K 线详情小条（长按换根、被其他操作清掉都走这里）
    LaunchedEffect(crosshair?.index) { onCrosshairIndexChange(crosshair?.index) }
    // 进划线工具清掉遗留的十字光标：那场手势已经是「拖线/落锚」，读数标留着会跟线上的价位标抢位置
    LaunchedEffect(drawTool) { if (drawTool != DrawTool.NONE) crosshair = null }
    // 整个图表共用一个测量器：每个子组件各建一个没有意义，还多一份缓存
    val measurer = rememberTextMeasurer()
    /**
     * 纵向缩放倍数（相对主图原始量程）。1f = 自动量程。
     * 手指在**价格轴一侧**上下拖会改它 —— 这是「拉伸/压缩金额刻度」的手势。
     *
     * 与 [viewport] 同为全局看图习惯（同样**不挂**序列 key）：换标的/换周期保持，
     * 初值来自 [initialView]，变化经 [onViewChange] 落盘。平移量按原始量程的比例
     * 折算，放到别的标的上本不是同一回事 —— 但用户选的就是全局口径，
     * 真不合适时图上常驻的「刻度已缩放 · 复位」小标一键清掉。
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
     * 换周期/换标的/首次进入后，新序列一就绪就把纵轴重定到最新价：最新价居中，
     * 量程对称向两侧外扩到仍能罩住全部可见蜡烛与叠加线（宁可多一片空白，不裁蜡烛）。
     *
     * 纵向缩放/平移是跨周期、跨标的全局保留的看图意图，旧意图套到新序列上可能
     * 把现价顶出量程 —— 现价虚线与现价牌会整个消失（见 [drawLastPrice]），
     * 而切周期后第一眼要看的就是现价。因此 (标的, 周期) 每变一次重算一遍缩放/平移；
     * 之后手势照常可再调，左下角小标仍可复位成贴合的自动量程。
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
            val baseSpan = base.high - base.low
            if (baseSpan <= ValueRange.EPSILON) null else Triple(close, base, baseSpan)
        }.filterNotNull().first()
        val (close, base, baseSpan) = ready
        // 居中所需的半程 = 现价到较远那侧极值的距离（上下两侧都罩住）
        val halfSpan = max(close - base.low, base.high - close)
        priceZoom = ((2.0 * halfSpan) / baseSpan).toFloat()
        pricePan = ((close - base.center) / baseSpan).toFloat()
            .coerceIn(base.panLimit(base, 2.0 * halfSpan))
    }

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

    /** 直线锚点（下标+价格）→ 像素：与蜡烛同一个 `xOf` 链，平移缩放后自然落位。 */
    fun anchorPx(anchor: TrendAnchor): Offset {
        val g = liveGeo
        val p = livePlot
        return Offset(
            ((anchor.index - p.start).toFloat() + 0.5f) * g.slot(p.visibleBars),
            g.yOf(alertRange.toFraction(anchor.price), g.mainTopPx, g.mainHeightPx),
        )
    }

    /** 落点 x 像素对应的分数蜡烛下标（[anchorPx] 的逆链）。 */
    fun indexAt(xPx: Float): Double {
        val g = liveGeo
        val p = livePlot
        val slot = g.slot(p.visibleBars)
        return if (slot > 0f) xPx / slot + p.start.toDouble() - 0.5 else Double.NaN
    }

    /** 已有直线的两端像素落点，供手势就近抓取与整体平移。 */
    fun trendPixels(): List<TrendLinePixels> =
        liveDrawLines.mapNotNull { line ->
            val a1 = line.anchor1
            val a2 = line.anchor2
            if (line.kind != ChartLineKind.TREND || a1 == null || a2 == null) null
            else TrendLinePixels(line.id, anchorPx(a1), anchorPx(a2))
        }

    /**
     * 删除模式下，落点 [point] 点中的是哪条线。
     *
     * 三类线**放在一起比距离**，取最近的一条 —— 图上它们本来就重叠（水平线与告警线只差一个
     * 价位、直线能从任何东西上穿过去），分类各判一次再按优先级挑，会出现「点着看得见的那条、
     * 删掉的却是它底下那条」。距离口径随线型：水平类是纵向距离，直线是点到线段距离
     * （与拖拽抓取同一个口径，[closestTrend] 复用）。
     *
     * 容差沿用拖拽的抓取半径（[ALERT_GRAB_WIDTH]，24dp）：比线本身的可视粗细宽得多，
     * 手指按不准也能中，而互相遮挡时仍由「谁更近」决出，不至于误删。
     */
    fun eraseHitAt(point: Offset): EraseHit? {
        val g = liveGeo
        if (!g.isUsable) return null
        val range = alertRange
        var best: EraseHit? = null
        fun consider(hit: EraseHit) {
            if (hit.distance <= alertGrabPx && hit.distance < (best?.distance ?: Float.MAX_VALUE)) {
                best = hit
            }
        }
        liveAlertLines.forEach { line ->
            val ruleId = line.ruleId ?: return@forEach
            val y = g.yOf(range.toFraction(line.price), g.mainTopPx, g.mainHeightPx)
            consider(EraseHit.Alert(ruleId, abs(y - point.y)))
        }
        liveDrawLines.forEach { line ->
            if (line.kind != ChartLineKind.HORIZONTAL || !line.price.isFinite()) return@forEach
            val y = g.yOf(range.toFraction(line.price), g.mainTopPx, g.mainHeightPx)
            consider(EraseHit.Draw(line.id, abs(y - point.y)))
        }
        trendPixels().closestTrend(point, alertGrabPx)?.let { t ->
            consider(EraseHit.Draw(t.id, distanceToSegment(point, t.p1, t.p2)))
        }
        return best
    }

    /**
     * 图内浮层（左下角「全屏」角标、右下角的显隐开关组）距绘图区边缘的内缩距离。
     * 几处浮层同用 [CORNER_BUTTON_INSET] 一档值，贴角才对称。
     */
    val cornerInsetPx = with(density) { CORNER_BUTTON_INSET.toPx() }

    /**
     * 这一帧画哪些告警线：
     * · 划线工具选中 = **纯净画布**（2026-10-14）：已划的线全部让位，只留手上那笔草稿
     *   （`dragging` 的那条）—— 落新线时满屏旧线只碍事，还容易被误认成自己刚拖的那根；
     * · 删除模式**全画**：能点的必须都看得见，藏起来一半等于「明明有这条线却删不掉」；
     * · 平时（NONE，含竖屏）随眼睛显隐。
     */
    val visibleAlertLines = when (drawTool) {
        DrawTool.ALERT -> alertLines.filter { it.dragging }
        DrawTool.ERASE -> alertLines
        else -> if (alertLinesVisible) alertLines else emptyList()
    }

    /**
     * 这一帧画哪些视觉画线：
     * · 告警工具下全不画 —— 那时单指属于告警线；
     * · 水平线/直线工具下**只留手上那笔草稿**（纯净画布，2026-10-14 改）——
     *   旧规则「只画同型的线」随「工具下不再抓已有线」一起废掉：编辑已归解锁态长按拖，
     *   工具模式下满屏旧线只剩干扰；
     * · 删除模式下**全画**（不看那只眼睛）—— 能点的必须都看得见；
     * · 平时（NONE，含竖屏）随眼睛显隐、只显不可编。
     */
    val visibleDrawLines = when (drawTool) {
        DrawTool.ALERT -> emptyList()
        DrawTool.NONE -> if (alertLinesVisible) drawLines else emptyList()
        DrawTool.ERASE -> drawLines
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
                // ❗**`linesEditable` / `lineDropToTrailingRail` 必须进 key**（2026-10-14 真机抓到）：
                // 这两个值是在**闭包外捕获**的普通参数，不在 key 里就永远不会刷新 ——
                // 全屏那侧「上锁」时 `drawTool` 从 `NONE` 变成……还是 `NONE`（工具本来就是收起的），
                // 于是 key 不变、协程不重启，手势仍然拿着**上锁前那份 `linesEditable = true`**，
                // 手柄里那句 `drawTool == NONE && linesEditable` 照旧成立 ⇒ **锁了还能把线拖走**，
                // 而 UI 那侧（提示条消失、按钮置灰）明明已经摆出「锁了」的样子。
                // 真机复现：上锁 → 竖拖 60px，`chart_line.price` 从 83312.04 变 83335.64。
                // 同一条理由也适用于 `atDropTarget` / `onLineDropDelete`：它们捕获的
                // `lineDropToTrailingRail` 与 `drawTool` 同样是过期值，落点判定会跟着错。
                //
                // 为什么可以放心重启：这两个开关只由竖栏那两枚按钮点击改变，而点按钮时手上
                // 不可能同时按在图上，不存在「手势进行中被重启」的场景。
                //
                // 标的与周期**必须**进 key，原因是另一回事：十字光标与价格基准
                // （priceBase）仍挂在 `remember(symbolKey, interval.storageKey)` 上，
                // 换周期会**重建 MutableState**，而本协程的闭包捕获的是创建那一刻的
                // 委托对象。key 不含周期时协程不重启，手势就一直在写**已经被丢弃的旧
                // state**，渲染读的却是新 state —— 表现为「切过周期的图十字光标不出来、
                // 捏合时价格基准冻结失效（量程逐帧抖）」。视窗与纵向刻度**不在此列**：
                // 它们是全局视角偏好，不挂序列 key（见上），委托永远指向活的 state。
                .pointerInput(drawTool, linesEditable, lineDropToTrailingRail, symbolKey, interval.storageKey) {
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
                        // 落点越过画布右缘 = 已经拖到竖栏那一栏上（它是 Row 里分出来的一栏，
                        // 正好从画布右缘开始）。用 canvasSize 的实时值读 —— 本地委托属性
                        // 在 lambda 里每次调用都会重新求值，旋屏后不会拿着旧宽度判。
                        atDropTarget = { x -> lineDropToTrailingRail && x >= canvasSize.width },
                        onLineDropDelete = onLineDropDelete,
                        eraseHitAt = ::eraseHitAt,
                        // 告警线走规则那条回调（顺带清掉它的通知配置），画线走 chart_line 那条
                        onEraseAlertLine = { id -> alertDelete(id) },
                        onEraseDrawLine = { id -> drawDelete(id) },
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
                                // 拖到最左端还继续往右拖 = 要看更早的历史（PRD FR-2.2）。
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
                                if (deltaBars > 0f && moved.atOldestEdge(bars)) liveLoadMore()
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
                if (alertLinesVisible && drawTool == DrawTool.NONE) {
                    drawIndicatorGuides(indicatorGuides, series, plot, geo, palette, mainRange)
                    drawBandGuides(bandGuides, geo, palette, mainRange)
                }
                if (visibleAlertLines.isNotEmpty()) drawAlertLines(visibleAlertLines, geo, palette, mainRange)
                // 视觉画线压在告警线之后、蜡烛之上同区：它是用户手写的「记号」，
                // 被预警虚线盖住时无所谓，反过来虚线该让位给刚拖的实线
                if (visibleDrawLines.isNotEmpty()) {
                    drawDrawLines(visibleDrawLines, geo, palette, mainRange, ::anchorPx)
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
            visibleAlertLines.forEach { line ->
                AlertLinePriceBadge(
                    price = line.price,
                    range = mainRange,
                    geo = geo,
                    density = density,
                    tickSize = tickSize,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                line.label?.let { label ->
                    AlertLineLeftTag(
                        label = label,
                        price = line.price,
                        range = mainRange,
                        geo = geo,
                        density = density,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
            // 水平画线也要给出价位：与告警线同一只右端标，落点全凭感觉是划不准的
            visibleDrawLines.forEach { line ->
                if (line.kind == ChartLineKind.HORIZONTAL && line.price.isFinite()) {
                    AlertLinePriceBadge(
                        price = line.price,
                        range = mainRange,
                        geo = geo,
                        density = density,
                        tickSize = tickSize,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
            SubAxisLabels(series.subPanes, subRanges, geo, density, Modifier.align(Alignment.TopStart))
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
        // 纵向刻度被调过就提示一次并给一键复位：不然用户会以为「图怎么长这样」，
        // 而且没有任何办法回去（视角是全局持久化的，重启也带着走）。
        // 复位只清纵向、保留横向视窗，并同样经 onViewChange 落盘 —— 按一次就彻底忘掉。
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
        band = scheme.primary.copy(alpha = 0.08f),
        crosshair = scheme.onSurface,
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
    /**
     * 角色 → 颜色。调色板色位（MA 八位 / EMA 八位 / BOLL 三位）直接按 ordinal 取
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

    // 实体随槽宽等比。曾钳 26px 上限：放大/短序列撑满后实体不再变宽、间距继续拉大，
    // 就是「实体偏窄、间距巨大」那个散开观感的来源（2026-10-03 去上限）。
    fun bodyWidth(visibleBars: Float): Float = max(1f, slot(visibleBars) * BODY_SHARE)

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
    series.overlay.bandFill?.let { (upper, lower) ->
        bandPath(upper, lower, plot, geo, range)?.let { drawPath(it, palette.band) }
    }
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
        if (path != null) drawPath(path, palette.label.copy(alpha = 0.7f), style = Stroke(width = 1.5f))
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
            strokeWidth = 2.5f,
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
            strokeWidth = if (line.dragging) 3f else 2.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
    }
}

private fun DrawScope.drawDrawLines(
    lines: List<DrawGuideLine>,
    geo: ChartGeo,
    palette: ChartPalette,
    range: ValueRange,
    anchorPx: (TrendAnchor) -> Offset,
) {
    lines.forEach { line ->
        val color = palette.crosshair.copy(alpha = if (line.dragging) 1f else DRAW_LINE_ALPHA)
        val width = if (line.dragging) 3f else 2f
        when (line.kind) {
            ChartLineKind.HORIZONTAL -> {
                if (!line.price.isFinite()) return@forEach
                val y = geo.yOf(range.toFraction(line.price), geo.mainTopPx, geo.mainHeightPx)
                if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return@forEach
                drawLine(color, Offset(0f, y), Offset(geo.plotWidthPx, y), width)
            }

            ChartLineKind.TREND -> {
                val a1 = line.anchor1 ?: return@forEach
                val a2 = line.anchor2 ?: return@forEach
                val p1 = anchorPx(a1)
                val p2 = anchorPx(a2)
                if (!p1.x.isFinite() || !p1.y.isFinite() || !p2.x.isFinite() || !p2.y.isFinite()) {
                    return@forEach
                }
                val dx = p2.x - p1.x
                if (abs(dx) < 0.5f) {
                    // 近似竖直的直线：沿 x 画满绘图区高度（斜率除法在这种角度会炸）
                    drawLine(
                        color,
                        Offset(p1.x, geo.plotTopPx),
                        Offset(p1.x, geo.plotHeightPx),
                        width,
                    )
                } else {
                    // 两点定线后**向绘图区左右沿延长**：趋势线看的是走向，不该断在锚点上
                    val m = (p2.y - p1.y) / dx
                    val y0 = p1.y - m * p1.x
                    val yW = y0 + m * geo.plotWidthPx
                    if (y0.isFinite() && yW.isFinite()) {
                        drawLine(color, Offset(0f, y0), Offset(geo.plotWidthPx, yW), width)
                    }
                }
                // 锚点实心小圆：直线被延长后端点不在线段可见范围内，不点个点抓取没有着落
                drawCircle(color, radius = TREND_ANCHOR_DOT.toPx(), center = p1)
                drawCircle(color, radius = TREND_ANCHOR_DOT.toPx(), center = p2)
            }
        }
    }
}

/** 直线工具落第一下的种子点：比锚点略大，提示「这里已经敲过一下了」。 */
private fun DrawScope.drawTrendSeedDot(center: Offset, palette: ChartPalette) {
    if (!center.x.isFinite() || !center.y.isFinite()) return
    drawCircle(palette.crosshair.copy(alpha = 0.9f), radius = TREND_SEED_DOT.toPx(), center = center)
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
 * 十字光标处的日期标：贴在时间轴上、与竖线同列。
 *
 * 与 [CrosshairPriceBadge]（横线读数贴右侧价格轴）成对 —— 十字光标的两条线各有一个读数：
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
 * 告警线的价格标。与十字光标的价格标同一套定位，只是 y 由**已保存的价格**反算 ——
 * 原始的落点像素并没有意义（量程可能已经被缩放/平移过），价格才是唯一真相。
 * 线的指标身份不标在这里（右缘只放价位），画在虚线左端（见 [AlertLineLeftTag]）。
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
 * 虚线左端的指标名称胶囊（如「1h MA30」）：右侧价签只回答「多少钱」，
 * 几条线挤在一起时「这是哪条」靠左端这颗认。跟着线与价签同显隐（眼睛开关/划线模式）。
 */
@Composable
private fun AlertLineLeftTag(
    label: String,
    price: Double,
    range: ValueRange,
    geo: ChartGeo,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val y = geo.yOf(range.toFraction(price), geo.mainTopPx, geo.mainHeightPx)
    if (y !in geo.mainTopPx..(geo.mainTopPx + geo.mainHeightPx)) return
    val colors = MarketTheme.colors
    val style = rememberPriceTagStyle()
    val measurer = rememberTextMeasurer()
    val textHeight = remember(label, style, density) {
        measurer.measure(AnnotatedString(label), style, density = density).size.height.toFloat()
    }
    val padH = with(density) { PRICE_BADGE_PAD_H_DP.dp.toPx() }
    val padV = with(density) { PRICE_BADGE_PAD_V_DP.dp.toPx() }
    Text(
        text = label,
        modifier = modifier
            .offset(
                x = 4.dp,
                // 与右侧价签同一套垂直居中：以虚线为中心
                y = with(density) { (y - textHeight / 2f - padV).toDp() },
            )
            .clip(Radius.xsShape)
            .background(colors.ink)
            .padding(horizontal = with(density) { padH.toDp() }, vertical = with(density) { padV.toDp() }),
        style = style,
        color = colors.paper,
        maxLines = 1,
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
    val style = rememberPriceTagStyle()
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

    panes.forEachIndexed { index, pane ->
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
                text = if (pane.compactAxis) PriceFormatter.formatCompact(value)
                else PriceFormatter.localeNumber(value, PriceFormatter.DEFAULT_DECIMALS),
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
 *   整体平移已有直线不再是这个工具的职责（归解锁态长按拖）；
 * · ERASE：不拖线，只在松手时删掉按下的那一点命中的线（滑走即作废）。
 * 双指缩放保持不变：画线时同样要能缩放看清细节。
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
     * **解锁态**：单指**长按**图上已有的线才能拖动它（不必先选工具）。
     *
     * true 时 [DrawTool.NONE] 下也在按下那一刻就近抓线 —— 抓到先**挂起**：按住不动
     * 够长按时长才转正开始拖（水平类改价位、直线整体平移）；没按够就滑走 = 普通
     * 画布手势，线纹丝不动（2026-10-15 拍板，防误划把路过的线拖走）。
     * 落空则照旧还给画布（平移、双指缩放、长按十字光标）。
     * 锁定态传 false —— 锁定 = 整张图只读，已有的线一根都动不了。
     *
     * 「抓哪根」仍放在画布手势之前的 down 定性：拖动半径只有 [grabPx]，
     * 拖动中再判会跟着手指跑。想纯粹看形态就上锁，这也是进入全屏默认锁定的原因。
     */
    linesEditable: Boolean = false,
    /**
     * 画布右侧紧邻着一块「删除落点」（全屏的划线竖栏）。拖线时把线拖到画布**右缘之外**
     * 松手即删 —— 判据是落点 x 越出画布宽度（竖栏正好从那里开始）。
     *
     * 由调用方传判据而不是图表自己找：表盘只知道自己多大，不知道右边摆了什么。
     * 恒 false = 没有落点，拖到哪儿都是落库。
     */
    atDropTarget: (xPx: Float) -> Boolean,
    /**
     * 把线拖到 [atDropTarget] 那块落点上松手：调用方删除这条线。
     *
     * ref 为 null = 「本场是在新建一根、且落到垃圾桶上了」——等同放弃这一笔，
     * 由调用方按当前工具清掉对应的草稿。
     */
    onLineDropDelete: (LineRef?) -> Unit = {},
    /** 删除模式（[DrawTool.ERASE]）：落点 [Offset] 命中的那条线；null = 落在空处，什么也不做。 */
    eraseHitAt: (Offset) -> EraseHit?,
    /**
     * 删除模式点中一条告警线：[ruleId] 是它背后的规则，由调用方销毁。
     * 与 [onHorizontalCommit] 分开是因为后者要按当前工具猜线型，而这里线型是判定结果里带的。
     */
    onEraseAlertLine: (Long) -> Unit,
    /** 删除模式点中一条视觉画线：调用方按 chart_line 主键删除。 */
    onEraseDrawLine: (Long) -> Unit,
    /** 已有直线的两端**像素**落点，按下那一刻用它就近抓取。 */
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
        // ——— 单指这一场干什么，全部在 **down 这一刻** 定性 ———
        //
        // 三条通道（划线工具 / 解锁态拖线 / 删除模式）都在这里定，理由是同一个：
        // [awaitFirstDown] 已经把 down 事件消费掉，循环里第一帧 [awaitPointerEvent]
        // 拿到的其实是**第一个 MOVE**。手指一动就滑出抓取半径（[grabPx] 才 24dp），
        // 于是「抓哪根已有的线」就被判错了，甚至凭空新建一根。
        // 轻点更快：第一帧可能已是 UP，循环体一帧 pressed 都见不到。
        // 区别在于**接管时机**：划线工具与删除模式当场接管；解锁态抓到线只挂起，
        // 按满长按且没滑走才转正（见循环内）—— 误划不能拖走线。
        val downY = first.position.y.coerceIn(plot0.mainTopPx, plot0.mainTopPx + plot0.mainHeightPx)
        /** 本场单指抓到的线（解锁态与划线工具下都可能）；null = 没抓到。 */
        var horizontalGrab: LineRef? = null
        /** 本场单指走不走水平通道：拖已有的一条，或按当前工具在这个价位新建一根。 */
        var horizontalActive = false
        /** 直线通道下这一场的用途；null = 没走这条通道。 */
        var trendMode: TrendMode? = null
        /** 正在整体平移的已有直线 id（0 只会在「新建」的预览里出现，成交必为正 id）。 */
        var trendGrabbedId = 0L
        /** 抓取那一刻线两端的像素与按下手指位置：平移按「端点 + 手指位移」整根走。 */
        var trendBase: Pair<Offset, Offset>? = null
        var trendDown = Offset.Zero
        /** 本场最近一次落点（已夹进绘图区）；第二敲靠它判「是不是落得太近」。 */
        var trendLast = Offset.Zero
        /**
         * 直线场「抓住准星拖」的基准：down 那一刻准星与手指的位置。
         * 之后每帧 准星 = down 时的准星 + 手指位移 —— 按下**不传送**，抓着拖。
         */
        var trendCrosshairBase = Offset.Zero
        var trendDragFrom = Offset.Zero
        /** 本场最近一帧的**原始** x（不夹进绘图区）：判「有没有拖到垃圾桶」只能用它。 */
        var lastRawX = first.position.x
        /**
         * 解锁态的**挂起**抓取：down 那一刻就近抓到了线但先不接管（2026-10-15），
         * 按满长按时长且没滑出 slop 才转正（见循环内）。挂起期间不发任何拖线回调，
         * 滑走/落下第二根手指即作废 —— 这场就是普通画布手势。
         */
        var pendingHorizontal: LineRef? = null
        var pendingTrend = false

        when {
            // ① 划线工具：单指只服务那一条通道，落点就是「在这个价位新建一根」。
            //    **不再就近抓已有线**（2026-10-14）：编辑旧线已由解锁态「长按拖动」覆盖，
            //    工具模式下再抓线只会与「纯净画布上落新线」的预期打架 ——
            //    而且纯净画布里旧线根本不可见，抓一根看不见的线等于盲操作。
            drawTool == DrawTool.ALERT || drawTool == DrawTool.HORIZONTAL -> {
                horizontalActive = true
            }
            // ② 解锁态：按下就近**抓**哪根线（不必先选工具），但先挂起不接管 ——
            //    按住不动够长按时长才转正开始拖；没按够就滑走（或落下第二根手指）
            //    = 这场是普通画布手势，线纹丝不动（2026-10-15 拍板，防「划到就拖走」）。
            //    落空与挂起作废都照旧还给画布（平移、双指缩放、长按十字光标）。
            //    刻度区与副图区不参与抓取 —— 那两处起笔本来就另有含义
            //    （缩放价格 / 交还页面翻页）。
            //
            //    水平类（告警线、水平线）与直线**放在一起比距离**：两条在交点附近都够得着
            //    时，各判一次再按「先水平后斜线」定优先级，会出现「点着看得见的那条、
            //    拖走的却是底下那条」。
            drawTool == DrawTool.NONE && linesEditable && !onPriceAxis && !onSubPane -> {
                val flat = horizontalAnchors().nearestHorizontal(downY, grabPx)
                val trend = trendPixels().nearestTrend(first.position, grabPx)
                if (trend != null && (flat == null || trend.second < flat.second)) {
                    trendGrabbedId = trend.first.id
                    trendBase = trend.first.p1 to trend.first.p2
                    trendDown = first.position
                    pendingTrend = true
                } else if (flat != null) {
                    pendingHorizontal = flat.first
                }
            }
            // ③ 直线工具：种子在就敲第二下，否则这一场负责「拖准星定第一点」。
            //    同样**不再抓已有线平移**（2026-10-14，理由同 ①）：平移整根直线也是编辑，
            //    归解锁态长按拖；工具下单指只管「抓住准星拖 → 松手定点」。
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
                trendMode = if (onTrendSeedGet() != null) {
                    // 第二敲当场用**准星当前位置**出一帧预览：轻点同样没有后续 pressed 帧，
                    // 而成交回调拿的是「最后一帧预览」落库 —— 一帧不发就等于白敲。
                    // 轻点第二敲 = 准星没动 = 与种子重合 → 收尾判「太近」撤销，正好。
                    val a1 = onTrendSeedGet()
                    val a2 = trendAnchorAt(cur.x, cur.y)
                    if (a1 != null && a2 != null) onTrendDrag(0L, a1, a2)
                    TrendMode.PLACE2
                } else {
                    TrendMode.SEED
                }
            }
        }
        /** 本场单指已经属于图表（而不是交给父级滚动容器）。解锁态的挂起抓取转正后才置 true。 */
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
        /**
         * 删除模式：按下的那一点命中了哪条线。跟上面三条通道一样在 down 定性 ——
         * 理由是同一条（循环里第一帧已经是 MOVE 了，落点会跟着手指跑）。手指一旦移开
         * 超过 slop 就把它作废：那时这场手势是「滑一下图」而不是「点这条线」，
         * 否则一次横向平移的起手就会顺手把碰到的线删掉。
         */
        var eraseHit = if (drawTool == DrawTool.ERASE) eraseHitAt(first.position) else null
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
         * 解锁态抓到线时同样要走这一帧：否则「按住不动直接松手」什么都不落库
         * （草稿从没建立过，成交回调拿不到东西）。
         */
        if (horizontalActive) {
            dragActive = true
            onHorizontalDrag(horizontalGrab, downY)
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
            }

            // 解锁态的挂起抓取在这里「转正」：单指按满长按时长、位移还在 slop 内，
            // 这场手势才真正归线 —— 转正后落进下面的 owned 分支逐帧拖（首帧就发出，
            // 「按住不动直接松手」也照样落库）。滑出 slop 或落下第二根手指就作废：
            // 线留在原位，这场照旧走平移/缩放。转正与长按十字光标共用一套判定
            // （时长 + slop），按在线上时这里先命中，十字光标那一支自然轮不到。
            if (pendingTrend || pendingHorizontal != null) {
                val held = primary.uptimeMillis - first.uptimeMillis
                if (pressed.size == 1 && held >= longPressMs && travelled <= touchSlop) {
                    if (pendingTrend) {
                        trendMode = TrendMode.MOVE
                    } else {
                        horizontalActive = true
                        horizontalGrab = pendingHorizontal
                    }
                    pendingTrend = false
                    pendingHorizontal = null
                    owned = true
                } else if (travelled > touchSlop || pressed.size >= 2) {
                    pendingTrend = false
                    pendingHorizontal = null
                }
            }

            // 划线 / 拖线分支必须排在十字光标判定之前：这两类手势里都不出十字光标。
            // `owned` 在 down 那一刻定性、挂起转正后置 true（见上方），解锁态没抓到线
            // 或挂起未转正时为 false，单指照旧走下面的平移 / 长按十字光标。
            if (owned && pressed.size == 1) {
                val plot = geo()
                val raw = primary.position
                lastRawX = raw.x
                val x = raw.x.coerceIn(0f, plot.plotWidthPx)
                val y = raw.y.coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                trendLast = Offset(x, y)
                if (drawTool == DrawTool.ERASE) {
                    // 删除模式这一场只干一件事：点中哪条删哪条（真正的删除在松手时做）。
                    // 手指一挪出 slop 就不算「点」—— 单指在删除模式下仍是唯一通道，
                    // 不设这道闸的话，想吃掉一次误触的滑动就会把它路过的那条线删掉。
                    if (eraseHit != null && (raw - first.position).getDistance() > touchSlop) {
                        eraseHit = null
                    }
                    continue
                }
                // `trendMode != null` 而不是 `drawTool == TREND`：解锁态抓到的直线也走这条路
                // （趋势线整体平移与直线工具下的平移是同一件事，没有第二套实现）
                if (trendMode != null) {
                    when (trendMode) {
                        // 抓住准星拖（down 已记基准，见定性处）：准星 = down 时准星 + 手指位移，
                        // 按下不传送、松手才定点（种子在收尾 SEED 分支按 trendLast 落）。
                        TrendMode.SEED -> {
                            val cx = (trendCrosshairBase.x + (raw.x - trendDragFrom.x))
                                .coerceIn(0f, plot.plotWidthPx)
                            val cy = (trendCrosshairBase.y + (raw.y - trendDragFrom.y))
                                .coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                            trendLast = Offset(cx, cy)
                            onTrendCrosshair(trendLast)
                        }
                        TrendMode.PLACE2 -> {
                            val cx = (trendCrosshairBase.x + (raw.x - trendDragFrom.x))
                                .coerceIn(0f, plot.plotWidthPx)
                            val cy = (trendCrosshairBase.y + (raw.y - trendDragFrom.y))
                                .coerceIn(plot.mainTopPx, plot.mainTopPx + plot.mainHeightPx)
                            trendLast = Offset(cx, cy)
                            onTrendCrosshair(trendLast)
                            // 预览线从种子点连到十字交点，松手才定下第二点
                            val a1 = onTrendSeedGet()
                            val a2 = trendAnchorAt(cx, cy)
                            if (a1 != null && a2 != null) onTrendDrag(0L, a1, a2)
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
                    }
                    continue
                }
                // 抓哪根已在**按下那一刻**定死（见循环前的说明），这里只逐帧跟手。
                // dragActive 与首帧落点：划线工具在 down 时就给过（单击落线靠那一帧）；
                // 解锁态的挂起抓取没有 down 帧 —— 转正后的第一帧就是这里，
                // 「按住不动直接松手」同样靠它落库。置位是幂等的，两边都成立。
                dragActive = true
                onHorizontalDrag(horizontalGrab, y)
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
                                onPricePan(deltaY / height)
                            }
                        }
                    }

                    null -> Unit
                }
            }
        }
        /**
         * 松手时落点在不在右侧竖栏那块「垃圾桶」上。
         *
         * 用**原始** x 判：循环里逐帧那份 x 已经夹进绘图区，永远到不了画布右缘之外，
         * 拿它判这件事会恒为假。还要本场确实在拖一根线才算 ——
         * 拖动中的水平类线、平移中的直线、两敲的预览，以及第一敲拖准星（SEED：
         * 拖到垃圾桶 = 取消这一笔，种子还没落，正好不用清）。
         */
        val droppedOnTrash = atDropTarget(lastRawX) && when {
            horizontalActive && dragActive -> true
            trendMode != null -> true
            else -> false
        }
        if (droppedOnTrash) {
            // 拖到垃圾桶上：一律**不落库**，手上这根当场消失。
            // 直线两条通道各交各的：MOVE = 删已有那条（chart_line 主键），
            // PLACE2 = 放弃刚敲出来的预览（与「第二下离得太近」同一条回调），
            // SEED = 什么都不用清（种子本来就在收尾才落，走不到落下的那步）。
            // 水平通道交给调用方按 LineRef 分流 —— 落空时那一支是「按当前工具新建的草稿」，
            // 也一并清掉（拖到垃圾桶上松手，本来就不该留下一根新线）。
            when (trendMode) {
                TrendMode.MOVE -> onLineDropDelete(LineRef.Guide(trendGrabbedId))
                TrendMode.PLACE2 -> {
                    onTrendSeed(null)
                    onLineDropDelete(LineRef.Guide(0L))
                }
                else -> onLineDropDelete(horizontalGrab)
            }
        } else {
            if (dragActive) {
                // 松手即落库（新线新建、已有线改价）。删除不在这里 —— 竖栏的删除模式另有一条。
                onHorizontalCommit(horizontalGrab)
            }
            // 删除模式：按下那一点命中了线、且这一场没滑走 → 松手即删。
            // 放在松手后而不是按下时删：手指还没抬起来就给结果，误按一下就没有回头路。
            // 删掉之后由调用方把工具收回 NONE（与落一根自动收起同一条出口）。
            eraseHit?.let { hit ->
                when (hit) {
                    is EraseHit.Alert -> onEraseAlertLine(hit.ruleId)
                    is EraseHit.Draw -> onEraseDrawLine(hit.lineId)
                }
            }
            when (trendMode) {
                // 第一敲在这里才「定点」：种子 = 准星停下的位置（松手前的拖动都只是瞄准）。
                // 轻点也走这条路 —— down 已把准星就位（trendLast 同步），收尾照落。
                TrendMode.SEED -> trendAnchorAt(trendLast.x, trendLast.y)?.let(onTrendSeed)
                null -> Unit
                TrendMode.PLACE2 -> {
                    val seed = onTrendSeedGet()
                    val seedPx = seed?.let(trendAnchorPx)
                    if (seed == null || seedPx == null) {
                        onTrendDelete(0L) // 种子半路没了：把预览收回去，别留一根悬空的新线
                    } else if ((trendLast - seedPx).getDistance() < grabPx) {
                        // 第二敲离第一下太近 = 两点定不出一条线：判「撤销这一笔」，
                        // 清种子并撤回预览 —— 与种子半路没了同一条回调
                        onTrendSeed(null)
                        onTrendDelete(0L)
                    } else {
                        onTrendCommit(0L)
                        onTrendSeed(null)
                    }
                }
                TrendMode.MOVE -> onTrendCommit(trendGrabbedId)
            }
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
 * 一条已有**水平类**的线（告警线 / 水平线）在主图上的纵向像素落点，手势按它决定抓哪根。
 *
 * [ref] 带上这条线属于哪个域：解锁态下单指只有一个水平通道，落库回调要靠它分流。
 */
internal data class HorizontalAnchor(val ref: LineRef, val yPx: Float)

/** 直线工具下单指这场的定性。 */
private enum class TrendMode {
    /** 第一敲：拖十字准星瞄准，**松手才落**种子锚点（落点 = 准星停下的位置）。 */
    SEED,
    /** 已有种子时的第二敲：拖动出预览，抬手成交（落点太近则撤销）。 */
    PLACE2,
    /** 就近抓到已有直线：整根随手指平移。 */
    MOVE,
}

/** 一条已有直线当前的两端像素落点，手势按它就近抓取与平移。 */
internal data class TrendLinePixels(val id: Long, val p1: Offset, val p2: Offset)

/**
 * 删除模式（[DrawTool.ERASE]）下点中的那条线。
 *
 * 分两型而不是只回一个 id：告警线删的是它背后的**规则**（顺带带走通知配置），
 * 视觉画线删的是 chart_line 的一行 —— 两张表、两条回调，混成一个 id 空间迟早会删错东西。
 * [distance] 只在同一次命中判定里用来在两类之间取最近，出了那个函数就没意义。
 */
private sealed interface EraseHit {
    val distance: Float

    /** 命中的是告警线（[ruleId] 为 alert_rule 的主键）。 */
    data class Alert(val ruleId: Long, override val distance: Float) : EraseHit

    /** 命中的是视觉画线（[lineId] 为 chart_line 的主键）。 */
    data class Draw(val lineId: Long, override val distance: Float) : EraseHit
}

/**
 * 按「点到线段的距离」就近取一条直线，超出 [slopPx] 返回 null（= 落一颗新种子）。
 *
 * 抓**线段**而不是无限延长线：延长部分是趋势的示意，可拖的锚段才是用户画的那一笔 ——
 * 在延长线上按一下就抓走整根会莫名其妙。
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

/** 价格标（最新价/十字光标/告警线）文字：比轴刻度再小一号，读数不该跟蜡烛抢视线。 */
@Composable
private fun rememberPriceTagStyle(): TextStyle =
    rememberAxisTextStyle().copy(fontSize = 8.sp, lineHeight = 9.sp)

/** 指标读数带的行高：与 [ChartGeo.READOUT_LINE_DP] 对齐，用来算读数带总高。 */
private val READOUT_LINE_HEIGHT = 12.sp

/**
 * K 线详情小条（[KlineChart] 的 candleReadout 槽）预留的高度：
 * 两行 9sp 文本（行高 11）+ 行距 2 + 与读数带之间的留白 4。
 * 小条本体是调用方传进来的 composable，画布猜不到它的尺寸，只能按约定夹表。
 */
private const val CANDLE_READOUT_BAND_DP = 28f

/** 价格标（最新价/十字光标/告警线共用）的水平内边距（dp）。 */
private const val PRICE_BADGE_PAD_H_DP = 3.5f

/** 价格标的垂直内边距（dp）。 */
private const val PRICE_BADGE_PAD_V_DP = 1f

/** 已有的告警线（非正在拖的那根）的不透明度：参照线不该和 K 线争视线。 */
private const val STATIC_ALERT_ALPHA = 0.45f

/**
 * 静态视觉画线的透明度：画线是用户自己做的记号，平时不该跟 K 线抢视线；
 * 拖拽中回到不透明（与告警线同一手感的「手里那根最实」）。
 * 注意画线是**实线**而告警线是虚线 —— 形态本身就把「会响的」和「只是记号」分开。
 */
private const val DRAW_LINE_ALPHA = 0.75f

/** 直线两端锚点的圆点半径（dp）。 */
private val TREND_ANCHOR_DOT = 3.dp

/** 第一敲种子点的半径（dp）：比锚点大一档，提示「已经敲过一下了」。 */
private val TREND_SEED_DOT = 4.5.dp

/** 左下角角标按钮的边长。 */
private val CORNER_BUTTON_SIZE = 26.dp

/**
 * 图内浮层（左下角「全屏」角标、右下角的显隐开关组）距绘图区边缘的内缩距离。
 * 几处浮层同用这一档值，贴角才对称。
 */
private val CORNER_BUTTON_INSET = 12.dp

/**
 * 划线的抓取容差：按下点离某根线在这个范围内就算抓它，否则新建一根。
 *
 * 比手指触摸半径略大 —— 屏幕上那根 1.5dp 的虚线根本按不准，
 * 容差太小会让「微调已有预警」变成「凭空多出一条新预警」。
 */
private val ALERT_GRAB_WIDTH = 24.dp

/** 底右角显隐开关（「MA」按钮与划线眼睛）的外圈边长，两者同高。 */
private val OVERLAY_BUTTON_SIZE = 26.dp

/** 眼睛图标本身的大小（小于外圈背景，留出内边距）由 `EyeGlyph` 的默认值给出。 */
