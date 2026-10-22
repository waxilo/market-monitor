package com.waxilo.marketmonitor.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertRuleSource
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.alert.IndicatorKind
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.indicator.DEFAULT_EMA_CATALOG
import com.waxilo.marketmonitor.domain.indicator.DEFAULT_MA_CATALOG
import com.waxilo.marketmonitor.domain.indicator.Indicators
import com.waxilo.marketmonitor.domain.indicator.MaFamily
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.KlineAggregator
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.OpenInterestSeries
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.chart.AlertPriceLine
import com.waxilo.marketmonitor.ui.chart.BandGuideLine
import com.waxilo.marketmonitor.ui.chart.ChartViewport
import com.waxilo.marketmonitor.ui.chart.ChartViewState
import com.waxilo.marketmonitor.ui.chart.DrawGuideLine
import com.waxilo.marketmonitor.ui.chart.IndicatorGuideLine
import com.waxilo.marketmonitor.ui.chart.MA_COLOR_SLOT_COUNT
import com.waxilo.marketmonitor.ui.chart.SubPaneKind
import com.waxilo.marketmonitor.ui.chart.mainReadoutLineCount
import com.waxilo.marketmonitor.ui.chart.toChartLine
import com.waxilo.marketmonitor.ui.common.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/** 详情页默认周期：日线信息密度最低，首屏最不容易看起来空白。 */
val DEFAULT_CHART_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.D1)

/** 均线期数取值范围，与桌面端（lib/maPeriods.ts）同口径。 */
const val MA_PERIOD_MIN = 1
const val MA_PERIOD_MAX = 1000

/**
 * 均线**目录**的条数上限（**每族各自**），与桌面端（`chartSeries.MA_LIMIT`）同口径：
 * 色板给每族八个色位，再多只是同色线堆在读数带上，没有信息量。
 *
 * 注意这是**一族的总数**（出厂档位也算在内），不是「自定义条数」—— 目录整份交给用户之后，
 * 预置档与自定义档在数据上已经不分家（见 [normalizeMaCatalog]）。
 */
const val MA_LIMIT = 8

/**
 * 一族均线的目录归一化：只留 `[MA_PERIOD_MIN, MA_PERIOD_MAX]` 内的值、去重、升序，
 * 并截到 [MA_LIMIT] 条。旧版本存的、手改坏的、以后删掉的档位都在这里被丢掉。
 *
 * **空表不回填出厂目录**：目录清空是合法偏好（chip 点亮也不画线，随时加得回来），
 * 回填会让「删光了」在下次启动时自己变回来。（桌面端对空目录回退出厂档位，
 * 这点有意不同 —— 那边目录是 localStorage 里的一个字段，判不出「没设过」与「清空了」。）
 */
fun normalizeMaCatalog(catalog: List<Int>): List<Int> =
    catalog.filter { it in MA_PERIOD_MIN..MA_PERIOD_MAX }.distinct().sorted().take(MA_LIMIT)

/**
 * 均线颜色归一化：目录内每条均线恰好一个合法色槽（0~[MA_COLOR_SLOT_COUNT]-1），且互不重复。
 *
 * 规则（也是老装机的迁移路径）：
 * 1. 目录里已有且合法、未撞车的槽位原样保留 —— 增删别的均线不改已有线的颜色；
 * 2. 缺失/非法/撞车的按**期数升序**补第一个空槽 —— 老存档没有颜色时，
 *    5/10/20/30/60 依次拿到 0~4，正是旧版「按期数固定分配」的观感；
 * 3. 目录外的期数一律丢弃（删掉的均线不占颜色）。
 *
 * 两族**各调一次**（色槽是各族自己的八位，互不占位）。
 * 与桌面端 `lib/maPeriods.ts` 的 `normalizeMaColors` 同一套口径。
 */
fun normalizeMaColors(catalog: List<Int>, raw: Map<Int, Int>): Map<Int, Int> {
    val periods = normalizeMaCatalog(catalog)
    val used = mutableSetOf<Int>()
    val out = LinkedHashMap<Int, Int>()
    for (period in periods) {
        val slot = raw[period]
        if (slot != null && slot in 0 until MA_COLOR_SLOT_COUNT && used.add(slot)) {
            out[period] = slot
        }
    }
    var nextSlot = 0
    for (period in periods) {
        if (period in out) continue
        while (nextSlot in used) nextSlot++
        if (nextSlot >= MA_COLOR_SLOT_COUNT) break // 目录已被 MA_LIMIT 截断，正常走不到
        out[period] = nextSlot
        used.add(nextSlot)
    }
    return out
}

/**
 * 给一条均线换色：目标槽位被别的均线占着就**互换**（始终一色一线）；
 * 不在目录里的期数、非法槽位原样返回。
 */
fun assignMaColor(
    colors: Map<Int, Int>,
    catalog: List<Int>,
    period: Int,
    slot: Int,
): Map<Int, Int> {
    if (period !in catalog || slot !in 0 until MA_COLOR_SLOT_COUNT) return colors
    val current = colors[period]
    val owner = catalog.firstOrNull { it != period && colors[it] == slot }
    val next = colors.toMutableMap()
    next[period] = slot
    if (owner != null) {
        if (current != null) next[owner] = current else next.remove(owner)
    }
    return normalizeMaColors(catalog, next)
}

/** MA 一族的出厂配色：出厂五档依次拿 0~4 号色槽（恢复默认与首帧初值都用它）。 */
val DEFAULT_MA_COLORS: Map<Int, Int> = normalizeMaColors(DEFAULT_MA_CATALOG, emptyMap())

/** EMA 一族的出厂配色：出厂三档依次拿 0~2 号色槽。 */
val DEFAULT_EMA_COLORS: Map<Int, Int> = normalizeMaColors(DEFAULT_EMA_CATALOG, emptyMap())

/**
 * 「逐条隐藏」的归一：只留**目录里真有的**期数、去重、按目录顺序。
 *
 * 目录里删掉的均线必须一并从 [muted] 里消失 —— 否则删了又加回来会莫名其妙地不显示，
 * 用户以为「加了一条新线」，图上却没有。老装机没有这个字段 ⇒ 空表 = 全都睁眼。
 *
 * 显隐因此分**两级**（与桌面端 `MaFamilyPrefs` 同口径）：族 chip 管总闸（`maVisible` /
 * `emaVisible`），每条行尾的小眼睛管分闸（这里）。总闸关掉再打开，分闸状态原样保留。
 */
fun normalizeMuted(catalog: List<Int>, muted: Collection<Int>): List<Int> =
    if (muted.isEmpty()) emptyList() else catalog.filter { it in muted }

/** 这一族此刻**图上真会画**的期数：目录 − 眼睛闭上的（整族隐藏由调用方折算成空表）。 */
fun drawnPeriods(catalog: List<Int>, muted: Collection<Int>): List<Int> =
    if (muted.isEmpty()) catalog else catalog.filter { it !in muted }

/** 翻一条均线的眼睛。不在目录里的期数原样返回（面板只可能点到目录里的行）。 */
fun toggleMuted(catalog: List<Int>, muted: Collection<Int>, period: Int): List<Int> {
    if (period !in catalog) return muted.toList()
    val next = muted.toMutableSet()
    if (!next.add(period)) next.remove(period)
    return normalizeMuted(catalog, next)
}

/** 全睁（`hide = false`）/ 全闭（`hide = true`）：面板标题行那个「全部显示 / 全部隐藏」。 */
fun setAllMuted(catalog: List<Int>, hide: Boolean): List<Int> = if (hide) catalog else emptyList()

/**
 * 详情页的一条统计。
 * [raw] 保留未格式化的原始数值，供进度条之类的可视化使用；
 * [value] 是给用户看的格式化结果。两者分开避免在组合里反解析字符串。
 */
@Immutable
data class StatItem(
    val label: String,
    val value: String,
)

@Immutable
data class DetailUiState(
    val id: SymbolId = SymbolId(MarketType.SPOT, ""),
    val title: String = "",
    val price: String = PriceFormatter.NO_DATA,
    val changePercent: Double? = null,
    /** 图表刻度与 tooltip 的小数位由它决定（PRD 已定：低价币自动放开位数）。 */
    val tickSize: BigDecimal? = null,
    val stats: List<StatItem> = emptyList(),
    /** 24h 高低与当前价（原始值），Hero 卡片的高低区间条使用。 */
    val low24h: Double? = null,
    val high24h: Double? = null,
    val lastPrice: Double? = null,
    val intervals: List<CandleInterval> = CandleInterval.quickPickPresets,
    val interval: CandleInterval = DEFAULT_CHART_INTERVAL,
    val candles: List<Kline> = emptyList(),
    /**
     * 主图均线（两族各有目录、配色与显隐，指标条上各一枚 chip）：
     * [maVisible] / [emaVisible] 是**整族**显隐，[maCatalog] / [emaCatalog] 是「配了哪几档」，
     * [maMuted] / [emaMuted] 是**逐条**小眼睛闭着的档位（只藏不删）。
     *
     * 两级互不覆盖：chip 关掉再打开，谁睁眼谁闭眼原样保留；配了但闭着眼的那几条仍在目录里，
     * 随时点回来 —— 图上画的 = 目录 − 闭眼。
     */
    val maVisible: Boolean = true,
    val maCatalog: List<Int> = DEFAULT_MA_CATALOG,
    /** MA 期数 → 线色槽位（0~7，见 `ui/chart/MA_LINE_ROLES`）；与目录一一对应。 */
    val maColors: Map<Int, Int> = DEFAULT_MA_COLORS,
    /** MA 一族里眼睛闭着的期数（`⊆ maCatalog`，见 [normalizeMuted]）。 */
    val maMuted: List<Int> = emptyList(),
    val emaVisible: Boolean = false,
    val emaCatalog: List<Int> = DEFAULT_EMA_CATALOG,
    /** EMA 期数 → 线色槽位（见 `EMA_LINE_ROLES`，与 MA 的八槽互不占位）。 */
    val emaColors: Map<Int, Int> = DEFAULT_EMA_COLORS,
    /** EMA 一族里眼睛闭着的期数（`⊆ emaCatalog`）。 */
    val emaMuted: List<Int> = emptyList(),
    val showBoll: Boolean = false,
    /** 选中的副图。空列表 = 不显示副图；支持多选。 */
    val subPanes: List<SubPaneKind> = listOf(SubPaneKind.VOLUME),
    /**
     * OI chip 是否可用：当前合约接口提供持仓量历史才亮，现货与无 OI 的接口恒 false。
     * 只看接口能力不看到没到数据 —— 亮不亮是「选不选得动」的问题。
     */
    val oiSupported: Boolean = false,
    /** 持仓量序列（美元名义值；Bybit 的基础币口径在绘制层折价）。没选 OI 或还没取到时为 null。 */
    val openInterest: OpenInterestSeries? = null,
    val watched: Boolean = false,
    val origin: DataOrigin = DataOrigin.REMOTE,
    /**
     * 当前展示周期的序列还在取（首屏 / 刚换了周期 / 手动重载）。
     *
     * 与「[candles] 是空的」是两件事：这个为真时图上画**空图表 + 遮罩**，
     * 为假而 [candles] 仍空才是「取到了，是空的」（新标的没有历史）或「取失败」。
     */
    val loadingCandles: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
) {
    /**
     * 实际会画出来的副图块数：选了 OI 但数据还没到时那块整块不画。
     * 图表高度按它算，否则会为不存在的那块留一条空档。
     */
    val subPaneCount: Int
        get() = subPanes.count { it != SubPaneKind.OI || openInterest?.points?.isNotEmpty() == true }

    /**
     * 主图读数带的行数：一族一行（MA / EMA / BOLL），空族不出行。
     *
     * 顶部读数带高度由它决定，而**加载中的骨架图与真实图必须同高** ——
     * 两边各算各的行数，网格就会差一行，数据到了整张图会往上/往下跳一格。
     */
    val mainReadoutLines: Int
        get() = mainReadoutLineCount(visiblePeriods(MaFamily.MA), visiblePeriods(MaFamily.EMA), showBoll)

    /** 一族这一刻画不画（指标条上那枚 chip 的亮灭）。 */
    fun isVisible(family: MaFamily): Boolean = when (family) {
        MaFamily.MA -> maVisible
        MaFamily.EMA -> emaVisible
    }

    /** 一族里眼睛闭着的期数（面板里每行那只小眼睛的状态）。 */
    fun mutedOf(family: MaFamily): List<Int> = when (family) {
        MaFamily.MA -> maMuted
        MaFamily.EMA -> emaMuted
    }

    /**
     * 一族这一刻**实际画出来**的期数：整族藏起来 = 空表（目录原样保留，再点亮就回来），
     * 族开着则再减去闭着眼的那几条。
     */
    fun visiblePeriods(family: MaFamily): List<Int> {
        if (!isVisible(family)) return emptyList()
        return when (family) {
            MaFamily.MA -> drawnPeriods(maCatalog, maMuted)
            MaFamily.EMA -> drawnPeriods(emaCatalog, emaMuted)
        }
    }
}

/**
 * 单个交易对详情（PRD FR-1.3 / FR-2.1 / FR-2.3）。
 *
 * 关键取舍：内存里保留**基础周期**的原始蜡烛，展示用的蜡烛每次整体重聚合。
 * 这样自定义周期的实时推送只需把一根基础蜡烛并进序列，不必处理「跨桶边界」与
 * 「tick 乱序」，而千根量级的聚合开销可忽略。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    container: AppContainer,
    private val id: SymbolId,
) : ViewModel() {

    private val repository = container.marketRepository
    private val watchlist = container.watchlistRepository
    private val settings = container.settings
    private val alerts = container.alertRepository
    private val engine = container.alertEngine
    private val chartLinesRepo = container.chartLineRepository

    private val interval = MutableStateFlow(DEFAULT_CHART_INTERVAL)
    private val reloadToken = MutableStateFlow(0)
    private val chart = MutableStateFlow(ChartData())

    /** 当前合约接口是否提供持仓量历史（OI chip 的可用性）；现货恒 false。换源后按新方言更新。 */
    private val oiSupported = MutableStateFlow(repository.supportsOpenInterest(id.market))

    /** 数据源代次：换源后让持仓量轮询按新方言重启。 */
    private val sourceVersion = MutableStateFlow(0)

    /**
     * 全局视角偏好（横向视窗 + 纵向刻度）：跨标的、跨周期、跨会话。
     *
     * 图表实例**首帧读一次**（`chartView.value`）；之后每次变化经 [commitChartView] 回来。
     * 放 ViewModel 而不是图表内部：竖屏与全屏是两个互斥的图表实例（旋屏还会重建
     * Activity），交接初值只能靠一个比它们活得久的地方。
     */
    private val chartViewState = MutableStateFlow(ChartViewState())
    val chartView: StateFlow<ChartViewState> = chartViewState.asStateFlow()
    private var chartViewSaveJob: Job? = null

    /**
     * 正在拖动的那条线：手指未离开，价格只是临时状态。
     *
     * [ruleId] 为 null 表示新划的线（还没落库）。放在 ViewModel 而不是 `rememberSaveable`：
     * 全屏要横屏，旋屏会让 Activity 重建，而 ViewModel 实例是保留的。
     */
    private data class AlertDrag(val ruleId: Long?, val price: BigDecimal)

    private val alertDrag = MutableStateFlow<AlertDrag?>(null)

    /** 一次性提示（创建成功之类）。3 秒后自动消失。 */
    private val noticeText = MutableStateFlow<String?>(null)
    private var noticeJob: Job? = null

    /**
     * 画在图表上的全部告警线。
     *
     * **直接由规则派生**，不另存一份：这样规则在预警列表里被删掉、或单次触发后被移除，
     * 线就自动消失，不会出现「线还在、预警早没了」的假象；反过来在预警页改阈值，
     * 回到详情页也能看到最新的那条线。拖动中的线覆盖掉对应规则的历史价位，
     * 松手落库后覆盖就没意义了（派生值已经是新价位）。
     *
     * 只有带目标价（threshold）的规则能画成一条线，区间/涨跌幅规则没有单一价位。
     */
    val alertLines: StateFlow<List<AlertPriceLine>> = combine(
        alerts.rules(),
        alerts.indicatorLines(),
        engine.bandAnchorInfo,
        alertDrag,
    ) { rules, lines, anchors, drag ->
        // 划线规则挂的线在虚线左端标身份（如「1h MA30」）：几条均线挤在一起时，
        // 光看右侧价位认不出哪条是哪条。均线带的虚线必定锚在池里某个周期的某条均线上
        // （引擎每轮换锚后发布），标「15m MA10·上3」= 锚在 15m MA10、池中共 3 条高于现价
        val lineById = lines.associateBy { it.id }
        val anchorByLine = anchors.associateBy { it.lineId }
        fun labelOf(rule: AlertRule): String? {
            val line = rule.indicatorLineId?.let(lineById::get) ?: return null
            return when (line.kind) {
                IndicatorKind.MA -> "${line.interval.label} MA${line.maPeriod}"
                IndicatorKind.MA_BAND -> {
                    val above = rule.condition == AlertCondition.ABOVE
                    val side = (if (above) anchorByLine[line.id]?.upper else anchorByLine[line.id]?.lower)
                        ?: return "均线带"
                    "${side.member.interval.label} MA${side.member.maPeriod}·${if (above) "上" else "下"}${side.poolCount}"
                }
            }
        }
        val own = rules.filter { it.market == id.market && it.symbol == id.symbol }
            .mapNotNull { rule ->
                val threshold = rule.threshold ?: return@mapNotNull null
                AlertPriceLine(
                    ruleId = rule.id,
                    price = threshold.toDouble(),
                    label = labelOf(rule),
                )
            }
        if (drag == null) own
        else own.filterNot { it.ruleId == drag.ruleId } + AlertPriceLine(
            ruleId = drag.ruleId,
            price = drag.price.toDouble(),
            dragging = true,
            label = own.firstOrNull { it.ruleId == drag.ruleId }?.label,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notice: StateFlow<String?> = noticeText.asStateFlow()

    // ---- 指标划线（铃铛弹层与图上灰参考线的数据源） ----

    /** 本标的的全部指标划线（含不告警的）：划线管理列表用。 */
    val indicatorLines: StateFlow<List<IndicatorLine>> = alerts.indicatorLines()
        .map { rows -> rows.filter { it.market == id.market && it.symbol == id.symbol } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 本标的的全部预警规则（手动 + 划线挂的）：铃铛弹层的「价格预警」区用。 */
    val symbolRules: StateFlow<List<AlertRule>> = alerts.rules()
        .map { rows -> rows.filter { it.market == id.market && it.symbol == id.symbol } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「不告警」的单周期均线画成灰色参考曲线：按它**自己配置的 K 线周期**算出指标序列，
     * 再台阶式对齐到当前展示的蜡烛 —— 不是定死在某个价位的水平线，线随指标周期演进。
     * 告警模式的线不在这儿：那是引擎按预警节奏同步阈值的虚线（走规则 → alertLines 那条链）。
     * 均线带也不在这儿：它画的是 [bandGuides] 那两条锚点水平线，不是逐根台阶线。
     *
     * 重算只挂在「划线集合变化」与「展示序列长出新一根蜡烛」上：
     * 每根 tick 都重算整条指标序列毫无意义，参考线慢一根无人在意。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val indicatorGuides: StateFlow<List<IndicatorGuideLine>> = combine(
        indicatorLines,
        chart.map { data -> data.raw.lastOrNull()?.openTime },
    ) { lines, _ -> lines.filter { it.enabled && it.alertMode == LineAlertMode.OFF && it.kind == IndicatorKind.MA } }
        .flatMapLatest { offLines ->
            flow { emit(offLines.flatMap { runCatching { buildGuides(it) }.getOrDefault(emptyList()) }) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「指标线」模式均线带的锚点水平线：引擎把所有成员均线值当一个集合，
     * 现价上/下最近各取一条发布过来（刚被穿越的成员先静默一轮、自动换锚），
     * 这里只按标的过滤后转成图上的灰色水平线。不挂规则、不会响。
     */
    val bandGuides: StateFlow<List<BandGuideLine>> = engine.bandDisplayLines
        .map { displays ->
            displays.filter { it.market == id.market && it.symbol == id.symbol }
                .flatMap { display ->
                    listOfNotNull(
                        display.upper?.let { BandGuideLine(display.lineId, it) },
                        display.lower?.let { BandGuideLine(display.lineId, it) },
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 一条「不告警」单周期均线对应的参考曲线；均线带走 [bandGuides] 的锚点水平线，
     * 逐成员台阶线正是它被否决的形态，这里刻意返回空。
     */
    private suspend fun buildGuides(line: IndicatorLine): List<IndicatorGuideLine> = when (line.kind) {
        IndicatorKind.MA -> listOfNotNull(
            buildGuide(line, line.interval, line.label) { Indicators.sma(it, line.maPeriod) },
        )

        IndicatorKind.MA_BAND -> emptyList()
    }

    /** 取 [memberInterval] 周期的蜡烛算指标序列，再按 openTime 台阶映射到展示序列的下标上。 */
    private suspend fun buildGuide(
        line: IndicatorLine,
        memberInterval: CandleInterval,
        label: String,
        seriesOf: (DoubleArray) -> DoubleArray,
    ): IndicatorGuideLine? {
        val page = repository.klines(id, memberInterval, GUIDE_CANDLES)
        if (page.klines.isEmpty()) return null
        val series = seriesOf(DoubleArray(page.klines.size) { page.klines[it].close.toDouble() })
        val display = KlineAggregator.aggregate(chart.value.raw, interval.value)
        if (display.isEmpty()) return null
        val out = DoubleArray(display.size) { Double.NaN }
        // 台阶对齐：每根展示蜡烛取「openTime 不晚于它」的最后一根指标蜡烛的值
        var j = 0
        for (i in display.indices) {
            while (j + 1 < page.klines.size && page.klines[j + 1].openTime <= display[i].openTime) j++
            if (page.klines[j].openTime <= display[i].openTime) out[i] = series[j]
        }
        return IndicatorGuideLine(line.id, label, out)
    }

    /**
     * 保存一条划线：**每个标的至多一条**——已有线时本条继承其 id 与创建时间顶替它，
     * 其余的（历史版本可能留下的多条）连同规则一并退场；
     * 配置与原条目完全相同则只提示，不做无谓写库。
     */
    fun saveIndicatorLine(line: IndicatorLine) {
        viewModelScope.launch {
            try {
                val incoming = line.copy(market = id.market, symbol = id.symbol)
                val existing = indicatorLines.value
                if (existing.any { sameLineConfig(it, incoming) }) {
                    showNotice("已有同样配置的划线")
                    return@launch
                }
                val predecessor = existing.firstOrNull()
                existing.drop(1).forEach { stale -> deleteIndicatorLineNow(stale.id) }
                alerts.saveIndicatorLine(
                    incoming.copy(
                        id = predecessor?.id ?: 0L,
                        createdAt = predecessor?.createdAt ?: incoming.createdAt,
                    ),
                )
                showNotice(if (predecessor == null) "已添加划线" else "已更新划线")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("划线保存失败：${e.displayMessage()}")
            }
        }
    }

    /** 连 enabled 一起比：只拨启用开关的保存也是真实改动，漏掉它会让开关永远关不掉。 */
    private fun sameLineConfig(a: IndicatorLine, b: IndicatorLine): Boolean {
        if (a.kind != b.kind || a.alertMode != b.alertMode || a.enabled != b.enabled) return false
        return if (a.members.isNotEmpty() || b.members.isNotEmpty()) {
            a.members.toSet() == b.members.toSet()
        } else {
            a.interval == b.interval && a.maPeriod == b.maPeriod
        }
    }

    /**
     * 删除划线并立刻收掉它挂的预警规则（引擎的巡检只是兜底）。
     *
     * 列表弹层下线后 UI 不再有入口，但**替换**旧条目时仍要走这套即时清理，
     * 保存路径（[saveIndicatorLine]）直接调用，不等引擎 5 秒后的巡检。
     */
    private suspend fun deleteIndicatorLineNow(lineId: Long) {
        alerts.deleteIndicatorLine(lineId)
        alerts.rules().first().filter { it.indicatorLineId == lineId }
            .forEach { alerts.deleteRule(it.id) }
    }

    fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch { runCatching { alerts.setRuleEnabled(ruleId, enabled) } }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch {
            try {
                alerts.deleteRule(ruleId)
                showNotice("已删除预警")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("删除失败：${e.displayMessage()}")
            }
        }
    }

    private val alertLinesVisibleState = MutableStateFlow(true)

    /**
     * 图上的划线要不要画出来，由主图右下角那只眼睛控制。
     *
     * **不进 DataStore**（与均线/布林/副图那几个显示偏好不同）：藏线是「这一会儿想看干净
     * 的 K 线」的临时动作，持久化下来会让下次冷启动的人以为预警被删了。
     * 放在 ViewModel 而不是 `rememberSaveable`：全屏要横屏，旋屏会重建 Activity。
     */
    val alertLinesVisible: StateFlow<Boolean> = alertLinesVisibleState.asStateFlow()

    fun toggleAlertLines() {
        alertLinesVisibleState.update { !it }
    }

    /** 序列与指标开关放在一起：任何一项变化都要重算展示序列。 */
    private data class ChartData(
        /** 已加载的原始蜡烛（**基础周期**，展示序列由它重聚合而来）。 */
        val raw: List<Kline> = emptyList(),
        /**
         * [raw] 是在哪个基础周期上取的；`null` = 还没有可用的序列。
         *
         * 与「[raw] 是空的」是两件事，也是本类里最容易混的一处：
         * [raw] 空只说明「这次取到的是空的」（新上市的币确实可能一根都没有），
         * 而这里是「还没取到」—— 首屏还在路上，或者刚换了周期、新的那一段还没回来。
         *
         * 展示序列只在它与当前周期的 [baseOf] 相等时才派生。少了这个约束，
         * 换周期时会把上一段的蜡烛按新周期重新分桶画出来（图是假的），
         * 而首屏的实时增量会把单根推送当整段序列画出来（进页面先冒一根柱子）。
         */
        val rawBase: CandleInterval? = null,
        val origin: DataOrigin = DataOrigin.REMOTE,
        val hasMore: Boolean = true,
        /**
         * 已经为**这一段序列**请过的那一页的最老时间戳（`null` = 还没请过）。
         *
         * 翻页去重必须跟序列放在一起：图表拖到最老一根会**逐帧**回调 `loadMore()`，
         * 同一个最老时间戳只能真的取一次；而「这一段序列」的身份由 [rawBase] + [raw] 定义，
         * 换周期/换标的重新取数（[loadBase]）时必须一起归零。
         *
         * 早先这个标记挂在画布的 `remember` 上（不带 key）：图那侧不知道序列换过没有，
         * 标记一旦「用掉」就不再放行，于是换过周期后再也翻不动 —— 用户报的
         * 「切换周期后就不加载了」就是它。放这里之后，它与序列同生共死，结构上不可能错配。
         */
        val oldestRequested: Long? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val maVisible: Boolean = true,
        /**
         * 这一族画哪几档。写入方（[restoreChartPreferences] / [saveCatalog]）
         * 已经归一化过，这里不再重复处理。
         */
        val maCatalog: List<Int> = DEFAULT_MA_CATALOG,
        /** 期数 → 颜色槽位；写入方已经归一化过（与 [maCatalog] 同步）。 */
        val maColors: Map<Int, Int> = DEFAULT_MA_COLORS,
        /** 这一族里眼睛闭着的期数（`⊆ maCatalog`，写入方已归一）。 */
        val maMuted: List<Int> = emptyList(),
        val emaVisible: Boolean = false,
        val emaCatalog: List<Int> = DEFAULT_EMA_CATALOG,
        val emaColors: Map<Int, Int> = DEFAULT_EMA_COLORS,
        /** EMA 一族里眼睛闭着的期数（`⊆ emaCatalog`）。 */
        val emaMuted: List<Int> = emptyList(),
        val showBoll: Boolean = false,
        /** 选中的副图集合（按枚举声明顺序归一化）。 */
        val subPanes: List<SubPaneKind> = listOf(SubPaneKind.VOLUME),
        /** 持仓量序列（OI 副图用）；未选中 / 未取到时为 null，清空与加载同走这里。 */
        val openInterest: OpenInterestSeries? = null,
        /** 周期条上摊开的方块，顺序即展示顺序（用户可通过「＋」管理）。 */
        val intervals: List<CandleInterval> = CandleInterval.quickPickPresets,
    ) {
        /** 一族这一刻画不画。 */
        fun isVisible(family: MaFamily): Boolean = when (family) {
            MaFamily.MA -> maVisible
            MaFamily.EMA -> emaVisible
        }

        /** 一族里眼睛闭着的期数（面板里每行那只小眼睛）。 */
        fun mutedOf(family: MaFamily): List<Int> = when (family) {
            MaFamily.MA -> maMuted
            MaFamily.EMA -> emaMuted
        }

        /** 一族这一刻实际画出来的期数：族藏起来 = 空表，否则再减去闭着眼的那几条。 */
        fun visiblePeriods(family: MaFamily): List<Int> {
            if (!isVisible(family)) return emptyList()
            return when (family) {
                MaFamily.MA -> drawnPeriods(maCatalog, maMuted)
                MaFamily.EMA -> drawnPeriods(emaCatalog, emaMuted)
            }
        }
    }

    val state: StateFlow<DetailUiState> = combine(
        repository.ticker(id),
        repository.instrument(id),
        watchlist.watchlist(id.market),
        chart,
        interval,
    ) { price, rule, watched, data, selected ->
        // 当前展示周期对应的取数周期：「序列就绪 / 序列为空 / 取失败」全按这一对 (rawBase, base) 判
        val base = baseOf(selected)
        DetailUiState(
            id = id,
            title = rule?.let { "${it.baseAsset}/${it.quoteAsset}" } ?: id.symbol,
            price = PriceFormatter.format(price?.lastPrice, rule?.priceTickSize),
            changePercent = price?.changePercent,
            tickSize = rule?.priceTickSize,
            low24h = price?.lowPrice?.toDouble(),
            high24h = price?.highPrice?.toDouble(),
            lastPrice = price?.lastPrice?.toDouble(),
            stats = price?.let {
                listOf(
                    StatItem("24h 最高", PriceFormatter.format(it.highPrice, rule?.priceTickSize)),
                    StatItem("24h 最低", PriceFormatter.format(it.lowPrice, rule?.priceTickSize)),
                    StatItem("24h 量能", PriceFormatter.formatQuantity(it.volume)),
                    StatItem("24h 成交额", PriceFormatter.formatCompact(it.quoteVolume)),
                )
            }.orEmpty() + listOf(
                StatItem("价格精度", rule?.priceTickSize?.toPlainString() ?: PriceFormatter.NO_DATA),
                StatItem("所属市场", id.market.label),
            ),
            interval = selected,
            intervals = data.intervals,
            candles = displayCandles(data, selected),
            maVisible = data.maVisible,
            maCatalog = data.maCatalog,
            maColors = data.maColors,
            maMuted = data.maMuted,
            emaVisible = data.emaVisible,
            emaCatalog = data.emaCatalog,
            emaColors = data.emaColors,
            emaMuted = data.emaMuted,
            showBoll = data.showBoll,
            subPanes = data.subPanes,
            oiSupported = oiSupported.value,
            openInterest = data.openInterest,
            watched = id in watched,
            origin = data.origin,
            // 「还在取」→ 图上画空图表 + 遮罩。图上没东西有三种，别混：
            //   · 这一段序列还没到（rawBase 不是它）或正在取 → 骨架 + 遮罩
            //   · 取失败（error 有话说）                      → 让位给错误空态与「重新加载」
            //   · 取到了但一根都没有（新标的没有历史）        → 同上，空态
            // 只看 `loading` 不够：换周期时 state 的重组可能排到「立 loading」之前，
            // 那一帧会掉进空态、闪一下「没有取到 K 线」。
            loadingCandles = data.loading || (data.rawBase != base && data.error == null),
            loadingMore = data.loadingMore,
            hasMore = data.hasMore,
            error = data.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState(id = id))

    init {
        viewModelScope.launch {
            // 先恢复上次使用的周期与指标开关，再开始加载：顺序反了会被首次加载的 copy 覆盖
            restoreChartPreferences()
            combine(interval, reloadToken) { selected, token -> selected to token }
                // 「正在取」这条标志由取数的链**独占**：实时增量不再顺手把它清掉
                // （那会让手上的重载被误判成已就绪），换周期的那一瞬也要先立起来 ——
                // 此刻 ChartData.rawBase 还指着旧周期，新的那一段得有个状态可落。
                .onEach { chart.update { data -> data.copy(loading = true) } }
                .flatMapLatest { (selected, _) -> flow { emit(loadBase(selected)) } }
                .collect { loaded -> chart.value = loaded }
        }
        viewModelScope.launch {
            interval
                // 增量要带上它在哪个**基础周期**上产生：与已加载序列对不上就不能并
                // （见 mergeBaseCandle —— 进页面时闪出来的那「一根柱子」就是错并进去的）
                .flatMapLatest { selected ->
                    repository.klineUpdate(id, selected).map { baseOf(selected) to it }
                }
                .collect { (base, candle) -> mergeBaseCandle(base, candle) }
        }
        // 自动兜底换了合约源：旧盘口的缓存已由容器清空，重拉当前周期；
        // 新源的持仓量能力可能不同，OI 副图按新方言重启。
        viewModelScope.launch {
            container.futuresSourceChanges.collect { _ ->
                if (id.market != MarketType.FUTURES) return@collect
                oiSupported.value = repository.supportsOpenInterest(id.market)
                sourceVersion.update { it + 1 }
                reloadToken.update { it + 1 }
            }
        }
        // 交易规则决定显示位数；首启可能还没落库，失败留给刷新兜底
        viewModelScope.launch { runCatching { repository.syncInstruments(id.market) } }
        // 报价头来自 ticker 流（Room 缓存视图）；未加自选的标的没人替它刷新，
        // 页面存活期间自己轮询单标的快照（weight 1），否则顶部永远挂着进页时的旧价
        viewModelScope.launch {
            while (isActive) {
                repository.refreshTicker(id)
                delay(TICKER_POLL_MS)
            }
        }
        // 持仓量副图：只在「选中 OI 且当前接口有 OI 历史」时轮询。
        // 组合里带上 interval —— 换周期要按新周期的基准间隔重取；distinctUntilChanged
        // 比的是整对，只比 Boolean 会把换周期那一路吞掉。
        // 先清旧序列再取：上一段的持仓量画在新 K 线上就是一坨错线（换标的时 VM 整体重建，同样干净）。
        viewModelScope.launch {
            combine(
                chart.map { SubPaneKind.OI in it.subPanes },
                interval,
                sourceVersion,
            ) { want, selected, _ -> want to selected }
                .distinctUntilChanged()
                // collectLatest 而非「flatMapLatest + 永不出值的 flow」：上游一变就掐掉
                // 上一轮轮询重来（那写法里 flow 的 T 推不出来，是真编译错）
                .collectLatest { (want, _) ->
                    chart.update { it.copy(openInterest = null) }
                    if (!want || !oiSupported.value) return@collectLatest
                    while (viewModelScope.isActive) {
                        loadOpenInterest()?.let { series -> chart.update { it.copy(openInterest = series) } }
                        delay(OI_POLL_MS)
                    }
                }
        }
    }

    fun selectInterval(target: CandleInterval) {
        if (interval.value == target) return
        interval.value = target
        viewModelScope.launch { settings.edit { it.copy(lastIntervalKey = target.storageKey) } }
    }

    /**
     * 图表交回最新视角：手势每变一帧回调一次，复位小标同样走这里。
     *
     * 状态**同步**更新 —— 竖屏↔全屏切换时，新图表实例的首帧初值读的就是它；
     * 落盘防抖：拖动时一帧写一次 DataStore 没有意义，停手
     * [CHART_VIEW_SAVE_DEBOUNCE_MS] 后写一次即可（与桌面端滚轮的 250ms 同口径）。
     *
     * 非有限值一律丢弃（视窗宽度还必须为正）：这是存储边界的最后一道闸，
     * 手抖出的一个 NaN 不该被写进持久化偏好。
     */
    fun commitChartView(view: ChartViewState) {
        val vp = view.viewport
        if (!vp.visibleBars.isFinite() || vp.visibleBars <= 0f) return
        if (!vp.rightOffset.isFinite() || !view.priceZoom.isFinite() || !view.pricePan.isFinite()) return
        chartViewState.value = view
        chartViewSaveJob?.cancel()
        chartViewSaveJob = viewModelScope.launch {
            delay(CHART_VIEW_SAVE_DEBOUNCE_MS)
            settings.edit {
                it.copy(
                    chartVisibleBars = vp.visibleBars,
                    chartRightOffset = vp.rightOffset,
                    chartPriceZoom = view.priceZoom,
                    chartPricePan = view.pricePan,
                )
            }
        }
    }

    /** 详情页的图表偏好跨会话保留（PRD 8 设置：周期、均线、副图、布林、视角）。 */
    private suspend fun restoreChartPreferences() {
        val saved = settings.current()
        CandleInterval.fromStorageKey(saved.lastIntervalKey)?.let { interval.value = it }
        // 视角只按「意图」恢复：越界与否到用的时候由 ChartViewport.clamp 兜底
        chartViewState.value = ChartViewState(
            viewport = ChartViewport(saved.chartVisibleBars, saved.chartRightOffset),
            priceZoom = saved.chartPriceZoom,
            pricePan = saved.chartPricePan,
        )
        chart.update {
            // 两族的目录各自归一化（越界/重复/超限都丢弃）。没存过（null）才用出厂目录；
            // 存过空表就是真的空，不回填 —— 那是用户自己删光的，重启不该自己变回来。
            val maCatalog = normalizeMaCatalog(saved.maCatalog ?: DEFAULT_MA_CATALOG)
            val emaCatalog = normalizeMaCatalog(saved.emaCatalog ?: DEFAULT_EMA_CATALOG)
            it.copy(
                maVisible = saved.maVisible,
                maCatalog = maCatalog,
                // 老装机没存过颜色：按目录升序补空槽，观感与旧版一致
                maColors = normalizeMaColors(maCatalog, saved.maColors),
                // 老装机没存过小眼睛：空表 = 全都睁眼（屏幕上与升级前完全一样）
                maMuted = normalizeMuted(maCatalog, saved.maMuted),
                emaVisible = saved.emaVisible,
                emaCatalog = emaCatalog,
                emaColors = normalizeMaColors(emaCatalog, saved.emaColors),
                emaMuted = normalizeMuted(emaCatalog, saved.emaMuted),
                showBoll = saved.bollEnabled,
                subPanes = saved.subPaneKeys.toSubPaneKinds(),
                // 周期条则相反：解析不出任何一项就当没设过，回落到默认集
                intervals = saved.intervalKeys
                    .split(',')
                    .mapNotNull { key -> CandleInterval.fromStorageKey(key.trim()) }
                    .distinctBy { it.storageKey }
                    .ifEmpty { it.intervals },
            )
        }
    }

    /**
     * 保存「＋」管理对话框产出的周期条列表：顺序即展示顺序。
     * 拒绝空列表——周期条空了就没有换周期的入口，属于把自己锁在门外。
     *
     * 当前选中的周期被删掉时，切到它原位置上的那一项（后一项顶上；删的是最后一项
     * 就取新的最后一项）。不回落的后果是条上没有任何方块高亮，图上在画哪个周期
     * 只能靠猜 —— 桌面端的 `ChartView` 同样会在此处自动换周期。
     */
    fun saveIntervals(next: List<CandleInterval>) {
        if (next.isEmpty()) return
        // 顺序不能反：interval 一改，加载链会在 Main.immediate 上**同步**开跑并抓走
        // chart 的当前快照（见 init 里的注释）；先切周期再落列表，等这一页取回来
        // 整份回写时就把刚保存的列表盖回旧值 —— 表现为「删了周期，条上又冒出来」。
        val current = interval.value
        val previous = chart.value.intervals
        chart.update { it.copy(intervals = next) }
        if (next.none { it.storageKey == current.storageKey }) {
            val removedAt = previous.indexOfFirst { it.storageKey == current.storageKey }
            interval.value = next[removedAt.coerceIn(0, next.lastIndex)]
        }
        val saved = next.joinToString(",") { it.storageKey }
        viewModelScope.launch {
            settings.edit {
                it.copy(intervalKeys = saved, lastIntervalKey = interval.value.storageKey)
            }
        }
    }

    fun toggleWatch() {
        viewModelScope.launch {
            if (watchlist.contains(id)) watchlist.remove(id) else watchlist.add(id)
        }
    }

    fun toggleBoll() {
        val next = !chart.value.showBoll
        chart.update { it.copy(showBoll = next) }
        viewModelScope.launch { settings.edit { it.copy(bollEnabled = next) } }
    }

    /**
     * 指标条上那枚 chip：**整族显隐**。
     *
     * 只藏不删 —— 目录、配色与**逐条小眼睛**原样保留，再点一下就把配置好的那几条放回来。
     * 这是「总闸」，与面板里每条行尾的小眼睛（分闸）正交：关掉再打开，谁睁眼谁闭眼不动。
     * 均线全关是合法偏好（裸 K 图：判断形态时叠着均线反而看不清），
     * 因此不做「至少留一条」的兜底。与桌面端 chip 的语义一致。
     */
    fun toggleFamily(family: MaFamily) {
        val next = !chart.value.isVisible(family)
        chart.update {
            when (family) {
                MaFamily.MA -> it.copy(maVisible = next)
                MaFamily.EMA -> it.copy(emaVisible = next)
            }
        }
        viewModelScope.launch {
            settings.edit {
                when (family) {
                    MaFamily.MA -> it.copy(maVisible = next)
                    MaFamily.EMA -> it.copy(emaVisible = next)
                }
            }
        }
    }

    /**
     * 保存「＋」管理弹窗产出的某一族配置：目录 + 线色 + **逐条小眼睛**，
     * 先归一化（范围/去重/超限截断、色槽补空/去重、小眼睛只留目录内的）。
     *
     * 目录是「配了哪几档」，画不画由两级开关决定（族 chip + 每条的小眼睛）；
     * 颜色按**期数**保存，所以弹窗里增删/排序不会改已有均线的颜色。
     * 新加的档位不该继承隐藏态 —— 由 [normalizeMuted] 保证（它只认目录内的期数，
     * 但这里还要先剔掉被删掉的那些，所以传的是归一后的目录）。
     */
    fun saveCatalog(
        family: MaFamily,
        next: List<Int>,
        colors: Map<Int, Int>,
        muted: Collection<Int> = emptyList(),
    ) {
        val catalog = normalizeMaCatalog(next)
        val palette = normalizeMaColors(catalog, colors)
        val hidden = normalizeMuted(catalog, muted)
        chart.update { current ->
            when (family) {
                MaFamily.MA ->
                    current.copy(maCatalog = catalog, maColors = palette, maMuted = hidden)
                MaFamily.EMA ->
                    current.copy(emaCatalog = catalog, emaColors = palette, emaMuted = hidden)
            }
        }
        val saved = chart.value
        viewModelScope.launch {
            settings.edit {
                when (family) {
                    MaFamily.MA -> it.copy(
                        maCatalog = saved.maCatalog,
                        maColors = saved.maColors,
                        maMuted = saved.maMuted,
                    )
                    MaFamily.EMA -> it.copy(
                        emaCatalog = saved.emaCatalog,
                        emaColors = saved.emaColors,
                        emaMuted = saved.emaMuted,
                    )
                }
            }
        }
    }

    /** 副图多选：点中即切换该块的显隐，全不选 = 只留主图。 */
    fun toggleSubPane(kind: SubPaneKind) {
        // OI 是永续专属、且只有部分合约源提供历史：置灰的 chip 仍可点，点了给一句原因
        // （Android 没有 hover，桌面那套 title 提示在这儿够不着用户）。
        // 只挡「加上」不挡「撤下」—— 副图偏好是全局一份，在合约页选过 OI 再开现货页，
        // 撤不掉就成了关不掉的死开关。
        val selectedNow = kind in chart.value.subPanes
        if (kind == SubPaneKind.OI && !oiSupported.value && !selectedNow) {
            showNotice(
                if (id.market.isFutures) "当前接口没有持仓量历史（Gate / 币安系 / OKX / Bybit 有）"
                else "现货没有持仓量（永续专属）"
            )
            return
        }
        chart.update {
            val next = if (kind in it.subPanes) it.subPanes - kind else it.subPanes + kind
            it.copy(subPanes = next.sortedBy { k -> SubPaneKind.entries.indexOf(k) })
        }
        val saved = chart.value.subPanes.toSubPaneKeys()
        viewModelScope.launch { settings.edit { it.copy(subPaneKeys = saved) } }
    }

    /**
     * 划线过程中每帧回调：只更新要画的那条线，不落库。
     *
     * [ruleId] 由图表判定按下时抓住的是哪条既有告警线（null = 新划的线），
     * 一次拖拽过程中固定不变 —— 否则线拖过另一条线时会「换主角」。
     */
    fun dragAlertLine(ruleId: Long?, price: Double) {
        if (!price.isFinite()) return
        alertDrag.value = AlertDrag(ruleId, BigDecimal(price.toString()))
    }

    /**
     * 手指离开：对齐 tickSize 后直接落库。
     *
     * 对齐是必须的 —— 校验器要求目标价是 tickSize 的整数倍，而手指落点换算出来的价格
     * 几乎不可能正好落在刻度上，不处理的话每次划线都会被精度校验挡下。
     *
     * 条件统一为「达到」：涨到或跌到这条线都提醒，方向由引擎在触发时按实际穿越自动判定，
     * 也就不再需要拿现价来分辨上破/下破。
     */
    fun commitAlertLine() {
        val drag = alertDrag.value ?: return
        val aligned = alignToTick(drag.price)
        viewModelScope.launch {
            if (upsertAlertLineRule(drag.ruleId, aligned)) alertDrag.value = null
        }
    }

    /**
     * 删除一条告警线（竖栏的删除工具点中它时调用）：删掉它对应的规则，线随之从图上消失。
     *
     * 线由规则派生，删除只能落到规则上 —— 这里就是预警列表那个删除按钮的同一条路径。
     * [ruleId] 为 null 表示手上一笔还没落库的新线（点中它等同于放弃），无需删规则。
     */
    fun deleteAlertLine(ruleId: Long?) {
        if (ruleId == null) {
            alertDrag.value = null
            return
        }
        viewModelScope.launch {
            try {
                alerts.deleteRule(ruleId)
                alertDrag.value = null
                showNotice("已删除该告警线")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("删除失败：${e.displayMessage()}")
            }
        }
    }

    // ---- 视觉画线（水平线/直线，纯记号不告警） ----

    /** 本标的已落库的视觉画线。 */
    val chartLines: StateFlow<List<ChartLine>> = chartLinesRepo.lines(id.market, id.symbol)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 正在拖动的那一笔画线草稿（已换算成可落库形态，id 为 0 = 新建中）。
     * 与 alertDrag 同理放 ViewModel：全屏旋屏重建组合，草稿不能丢。
     */
    private val drawDraft = MutableStateFlow<ChartLine?>(null)
    val drawDraftLine: StateFlow<ChartLine?> = drawDraft.asStateFlow()

    /**
     * 画线拖动中每帧回调：把「分数下标锚点」按**当前**展示序列折算成绝对时间戳存草稿，
     * 不落库。存时间戳而不是下标：草稿期间翻页会前插蜡烛、下标整体平移，
     * 时间戳不受影响，绘制端每帧再按最新序列折回去，线不会跟着历史跑。
     */
    fun dragDrawLine(guide: DrawGuideLine) {
        val existing = if (guide.id != 0L) chartLines.value.firstOrNull { it.id == guide.id } else null
        drawDraft.value = guide.toChartLine(
            market = id.market,
            symbol = id.symbol,
            candles = displayCandles(chart.value, interval.value),
            interval = interval.value,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
    }

    /** 松手：草稿落库（id 为 0 新建，其余原位更新）。失败保留草稿，可换个位置再试。 */
    fun commitDrawLine() {
        val draft = drawDraft.value ?: return
        viewModelScope.launch {
            try {
                chartLinesRepo.save(draft)
                drawDraft.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("画线保存失败：${e.displayMessage()}")
            }
        }
    }

    /**
     * 删除一条视觉画线（竖栏的删除工具点中它时调用），或撤销直线两敲落得太近的那一笔。
     *
     * lineId 为 0 = 只是放弃手上的草稿；其余删掉落库的那条。
     */
    fun deleteDrawLine(lineId: Long) {
        drawDraft.value = null
        if (lineId == 0L) return
        viewModelScope.launch {
            try {
                chartLinesRepo.delete(lineId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("删除画线失败：${e.displayMessage()}")
            }
        }
    }

    /**
     * 清空本标的图上**全部划线**：告警线（连带其预警规则）+ 全部水平线 / 直线。
     *
     * 三类线在存储上是两拨，这里各走各的权威路径，不新造删法：
     * · 告警线由预警规则派生（见 [alertLines]），删线 = 删规则，与预警列表里的删除同一个方法；
     * · 水平线 / 直线在 chart_line 表里，走 [ChartLineRepository.deleteAll] 一次删净；
     * · 拖动中的草稿与告警拖拽是「还没落库的那一笔」，一并清掉，否则清空后手上还留着半条。
     *
     * 口径与 [alertLines] 严格对齐：图上凡是**有价位**的规则都会画成一条线，所以这里就按
     * `threshold != null` 取 —— 手动在预警页建的价位预警同样在图上有一条线，也一并清掉。
     * 只删本标的（规则表、画线表都按 market + symbol 收窄），别的标的不受影响。
     *
     * 不在这里问确认：调用方负责弹确认框并把「会删掉几条」写进去 —— 数量只有在那边
     * 同时拿得到 alertLines 与 chartLines 时才算得准。
     */
    fun clearAllLines() {
        viewModelScope.launch {
            try {
                val rules = symbolRules.value.filter { it.threshold != null }
                rules.forEach { alerts.deleteRule(it.id) }
                chartLinesRepo.deleteAll(id.market, id.symbol)
                drawDraft.value = null
                alertDrag.value = null
                showNotice("已清空图上的全部划线")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice("清空划线失败：${e.displayMessage()}")
            }
        }
    }

    /**
     * 锁定态下点了竖栏里那些被关掉的动作（新建 / 删除 / 清空）：把原因说出来。
     *
     * 这些按钮在锁定态是**置灰但仍可点**的 —— App 上没有 hover，置灰的死按钮说不出话，
     * 用户只会以为图坏了。与 OI chip「置灰但仍可点、点一下弹同一句原因」是同一条口径。
     */
    fun notifyLinesLocked() {
        showNotice("已锁定，图上划线全部只读 · 点竖栏顶部的锁解锁")
    }

    /**
     * 建/改这条线对应的规则，成功返回 true。
     *
     * 有 ruleId 就更新（保留 repeatMode / Webhook 等用户可能调过的字段），
     * 没有就新建 —— 拖动同一个价位反复松手不该堆出一串规则。
     * 失败时保留拖动中的线，让用户能换个价位再试一次。
     */
    private suspend fun upsertAlertLineRule(
        ruleId: Long?,
        price: BigDecimal,
    ): Boolean {
        val label = PriceFormatter.format(price, state.value.tickSize)
        val name = "${id.symbol} 达到 $label"
        return try {
            val existing = ruleId?.let { alerts.rule(it) }
            if (existing != null) {
                // 价位没变就别写：光标清零触发状态就会让一条已经响过的「单次」
                // 预警重新响一次，而用户可能只是按住线又松手。
                if (existing.threshold?.compareTo(price) == 0) return true
                // 划线引擎维护的指标规则只挪价位，名字与方向条件留给引擎下轮对号；
                // 手动线则统一成「达到」，方向由触发时的实际穿越决定
                if (existing.source == AlertRuleSource.MANUAL) {
                    alerts.saveRule(existing.copy(name = name, condition = AlertCondition.REACH, threshold = price))
                } else {
                    alerts.saveRule(existing.copy(threshold = price))
                }
                // 价位换了，旧的触发状态必须清零：否则 ONCE 的 fired 闸门会让
                // 新价位一次都不提醒，wasSatisfied 也会把真正的「穿越」边沿吃掉。
                alerts.saveState(existing.id, AlertState())
            } else {
                alerts.saveRule(
                    AlertRule(
                        market = id.market,
                        symbol = id.symbol,
                        name = name,
                        condition = AlertCondition.REACH,
                        threshold = price,
                        // 列表按 createdAt 倒序，新建的必须拿到当前时间才排在最前
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            showNotice("已设置预警 · $label")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showNotice("预警设置失败：${e.displayMessage()}")
            false
        }
    }

    /** 把价格对齐到交易规则的最小变动单位，避免建规则时被精度校验挡下。 */
    private fun alignToTick(price: BigDecimal): BigDecimal {
        val tick = state.value.tickSize ?: return price
        if (tick.signum() == 0) return price
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick)
    }

    private fun showNotice(text: String) {
        noticeText.value = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            noticeText.value = null
        }
    }

    /** 重取快照与当前周期序列。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                repository.refreshTickers(id.market)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chart.update { it.copy(error = e.displayMessage()) }
            }
            reloadToken.update { it + 1 }
        }
    }

    /** 图表拖到最左端时往更早方向翻页。 */
    fun loadMore() {
        val current = chart.value
        val base = baseInterval()
        // 闸门全部在**这一次快照**上判，且任何一条不满足都**不写任何状态**。
        // 「先写状态再返回」是旧写法的另一个坑：把「这次没请求成」记成「已经请求过」，
        // 而那个标记又在图那侧、跨周期不放行 —— 一次误记就让整段序列再也翻不动。
        if (current.loadingMore || current.loading) return
        // 手上这段序列必须正是当前周期要的那一段：换周期途中来的触发一律丢掉
        if (current.rawBase != base || current.raw.isEmpty()) return
        if (!current.hasMore) return
        val oldest = current.raw.first().openTime
        if (current.oldestRequested == oldest) return
        // 先落标记再起协程：标记与 loadingMore 都是同步写的，同一帧里的重复触发当场挡掉
        chart.update { it.copy(loadingMore = true, oldestRequested = oldest) }
        viewModelScope.launch {
            try {
                val page = repository.olderKlines(id, base, oldest, BASE_PAGE)
                val merged = (page.klines + current.raw).distinctBy { it.openTime }.sortedBy { it.openTime }
                chart.update {
                    // 在途期间换过周期/换过源取来的那一页属于上一段序列，整页丢掉换新，
                    // 不能把旧周期的基础蜡烛塞进新周期的 raw（rawBase 与 raw 必须成对）
                    if (it.rawBase != base) it
                    else it.copy(
                        raw = merged.takeLast(MAX_RAW),
                        hasMore = page.hasMore && page.klines.isNotEmpty(),
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chart.update { it.copy(loadingMore = false, error = e.displayMessage()) }
            }
        }
    }

    /**
     * 取一段完整的序列（[BASE_PAGE] 根基础周期蜡烛）+ 它归属的基础周期。
     *
     * **`rawBase` 与 `raw` 必须同时更新**：这一对就是「哪一段序列是就绪的」的答案。
     * 取失败时两者一起作废（不能只清 raw 留着 rawBase，也不能反过来）。
     *
     * 翻页去重标记 [ChartData.oldestRequested] 也必须在这里归零：它记的是
     * 「为上面这一段序列请过哪一页」，序列一换它就没有意义了 —— 留着不放的后果正是
     * 换周期之后再也翻不动（见该字段的说明）。
     */
    private suspend fun loadBase(selected: CandleInterval): ChartData {
        val base = baseOf(selected)
        val previous = chart.value
        return try {
            val page = repository.klines(id, base, BASE_PAGE)
            previous.copy(
                raw = page.klines,
                rawBase = base,
                origin = page.origin,
                hasMore = page.hasMore,
                loading = false,
                loadingMore = false,
                oldestRequested = null,
                error = if (page.origin == DataOrigin.CACHE) CACHE_HINT else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previous.copy(
                raw = emptyList(),
                rawBase = null,
                loading = false,
                loadingMore = false,
                oldestRequested = null,
                error = e.displayMessage(),
            )
        }
    }

    /**
     * 取持仓量序列（对齐到蜡烛时间轴在绘制层做）。
     *
     * 请求基准用**基础周期**而不是展示周期：自定义周期下展示根数 = 基础根数 ÷ 聚合倍率，
     * 按基础口径取 500 根恰好覆盖整段原始窗口（官方周期两者相同，与桌面端一致）。
     * 仓库层把网络失败归成 null —— 返回 null 表示「这一轮没拿到」，保留上一轮数据不覆盖。
     */
    private suspend fun loadOpenInterest(): OpenInterestSeries? =
        repository.openInterest(id, baseInterval().minutes, BASE_PAGE)

    /**
     * 把实时推送的最新一根并进已加载序列（同一根替换、更新的一根追加；迟到的旧蜡烛丢掉）。
     *
     * [base] 是这根增量所属的基础周期。**对不上就不能并**：增量是「往序列上补一根」，
     * 序列本身还没到（首屏）或者还在别的周期上（刚换了周期）时，凭空补出来的就是
     * 「一根柱子的图」—— 等整页到齐再整个换掉，正是用户看到的「先冒一根、再跳出正确的图」。
     * 首屏那一根也不需要在这里补：整页请求本身就会带回最新一根。
     *
     * 这里**不碰 `loading`**：加载与否由取数的链说了算，增量顺手把它清掉会让
     * 重载途中误判成已就绪。
     */
    private fun mergeBaseCandle(base: CandleInterval, update: Kline) {
        chart.update { state ->
            val list = state.raw
            if (state.rawBase != base || list.isEmpty()) return@update state
            val last = list.last()
            val merged = when {
                update.openTime == last.openTime -> list.dropLast(1) + update
                update.openTime > last.openTime -> (list + update).takeLast(MAX_RAW)
                else -> list // 重连后迟到的旧蜡烛交给刷新，不倒着改历史
            }
            if (merged === list) state else state.copy(raw = merged)
        }
    }

    /**
     * 当前展示周期的序列；还没取到（[ChartData.rawBase] 与它不符）时为空。
     *
     * 空不等于「没有数据」—— 调用方用 [DetailUiState.loadingCandles] 区分
     * 「正在取」与「取到了是空的」。
     */
    private fun displayCandles(data: ChartData, selected: CandleInterval): List<Kline> {
        if (data.rawBase != baseOf(selected)) return emptyList()
        return KlineAggregator.aggregate(data.raw, selected)
    }

    /**
     * 展示周期对应的**取数周期**：官方周期就是它自己，自定义周期取能整除它的最大官方周期
     * （原始蜡烛按这个周期存，展示时再聚合）。详情页里所有「按基础周期取数」的地方
     * （首屏整页、翻页、持仓量、实时增量）都走它，口径只有一处。
     */
    private fun baseOf(selected: CandleInterval): CandleInterval =
        selected.baseInterval?.let(CandleInterval::of) ?: selected

    private fun baseInterval(): CandleInterval = baseOf(interval.value)

    private companion object {
        /** 基础周期的请求条数；自定义周期下展示根数按聚合倍率减少。 */
        const val BASE_PAGE = 500

        /** 画一条灰参考曲线时取的历史根数：够指标成形并铺满当前视窗即可。 */
        const val GUIDE_CANDLES = 300

        /** 内存里保留的最大基础蜡烛数，翻页过多时丢弃最老的。 */
        const val MAX_RAW = 2_000
        const val CACHE_HINT = "网络不可用，K 线展示的是本地缓存"

        /** 一次性提示的停留时长。 */
        const val NOTICE_MS = 3_000L

        /** 详情页表头报价的 REST 轮询间隔（与仓库层 K 线轮询同频）。 */
        const val TICKER_POLL_MS = 2_000L

        /**
         * 持仓量副图的轮询间隔。最细的原生 OI 周期是 5m（Gate 有 1m 是例外），
         * 10 秒整段重拉已经远快于数据本身的更新速度；单次是一根轻请求（weight 1）。
         */
        const val OI_POLL_MS = 10_000L

        /**
         * 视角落盘的防抖时长：手势停手后写一次。
         * 防的是「拖一帧写一帧库」，不是怕写坏 —— DataStore 本身是原子写。
         */
        const val CHART_VIEW_SAVE_DEBOUNCE_MS = 250L
    }
}

/**
 * 副图选择与持久化串之间的转换。
 *
 * 存成逗号分隔的名字（而不是序号）是为了：① 枚举增删不影响历史数据；
 * ② 空串天然表达「不显示副图」，不需要额外的哨兵值。
 */
private fun String.toSubPaneKinds(): List<SubPaneKind> {
    if (isBlank()) return emptyList()
    return split(',').mapNotNull { name -> SubPaneKind.entries.firstOrNull { it.name == name.trim() } }
}

private fun List<SubPaneKind>.toSubPaneKeys(): String = joinToString(",") { it.name }
