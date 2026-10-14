package com.waxilo.marketmonitor.ui.detail

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.alert.AlertRuleSource
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DEFAULT_MA_CATALOG
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.chart.AlertPriceLine
import com.waxilo.marketmonitor.ui.chart.BandGuideLine
import com.waxilo.marketmonitor.ui.chart.ChartCornerAction
import com.waxilo.marketmonitor.ui.chart.ChartModel
import com.waxilo.marketmonitor.ui.chart.ChartSkeleton
import com.waxilo.marketmonitor.ui.chart.ChartViewState
import com.waxilo.marketmonitor.ui.chart.DrawGuideLine
import com.waxilo.marketmonitor.ui.chart.DrawTool
import com.waxilo.marketmonitor.ui.chart.IndicatorGuideLine
import com.waxilo.marketmonitor.ui.chart.KlineChart
import com.waxilo.marketmonitor.ui.chart.LineRef
import com.waxilo.marketmonitor.ui.chart.SUB_PANE_HEIGHT_DP
import com.waxilo.marketmonitor.ui.chart.SubPaneKind
import com.waxilo.marketmonitor.ui.chart.toGuideLine
import com.waxilo.marketmonitor.ui.common.AnimatedBanner
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.FilterChip
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.MetricCell
import com.waxilo.marketmonitor.ui.common.RangeBar
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SectionOverline
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.HeroPriceStyle
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Motion
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * 详情页（PRD 4.1 第三级、4.2 图表）。
 *
 * 版面重排的要点：
 * 1. **价格从顶栏里搬出来**，成为独立的 Hero 区块。旧版把价格塞在 AppBar 的 actions 里，
 *    和三个图标按钮抢横向空间，长价格（如 0.00004321）必然被截断；
 * 2. 顶栏只留返回 + 标题 + 一个溢出菜单，把「刷新 / 建预警 / 自选」收进菜单；
 * 3. Hero 下方直接给「24h 高低区间条」——一屏数字说不出「现在离高点还有多远」，
 *    一根条能。
 * 4. 图表高度按屏高自适应，不再写死 360dp：小屏上 360dp 会挤掉统计区，大屏上又显小。
 */
@Composable
fun DetailScreen(
    symbolId: SymbolId,
    onBack: () -> Unit,
    onCreateAlert: () -> Unit,
    onEditRule: (Long) -> Unit,
    viewModel: DetailViewModel = appViewModel(key = "detail:${symbolId.storageKey}") {
        DetailViewModel(it, symbolId)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 提到这一层是因为竖屏图与全屏图都要画告警线：线由预警规则派生，在哪个模式下看都该一致
    val alertLines by viewModel.alertLines.collectAsStateWithLifecycle()
    // 「不告警」的划线画成灰参考曲线，同样两种视图都要画
    val indicatorGuides by viewModel.indicatorGuides.collectAsStateWithLifecycle()
    // 指标线模式均线带的两条灰色锚点水平线（引擎择近 + 穿越后静默换锚），同样两种视图共用
    val bandGuides by viewModel.bandGuides.collectAsStateWithLifecycle()
    val indicatorLines by viewModel.indicatorLines.collectAsStateWithLifecycle()
    val symbolRules by viewModel.symbolRules.collectAsStateWithLifecycle()
    // 同样共用一个开关：在竖屏把线藏了，进全屏不该又冒出来
    val alertLinesVisible by viewModel.alertLinesVisible.collectAsStateWithLifecycle()
    // 视觉画线（水平线/直线）：竖屏只显不可编，全屏可编辑 —— 两种视图同一份数据
    val chartLines by viewModel.chartLines.collectAsStateWithLifecycle()
    val drawDraft by viewModel.drawDraftLine.collectAsStateWithLifecycle()
    // 每次现折：时间戳→下标依赖「这份序列的头一根」，翻页/切周期都要整体重折
    val drawLines = remember(chartLines, drawDraft, state.candles, state.interval) {
        val saved = chartLines.mapNotNull { it.toGuideLine(state.candles, state.interval) }
        val draft = drawDraft?.toGuideLine(state.candles, state.interval)?.copy(dragging = true)
        if (draft == null) saved else saved.filterNot { it.id == draft.id } + draft
    }
    /**
     * 用 `rememberSaveable` 而不是普通 state：全屏要横屏，旋屏会让 Activity 重建，
     * 普通 state 会连同「正在全屏」一起丢掉，用户看到的是自动退出全屏。
     */
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // 周期管理对话框：竖屏与全屏共用一个开关，挂在最外层免得两条分支各存一份
    var intervalManagerOpen by rememberSaveable { mutableStateOf(false) }
    // 均线周期管理对话框同理：入口在竖屏与全屏的指标条上各有一个「＋」
    var maManagerOpen by rememberSaveable { mutableStateOf(false) }
    // 铃铛预警整页与划线编辑框：IndicatorLine 不可序列化，旋屏丢了重开即是（同周期管理草稿）
    var alertsPageOpen by rememberSaveable { mutableStateOf(false) }
    var lineDialogOpen by remember { mutableStateOf(false) }
    var lineEditing by remember { mutableStateOf<IndicatorLine?>(null) }
    /**
     * 十字光标下标，竖屏图与全屏图共用：K 线详情小条常显在图表顶部、读数带正上方，
     * 长按跟着手指、松手**停住**（画布上的平移/缩放/轻点才把它清掉、回到最新一根）。
     * 图表内部已按标的/周期复位，切页时它会以 null 回调一次，这里不必再挂 key。
     */
    var crosshairIndex by remember { mutableStateOf<Int?>(null) }

    FullscreenController(active = fullscreen)
    // 预警整页盖在详情页之上，系统返回键要先退这一层而不是整个详情页路由
    BackHandler(enabled = alertsPageOpen) { alertsPageOpen = false }

    Box(modifier = Modifier.fillMaxSize().background(MarketTheme.colors.paper)) {
        if (fullscreen) {
            FullscreenChart(
                state = state,
                alertLines = alertLines,
                indicatorGuides = indicatorGuides,
                bandGuides = bandGuides,
                drawLines = drawLines,
                alertLinesVisible = alertLinesVisible,
                viewModel = viewModel,
                onManageIntervals = { intervalManagerOpen = true },
                onManageMa = { maManagerOpen = true },
                onExit = { fullscreen = false },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailTopBar(
                    state = state,
                    onBack = onBack,
                    onToggleWatch = viewModel::toggleWatch,
                    onShowAlerts = { alertsPageOpen = true },
                    onShowLines = {
                        lineEditing = indicatorLines.firstOrNull()
                        lineDialogOpen = true
                    },
                )

                val error = state.error
                AnimatedBanner(visible = error != null, text = error.orEmpty(), tone = BannerTone.Error)
                AnimatedBanner(
                    visible = error == null && state.origin == DataOrigin.CACHE,
                    text = "K 线来自本地缓存",
                )

                Hero(state = state)

                Rule(inset = Spacing.Gutter)

                IntervalSelector(
                    options = state.intervals,
                    selected = state.interval,
                    onSelect = viewModel::selectInterval,
                    onManage = { intervalManagerOpen = true },
                )

                ChartArea(
                    state = state,
                    alertLines = alertLines,
                    indicatorGuides = indicatorGuides,
                    bandGuides = bandGuides,
                    drawLines = drawLines,
                    alertLinesVisible = alertLinesVisible,
                    onToggleAlertLines = viewModel::toggleAlertLines,
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::refresh,
                    onFullscreen = { fullscreen = true },
                    onCrosshairIndexChange = { crosshairIndex = it },
                    // 视角全局一份：初值取 ViewModel 当前值（全屏那份的调整也在里面），
                    // 变化交回去 —— 切周期、换标的、重启都不重置
                    initialView = viewModel.chartView.value,
                    onViewChange = viewModel::commitChartView,
                    // 小条不属于周期：它画进图表顶部、与指标读数带同一个容器，上下对读
                    candleReadout = { CandleReadoutRow(state = state, index = crosshairIndex) },
                )

                IndicatorBar(
                    maCatalog = state.maCatalog,
                    activeMa = state.maPeriods,
                    showBoll = state.showBoll,
                    subPanes = state.subPanes,
                    oiSupported = state.oiSupported,
                    onToggleMa = viewModel::toggleMaPeriod,
                    onManageMa = { maManagerOpen = true },
                    onToggleBoll = viewModel::toggleBoll,
                    onToggleSubPane = viewModel::toggleSubPane,
                )

                Rule(inset = Spacing.Gutter, strong = true)

                StatsSection(stats = state.stats)

                Text(
                    text = "预警由常驻通知保活；杀掉进程后检测会停止",
                    modifier = Modifier.fillMaxWidth().padding(
                        start = Spacing.Gutter,
                        end = Spacing.Gutter,
                        top = Spacing.Sm,
                        bottom = Spacing.Xl,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MarketTheme.colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (intervalManagerOpen) {
            IntervalManagerDialog(
                initial = state.intervals,
                selected = state.interval,
                onDismiss = { intervalManagerOpen = false },
                onSave = {
                    viewModel.saveIntervals(it)
                    intervalManagerOpen = false
                },
            )
        }

        if (maManagerOpen) {
            MaManagerDialog(
                catalog = state.maCatalog,
                onDismiss = { maManagerOpen = false },
                onSave = {
                    viewModel.saveMaCatalog(it)
                    maManagerOpen = false
                },
            )
        }

        // 本标的预警整页：从右往左滑入、盖住详情页（不再是底部弹层）。
        // 挂在 Box 最外层作最上层子节点，返回键/左上角箭头都收在这页里。
        AnimatedVisibility(
            visible = alertsPageOpen,
            enter = slideInHorizontally(tween(Motion.BaseMs, easing = Motion.Emphasized)) { it } +
                fadeIn(tween(Motion.BaseMs)),
            exit = slideOutHorizontally(tween(Motion.BaseMs, easing = Motion.Emphasized)) { it } +
                fadeOut(tween(Motion.FastMs)),
            modifier = Modifier.zIndex(1f),
        ) {
            SymbolAlertsPage(
                symbolId = symbolId,
                manualRules = symbolRules.filter { it.source != AlertRuleSource.INDICATOR },
                onDismiss = { alertsPageOpen = false },
                onNewRule = {
                    alertsPageOpen = false
                    onCreateAlert()
                },
                onEditRule = { id ->
                    alertsPageOpen = false
                    onEditRule(id)
                },
                onToggleRule = viewModel::setRuleEnabled,
                onDeleteRule = viewModel::deleteRule,
            )
        }

        if (lineDialogOpen) {
            IndicatorLineDialog(
                symbolId = symbolId,
                maCatalog = state.maCatalog,
                initial = lineEditing,
                onDismiss = { lineDialogOpen = false },
                onSave = { line ->
                    viewModel.saveIndicatorLine(line)
                    lineDialogOpen = false
                },
            )
        }
    }
}

/**
 * 进全屏 = 横屏 + 隐藏系统栏；退出时逐一还原。
 *
 * 两个容易踩的点：
 * 1. 恢复方向必须**跳过旋屏重建**的那一次 —— 新的 Activity 会立刻再次请求横屏，
 *    这里若抢先置回 `UNSPECIFIED`，两者就会打架（表现为闪一下竖屏再横过来）；
 * 2. 重建后系统栏的可见性会回到默认值，所以隐藏动作必须随新的 Activity 再做一遍，
 *    这也是把副作用挂在 `active` 而非「进入时执行一次」的原因。
 */
@Composable
private fun FullscreenController(active: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

/**
 * 全屏图表：整屏只有图，顶部一条窄操作栏 + 右侧一条划线竖栏。
 *
 * 划线从顶栏挪到**右侧竖栏**（2026-10-12 定版）：三个工具（告警线/水平线/直线）挤在
 * 周期条里既放不下，也把「看哪个周期」和「要对图做什么」两件不相干的事混在一栏。
 * 竖栏纵向排开工具按钮，单选；再点当前选中的 = 退回普通看图态。
 *
 * 工具激活时单指被图征用，**且不需要长按** —— 划线就是这个工具下唯一的目的：
 * · 告警线：纵向拖出即拖一根「达到」预警（涨跌碰到都提醒）；
 * · 水平线：拖出即一条纯视觉记号（不响）；
 * · 直线：第一敲落起点、第二敲落终点（两下太近 = 撤销），抓到已有直线可整体平移。
 * 双指缩放照旧可用。画线是**一次性**的：松手落库后自动退回普通态，
 * 单指随即恢复平移画布 —— 常驻工具会让「画完线想看别的区间」做不到。
 * 已有的告警线在工具下按到它（容差 24dp）是**改它**而不是在旁边叠一条。
 *
 * 换周期与改指标也在这里给到：全屏是横屏，竖屏那套控件（周期条、指标条）整块被图表顶掉了，
 * 只剩一个「退出全屏」的话，用户每次想换个周期都得先退出、改完、再进来。
 * 两者都做成**常驻的窄带**、钉在图表上方，与竖屏「控件常驻在图的上/下方」语义一致：
 * 周期是左端可横滚的一条，指标一条紧跟其下 —— 换周期与改指标都是高频动作，
 * 做成「按需展开再选、选完又收起」的浮层的话，改一次要点三次。
 * **全屏不画副图**：横屏高度本来就紧张，一条副图窗格换来的信息量抵不上它吃掉的蜡烛区，
 * 副图留给竖屏看；指标条里的「副图」一组也一并收掉（`showSubPanes = false`），
 * 免得留下点了在全屏里没有任何反应的开关。
 */
@Composable
private fun FullscreenChart(
    state: DetailUiState,
    alertLines: List<AlertPriceLine>,
    indicatorGuides: List<IndicatorGuideLine>,
    bandGuides: List<BandGuideLine>,
    drawLines: List<DrawGuideLine>,
    alertLinesVisible: Boolean,
    viewModel: DetailViewModel,
    onManageIntervals: () -> Unit,
    onManageMa: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MarketTheme.colors
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    /**
     * 划线锁：**进入全屏默认锁定**（用户口径）。锁定 = 整张图只读 ——
     * 已有的线不能拖、不能删，也不能新建；要动线先解锁。
     *
     * 和 [toolName] 一样用 `rememberSaveable`：全屏要横屏，旋屏重建不能把锁弄丢
     * （否则用户解锁后一旋屏，手上那根线又变回不可动，像是操作没生效）。
     */
    var linesLocked by rememberSaveable { mutableStateOf(true) }
    // 挂在全屏内部：退出全屏再进来就该回到普通看图态，不该还停在划线工具上。
    // 存 name 而不是枚举本身：DrawTool 不在默认 Saver 的支持面里，旋屏重建不能丢工具态。
    var toolName by rememberSaveable { mutableStateOf(DrawTool.NONE.name) }
    // 锁定态下把工具**一律收成 NONE**：解锁前手上还举着「水平线」的话，
    // 单指会被划线通道吃掉却什么也画不出来（锁定 = 不许新建），看着像图卡住了。
    // 锁定那一刻也主动把工具收掉（见锁按钮），所以这里只是兜底。
    val drawTool = if (linesLocked) {
        DrawTool.NONE
    } else {
        DrawTool.entries.firstOrNull { it.name == toolName } ?: DrawTool.NONE
    }
    fun selectTool(next: DrawTool) { toolName = next.name }
    /**
     * 拖动中的线：整条竖栏变成垃圾桶落点。
     *
     * 由**已经落库/预览的线自己的 dragging 标记**推出来，而不是手势回调里另报一个布尔 ——
     * 那个布尔一旦在异常路径（换周期、旋屏、组合被销毁）漏掉一次收尾，竖栏就永久变成垃圾桶。
     * 草稿本来就是「正在拖」的唯一事实来源，用它是免费的，也不会漏收尾。
     */
    val draggingLine = alertLines.any { it.dragging } || drawLines.any { it.dragging }
    // 十字光标下标也本地持有：进出全屏各用各的长按，不该互相串
    var crosshairIndex by remember { mutableStateOf<Int?>(null) }
    // 清空划线的确认框：不进 DrawTool（它不是一种「模式」，按一次就完事），
    // 只在弹与不弹之间切换。挂全屏内部同 toolName —— 退出再进来不该还留着这个框。
    var clearConfirm by remember { mutableStateOf(false) }
    // 图上有几笔可清：告警线按条数（一条规则一条线），画线只数**已落库**的
    // （id 为 0 的是手上那笔草稿，清空本来就把它一起丢掉，但报数不能算它）
    val clearAlertCount = alertLines.count { !it.dragging }
    val clearLineCount = drawLines.count { it.id != 0L }
    val clearable = clearAlertCount > 0 || clearLineCount > 0

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        // 顶部两条常驻窄带：上面「周期 + 划线 / 退出」，下面「指标」。
        // 两者都不压图：横屏高度紧，浮在图上会盖住 K 线，钉成图上方的一小段最省心。
        // 周期在左侧可横滚，右侧两个动作固定常驻（退出入口不能被滚走）。
        IntervalSelector(
            options = state.intervals,
            selected = state.interval,
            onSelect = viewModel::selectInterval,
            onManage = onManageIntervals,
            compact = true,
            actions = {
                // 划线工具不在这里：它们搬到了图右侧的竖栏（DrawToolRail），顶栏只留「退出」
                IconButton(onClick = onExit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "退出全屏",
                        modifier = Modifier.size(16.dp),
                        tint = colors.ink,
                    )
                }
            },
        )
        Rule(inset = 0.dp)
        // 指标常驻：与竖屏一致，改 MA / BOLL 不必先展开浮层。副图全屏不画，选项也收掉。
        IndicatorBar(
            maCatalog = state.maCatalog,
            activeMa = state.maPeriods,
            showBoll = state.showBoll,
            subPanes = state.subPanes,
            oiSupported = state.oiSupported,
            onToggleMa = viewModel::toggleMaPeriod,
            onManageMa = onManageMa,
            onToggleBoll = viewModel::toggleBoll,
            onToggleSubPane = viewModel::toggleSubPane,
            compact = true,
            showSubPanes = false,
        )
        Rule(inset = 0.dp)
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 图本体吃剩余宽度，右侧竖栏是**分出来的一栏**而不是浮在图上 ——
            // 图右沿就是价格刻度，浮层盖上去等于把刻度藏了
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when {
                // 全屏里换周期也会经历「这一段序列还没到」：与竖屏同处理（空图 + 遮罩），
                // 否则这个空窗里会报一句「没有取到 K 线」
                state.loadingCandles && state.candles.isEmpty() -> ChartLoading(state)

                state.candles.isEmpty() -> HintRow(
                    title = "没有取到 K 线",
                    subtitle = state.error ?: "换个周期或重新加载试试",
                    actionLabel = "重新加载 →",
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> {
                    // 全屏不画副图：显式给空列表，序列与视口都按「没开副图」算。
                    // 副图选择是全局偏好（竖屏那份），这里只影响全屏这一帧的渲染。
                    val series = remember(
                        state.candles,
                        state.maPeriods,
                        state.showBoll,
                        state.openInterest,
                    ) {
                        ChartModel.build(
                            candles = state.candles,
                            maPeriods = state.maPeriods,
                            showBoll = state.showBoll,
                            subPanes = emptyList(),
                            openInterest = state.openInterest,
                        )
                    }
                    KlineChart(
                        series = series,
                        interval = state.interval,
                        tickSize = state.tickSize,
                        symbolKey = state.id.storageKey,
                        // 视角是全局偏好：首帧初值从 ViewModel 读（竖屏那份的每次变化
                        // 都同步在它上面），进去后的变化同样交回去 —— 两个实例接力不掉队
                        initialView = viewModel.chartView.value,
                        onViewChange = viewModel::commitChartView,
                        onLoadMore = viewModel::loadMore,
                        onCrosshairIndexChange = { crosshairIndex = it },
                        // 与竖屏同位：图表顶部、读数带正上方的同一个容器
                        candleReadout = { CandleReadoutRow(state = state, index = crosshairIndex) },
                        // 全屏专属：竖屏那块 42sp 的 Hero 在这里没有立足之地（横屏要留给图），
                        // 现价改钉在读数带右端，字号与位置都受读数带约束
                        lastPriceBoard = { LastPriceBoard(state = state) },
                        alertLines = alertLines,
                        indicatorGuides = indicatorGuides,
                        bandGuides = bandGuides,
                        drawLines = drawLines,
                        drawTool = drawTool,
                        alertLinesVisible = alertLinesVisible,
                        onToggleAlertLines = viewModel::toggleAlertLines,
                        onAlertLineDrag = viewModel::dragAlertLine,
                        onAlertLineCommit = {
                            viewModel.commitAlertLine()
                            // 划线是一次性动作：落一根就收起工具，单指拖动立刻还给平移画布；
                            // 画下一根需重新点竖栏。删除同样收起，见 onAlertLineDelete。
                            selectTool(DrawTool.NONE)
                        },
                        onAlertLineDelete = { id ->
                            viewModel.deleteAlertLine(id)
                            // 删一条也算「用完一次划线」：留在工具里只会让人误以为还能继续拖线
                            selectTool(DrawTool.NONE)
                        },
                        onDrawLineDrag = viewModel::dragDrawLine,
                        onDrawLineCommit = {
                            viewModel.commitDrawLine()
                            selectTool(DrawTool.NONE)
                        },
                        onDrawLineDelete = { lineId ->
                            viewModel.deleteDrawLine(lineId)
                            selectTool(DrawTool.NONE)
                        },
                        // 解锁态：不选工具也能按住线直接拖（水平线改价位、直线整体平移）
                        linesEditable = !linesLocked,
                        // 右侧竖栏就是删除落点：拖到它上面松手即删（锁定态不给这条路）
                        lineDropToTrailingRail = !linesLocked,
                        onLineDropDelete = { ref ->
                            when (ref) {
                                is LineRef.Alert -> viewModel.deleteAlertLine(ref.ruleId)
                                is LineRef.Guide -> viewModel.deleteDrawLine(ref.lineId)
                                // ref 为 null = 落到垃圾桶上的是「刚新建的那根」：
                                // 按当前工具把对应的草稿清掉（拖废纸篓本来就不该留下新线）
                                null -> when (drawTool) {
                                    DrawTool.ALERT -> viewModel.deleteAlertLine(null)
                                    DrawTool.HORIZONTAL -> viewModel.deleteDrawLine(0L)
                                    else -> Unit
                                }
                            }
                            // 拖到垃圾桶上删完 = 这一笔用完：与「删完自动收起」同一个出口
                            selectTool(DrawTool.NONE)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 指标改动走顶部常驻条，图上不再有浮层——浮层无论压顶部还是底部都要吃掉一截图。
            // 底部提示按当前工具给一句：几个工具的落笔方式不一样，混在一句里等于没说。
            //
            // ⚠️ 文案必须**短到一行放得下**（2026-10-12 修）：这里是 maxLines=1，
            // 而原先 TREND 那句 88 字，在 640dp 宽的横屏上被父容器裁掉了一半 ——
            // 被裁的正是「删除怎么用」那半句，用户于是只看到怎么画。删减原则：
            // **怎么删 > 怎么画 > 题外话**；而「删除」现在由竖栏那枚独立按钮说清，
            // 这三句只管怎么画（各留一句最不容易猜错的：告警线会响、水平线落一根即收、直线两敲）。
            val toolHint = when {
                // 锁定态不摆常驻提示：默认就是锁定，一进全屏就挂条横幅太吵。
                // 那条「锁了」的话由点竖栏里置灰按钮时的提示负责说（notifyLinesLocked）。
                linesLocked -> null
                // 解锁但没选工具：这一档新增了「按住线就能拖」，不说一句没人会去试
                drawTool == DrawTool.NONE ->
                    "按住线即可拖动 · 拖到右侧竖栏松手删除"
                drawTool == DrawTool.ALERT ->
                    "上下拖动放告警线 · 涨跌都提醒 · 落一根即收起"
                drawTool == DrawTool.HORIZONTAL ->
                    // 「按住可改价位」已删（2026-10-14）：工具下不再抓旧线，改价归解锁态直接拖
                    "拖动放水平线 · 落一根即自动收起"
                drawTool == DrawTool.TREND ->
                    "拖动图中准星，松手定第一点 · 再拖一次松手连成线（太近=撤销）"
                // 删除模式与上面三枚是相反方向的动作用，这句必须**先说要做什么**：
                // 用户点完删除图上看不出任何变化（线本来就在），不说就会以为没生效
                else ->
                    "点一下要删的那条线即删除 · 告警线/水平线/直线都可以 · 删完自动收起"
            }
            toolHint?.let { hint ->
                Text(
                    text = hint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Spacing.Lg)
                        // padding 写在 clip/background 之前：它把父容器的可用宽度收窄一道，
                        // 于是文字真的会换行，而不是撑破父容器被裁掉
                        .padding(horizontal = Spacing.Md)
                        .clip(Radius.fullShape)
                        .background(colors.washStrong)
                        .padding(horizontal = Spacing.Md, vertical = Spacing.Xxs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    // 兜底：万一以后有人把文案写长了，宁可换两行，也不要被裁掉半句
                    maxLines = 2,
                )
            }

            // 提示浮在底部：顶部是指标读数带与最新蜡烛，浮层压上去代价最大
            AnimatedBanner(
                visible = notice != null,
                text = notice.orEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.Gutter),
            )
            }
            DrawToolRail(
                tool = drawTool,
                clearable = clearable,
                locked = linesLocked,
                onToggleLock = {
                    linesLocked = !linesLocked
                    // 上锁顺手把工具收掉：锁定 = 单指还给平移画布，
                    // 留着「水平线」在下一次解锁时会突然又是新建模式，很容易误画一根
                    if (linesLocked) selectTool(DrawTool.NONE)
                },
                // 拖动中的线 → 整栏变成垃圾桶落点
                dropActive = draggingLine,
                onSelect = { next ->
                    // 锁定态：按钮置灰但**仍可点**，点一下把「为什么不能动」说出来。
                    // 真做成禁用的话，App 上没有 hover，用户只会以为图坏了。
                    if (linesLocked) {
                        viewModel.notifyLinesLocked()
                    } else {
                        // 再点当前选中的 = 收起工具，单指还给平移画布（与落一根后自动收起同一个出口）
                        selectTool(if (next == drawTool) DrawTool.NONE else next)
                    }
                },
                // 不做二次点击确认（「再点一次就清」那种）：清空连带删预警规则、不可撤销，
                // 得让用户在看清删掉几条之后再说「是」
                onClear = {
                    if (linesLocked) viewModel.notifyLinesLocked() else clearConfirm = true
                },
            )
        }
    }

    if (clearConfirm) {
        ClearAllLinesDialog(
            alertCount = clearAlertCount,
            lineCount = clearLineCount,
            onDismiss = { clearConfirm = false },
            onConfirm = {
                clearConfirm = false
                viewModel.clearAllLines()
                // 线都清了，手上的工具自然没有对象可抓：与「删完自动收起」同一个出口
                selectTool(DrawTool.NONE)
            },
        )
    }
}

/**
 * 清空全部划线的二次确认：**先把会删掉什么说清楚**再让点。
 *
 * 这一下与「删除」那一枚不是一回事：删除是用户拿手指在图上点名，清空是闭着眼扫掉一整张图，
 * 而且告警线背后挂着预警规则（可能带 Webhook）。所以这里报两类线的**条数**，
 * 并单独点明「预警规则一并删除」—— 用户在预警页辛苦配过的东西就在这一下里。
 *
 * 一条线都没有时这一枚本来就是置灰的（见 [DrawToolRail] 的 clearable），
 * 对话框不会以「将删除 0 条」的样子出现。
 */
@Composable
private fun ClearAllLinesDialog(
    alertCount: Int,
    lineCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MarketTheme.colors
    val parts = buildList {
        if (alertCount > 0) add("$alertCount 条告警线（对应的预警规则一并删除）")
        if (lineCount > 0) add("$lineCount 条水平线 / 直线")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "清空全部划线？", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        },
        text = {
            Text(
                text = "将删除本标的图上的全部划线：${parts.joinToString("、")}。删除后无法恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "清空", color = colors.down, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消", color = colors.muted) }
        },
    )
}

/**
 * 竖栏的两把尺子：按钮与图形。
 *
 * 单独抽出来是因为它们得**一起**改 —— 图形占按钮一半（12 / 24）是这枚按钮「不显胖」的
 * 全部秘密：把按钮改小而不动图形，图形就贴到圆边上了；反过来把图形改小而按钮不动，
 * 圆钮里会出现一圈空。抽成常量后至少改的时候看得见另一半。
 */
private val RAIL_BUTTON = 24.dp

/**
 * 图形的边长：12dp，按钮的一半。
 *
 * 图形里的线宽一律取 `size.width * 0.11f`（各 Glyph 里那一行 `val stroke = ...`）：
 * 12dp 画布在 3x 屏上是 36px，0.11 正好落在 4px —— 最干净的一档；沿用原先的 0.10
 * 会落在 3.6px，描边两侧各被抗锯齿吃掉一圈，图形看着发虚发灰。
 */
private val RAIL_GLYPH = 12.dp

/**
 * 全屏右侧的划线竖栏：锁 · 告警线 / 水平线 / 直线 · 删除 / 清空。
 *
 * 图标全部手搓 Canvas（同 [FullscreenGlyph] 的理由：只依赖 icons-core，
 * 不为一个图标拖进 material-icons-extended）。三枚线型刻意做成「这条线长什么样」：
 * 虚线 = 会响的告警线，实水平线 / 斜线 = 只做记号的画线，形状自身就是说明。
 *
 * **首枚是锁**，它管的是下面整栏：锁定 = 整张图只读（不能新建、不能拖、不能删），
 * 进入全屏默认锁定。它是整栏的总开关，所以摆最上面、与下面用一道细线隔开。
 * 锁定态下其余按钮**置灰但仍可点**：点一下弹一句「先解锁」—— App 上没有 hover，
 * 真正禁用的按钮说不出话，用户只会以为图坏了（与 OI chip 那条口径相同）。
 *
 * 删除 / 清空与三枚线型之间也有一道细线：它们回答的是另一个问题 ——「这些线怎么撤掉」。
 * 子删除有两条路：① 那枚「删除」选中后点图上的线（[DrawTool.ERASE]）；
 * ② **按住线直接拖到这条竖栏上松手**（拖线中整栏会变成垃圾桶，见 [dropActive]）。
 * 第 ② 条与第 ① 条互补：拖动本来就是「改线」的手势，改了不如意时手已经在半路，
 * 顺手拖出去比先松手、再点删除、再点那条线少两步。
 * （2026-10-12 曾有一条「拖到画布右上角垃圾桶」的路被撤掉，原因是那个落点
 * **平时不存在**、要靠猜；这次是把落点做成**拖线时才出现**的整栏高亮，看得见才敢拖。）
 * 清空是一次性动作，不进任何模式，按下去先弹确认（见 [ClearAllLinesDialog]），
 * 一条线都没有时整枚置灰 —— 空图上摆一个按了没反应的按钮只会让人怀疑它坏了。
 *
 * 规格按「图上配角」定：2026-10-12 用户连着两轮反馈「太大」后收成现在这个尺寸。
 * **关键的一条是高度**：原先 `.fillMaxHeight()` + `wash` 底色会在屏幕右缘拉出一条
 * 102px 宽 × 1080px 高的整条灰带 —— 那才是「大」的主体，按钮本身其实很小。
 * 竖栏只该是一小块工具面板：高度跟着按钮走（6 枚 ≈ 180dp），下缘留白还给图。
 *
 * 其余尺寸：竖栏占位 30dp（其中右留白 4dp，不贴着屏幕边）、按钮 24dp、图形 12dp、
 * 间距 4dp（都是设计常量阶梯里的值）。按钮小于 Material 的 48dp 触控标准是**有意的** ——
 * 竖栏是独立一栏（不浮在图上），图表宽度不受它影响，但多吃的每一 dp 都是从 K 线上抠的。
 */
@Composable
private fun DrawToolRail(
    tool: DrawTool,
    /** 当前标的图上有可清的线（告警线或画线）：没有就置灰这一枚。 */
    clearable: Boolean,
    /** 锁定态：新建 / 编辑 / 删除全关。按钮照样可点，点一下由调用方把原因说出来。 */
    locked: Boolean,
    onToggleLock: () -> Unit,
    /** 正在拖一根线：整栏盖成垃圾桶落点（拖到它上面松手即删）。 */
    dropActive: Boolean,
    onSelect: (DrawTool) -> Unit,
    onClear: () -> Unit,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            // padding 写在 width 之前：它先把可用宽度收窄，于是「竖栏占位」= 26 + 右留白 4
            .padding(top = Spacing.Xs, end = Spacing.Xxs)
            .width(26.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(Radius.fullShape)
                .background(colors.wash)
                .padding(vertical = Spacing.Xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Xxs),
        ) {
            DrawToolButton("锁定/解锁划线编辑", locked, onToggleLock) { color ->
                LockGlyph(color, locked)
            }
            RailDivider(colors.hairline)
            DrawToolButton(
                label = "预警线",
                selected = tool == DrawTool.ALERT,
                onClick = { onSelect(DrawTool.ALERT) },
                dimmed = locked,
            ) { color ->
                DashedLineGlyph(color)
            }
            DrawToolButton(
                label = "水平线",
                selected = tool == DrawTool.HORIZONTAL,
                onClick = { onSelect(DrawTool.HORIZONTAL) },
                dimmed = locked,
            ) { color ->
                HorizontalLineGlyph(color)
            }
            DrawToolButton(
                label = "直线",
                selected = tool == DrawTool.TREND,
                onClick = { onSelect(DrawTool.TREND) },
                dimmed = locked,
            ) { color ->
                DiagonalLineGlyph(color)
            }
            // 画 / 撤 是两类动作：不隔开的话，六枚圆钮看着像同一个集合里的六种线型
            RailDivider(colors.hairline)
            DrawToolButton(
                label = "删除",
                selected = tool == DrawTool.ERASE,
                onClick = { onSelect(DrawTool.ERASE) },
                dimmed = locked,
            ) { color ->
                TrashGlyph(color)
            }
            DrawToolButton(
                label = "清空全部划线",
                selected = false,
                // 空了就真禁用（按了没反应）；锁定态另说 —— 那时让它可点，
                // 点一下由调用方解释「先解锁」，比一个哑按钮有用
                enabled = clearable || locked,
                dimmed = locked,
                onClick = onClear,
            ) { color ->
                ClearAllGlyph(color)
            }
        }
        if (dropActive) {
            // 拖线中：整栏盖成一只垃圾桶。
            //
            // 盖在**同一块几何**上而不是替换内容 —— 拖动途中目标不能自己动，
            // 尺寸与位置必须与松手前逐像素一致，否则「看见落点、拖过去」会扑空。
            // 这层没有 pointerInput，不吃指针事件；再说这一场手势的指针本来就是
            // 图那边按下时认领的，事件也不经过这里。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(Radius.fullShape)
                    .background(colors.down),
                contentAlignment = Alignment.Center,
            ) {
                TrashGlyph(colors.paper, glyphSize = RAIL_BUTTON * 0.62f)
            }
        }
    }
}

/** 竖栏里表示「这一组是另一类动作」的细分隔线。 */
@Composable
private fun RailDivider(color: Color) {
    Box(modifier = Modifier.width(12.dp).height(1.dp).background(color))
}

@Composable
private fun DrawToolButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** 看着置灰但**仍可点**（锁定态）：点一下由调用方说出为什么不能动。 */
    dimmed: Boolean = false,
    glyph: @Composable (Color) -> Unit,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .size(RAIL_BUTTON)
            .clip(Radius.fullShape)
            .background(if (selected) colors.ink else Color.Transparent)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 选中态整枚反色：底色换成 ink 后图形得改走 paper，否则深底压深线看不见。
        // 未选中用 muted（与 chip 的未选中文字同档）—— 原先用 ink 时三根纯黑粗条
        // 是全屏最重的墨迹，比图还显眼。
        //
        // 两档「看着不能用」是两回事：
        // · [enabled] = false 是真禁用（按了没反应），只留给确实没有对象的动作（清空）；
        // · [dimmed] = true 是**看着灰但仍可点** —— 用于锁定态：点一下要能说出原因，
        //   而 App 上没有 hover，静默的禁用按钮说不出话。
        glyph(
            when {
                selected -> colors.paper
                dimmed || !enabled -> colors.hairlineStrong
                else -> colors.muted
            },
        )
    }
}

/** 预警线工具：一条虚线（与图上告警线同形态）。 */
@Composable
private fun DashedLineGlyph(color: Color) {
    Canvas(Modifier.size(RAIL_GLYPH)) {
        val stroke = size.width * 0.11f
        val y = size.height / 2f
        val dash = size.width / 7f
        var x = stroke / 2f
        while (x < size.width - dash) {
            drawLine(color, Offset(x, y), Offset(x + dash * 3f, y), stroke, cap = StrokeCap.Round)
            x += dash * 4f
        }
    }
}

/** 水平线工具：一条实线。 */
@Composable
private fun HorizontalLineGlyph(color: Color) {
    Canvas(Modifier.size(RAIL_GLYPH)) {
        val stroke = size.width * 0.11f
        drawLine(
            color,
            Offset(stroke / 2f, size.height / 2f),
            Offset(size.width - stroke / 2f, size.height / 2f),
            stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 直线工具：一条上扬斜线，两端点各加一个圆点（两敲落锚的示意）。 */
@Composable
private fun DiagonalLineGlyph(color: Color) {
    Canvas(Modifier.size(RAIL_GLYPH)) {
        val stroke = size.width * 0.11f
        val i = stroke
        val from = Offset(i, size.height - i)
        val to = Offset(size.width - i, i)
        drawLine(color, from, to, stroke, cap = StrokeCap.Round)
        drawCircle(color, radius = stroke * 0.9f, center = from)
        drawCircle(color, radius = stroke * 0.9f, center = to)
    }
}

/**
 * 锁：竖栏最上面那枚，管的是**下面整栏**（锁定 = 整张图只读）。
 *
 * 状态要两处一起说：按钮底色（锁定时整枚反色，见 [DrawToolButton] 的 selected）
 * 与**锁梁的位置** —— 12dp 的画布上塞不下第二个字，形状就是唯一的第二句话。
 * 锁梁的两条腿插进锁体 = 已锁；整条被拔出来、腿与锁体之间留出一道空隙 = 已解锁。
 *
 * 只靠那 1dp 多的空隙说状态，看着悬，其实够用：这枚按钮本身就是反色的强弱对比，
 * 空隙只是给「反色=锁」这件事一个可以指向的形状（不然反色的锁和反色的线型没区别）。
 */
@Composable
private fun LockGlyph(color: Color, locked: Boolean) {
    Canvas(Modifier.size(RAIL_GLYPH)) {
        val stroke = size.width * 0.11f
        val w = size.width
        val h = size.height
        val cap = StrokeCap.Round
        val bodyTop = h * 0.50f
        // 锁体：上宽下窄的小梯形，与「删除」那枚同一套轮廓语言（矩形在这个尺寸下像个方块）
        drawLine(color, Offset(w * 0.16f, bodyTop), Offset(w * 0.84f, bodyTop), stroke, cap = cap)
        drawLine(color, Offset(w * 0.16f, bodyTop), Offset(w * 0.24f, h * 0.92f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.84f, bodyTop), Offset(w * 0.76f, h * 0.92f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.24f, h * 0.92f), Offset(w * 0.76f, h * 0.92f), stroke, cap = cap)
        // 锁梁：方拱（两腿 + 顶），不用圆拱 —— 圆拱得引 drawArc/Size，
        // 且在这个尺寸下与另外几枚的直线语言不一致
        val legBottom = if (locked) h * 0.54f else h * 0.38f
        drawLine(color, Offset(w * 0.32f, legBottom), Offset(w * 0.32f, h * 0.20f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.32f, h * 0.20f), Offset(w * 0.68f, h * 0.20f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.68f, h * 0.20f), Offset(w * 0.68f, legBottom), stroke, cap = cap)
    }
}

/**
 * 删除工具：一只垃圾桶（删除的通用意象），与竖栏里那枚「点一下即删」的开关是同一个意思。
 *
 * 手搓而不是用 `Icons.Default.Delete`：material 的图形在 24dp 视图里只占中间一半，
 * 摆进这枚 12dp 的画布会明显比旁边三根线小一圈。这里按同一套 0.11w 线宽自己画，
 * 提到「12dp 画布铺满」的尺寸口径就与三枚线型一致了。桶身用梯形而不是矩形 ——
 * 只差两条斜边，但矩形在这个尺寸下更像一个「方块」。
 *
 * 只删**一条**：点它 → 再点图上的线。全删在下一枚 [ClearAllGlyph]。
 *
 * [glyphSize] 是给「拖线时整栏变垃圾桶」那层用的（画得比 12dp 大）。参数**不能叫 `size`**：
 * `Canvas` 作用域里的 `size` 是画布尺寸，同名参数会把它遮住 —— 报错是
 * `Function invocation 'width(...)' expected`，看着像少了扩展函数，其实是名字撞了。
 */
@Composable
private fun TrashGlyph(color: Color, glyphSize: Dp = RAIL_GLYPH) {
    Canvas(Modifier.size(glyphSize)) {
        val stroke = size.width * 0.11f
        val w = size.width
        val h = size.height
        val cap = StrokeCap.Round
        val lidY = h * 0.30f
        // 提手：桶盖上方那个小方口
        drawLine(color, Offset(w * 0.40f, lidY), Offset(w * 0.40f, h * 0.12f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.40f, h * 0.12f), Offset(w * 0.60f, h * 0.12f), stroke, cap = cap)
        drawLine(color, Offset(w * 0.60f, h * 0.12f), Offset(w * 0.60f, lidY), stroke, cap = cap)
        // 桶盖
        drawLine(color, Offset(w * 0.12f, lidY), Offset(w * 0.88f, lidY), stroke, cap = cap)
        // 桶身：上宽下窄
        val bodyTop = h * 0.42f
        val bodyBottom = h * 0.92f
        drawLine(color, Offset(w * 0.22f, bodyTop), Offset(w * 0.78f, bodyTop), stroke, cap = cap)
        drawLine(color, Offset(w * 0.22f, bodyTop), Offset(w * 0.31f, bodyBottom), stroke, cap = cap)
        drawLine(color, Offset(w * 0.78f, bodyTop), Offset(w * 0.69f, bodyBottom), stroke, cap = cap)
        drawLine(color, Offset(w * 0.31f, bodyBottom), Offset(w * 0.69f, bodyBottom), stroke, cap = cap)
    }
}

/**
 * 清空全部划线：三条被一道斜杠划掉的线。
 *
 * 为什么不用第二只垃圾桶：垃圾桶在这条竖栏里已经是「删一条」的意思（见 [TrashGlyph]），
 * 两枚同形按钮上下挨着，用户只能靠猜哪只删得多。这里画的是**内容**而不是工具 ——
 * 三条横线正是这家竖栏一直在画的那些线，一道斜杠划过整组，说的是「全没了」。
 * 形状本身把「全部」表达出来了，省掉一行字（竖栏里也放不下字）。
 *
 * 真到按下去那一刻还有 [ClearAllLinesDialog] 兜一道：图形再准也只是「大概其」，
 * 而这一下会连带删掉预警规则。
 */
@Composable
private fun ClearAllGlyph(color: Color) {
    Canvas(Modifier.size(RAIL_GLYPH)) {
        val stroke = size.width * 0.11f
        val w = size.width
        val h = size.height
        val cap = StrokeCap.Round
        // 三条线只到 0.86：斜杠要占右上与左下两个角，线铺满会和斜杠端点糊在一起
        listOf(0.20f, 0.50f, 0.80f).forEach { f ->
            drawLine(color, Offset(w * 0.08f, h * f), Offset(w * 0.86f, h * f), stroke, cap = cap)
        }
        // 斜杠：整枚图形里最有信息量的一笔，画得比三条线更满
        drawLine(color, Offset(w * 0.14f, h * 0.90f), Offset(w * 0.90f, h * 0.12f), stroke, cap = cap)
    }
}

/**
 * 全屏里的现价牌：读数带右端（右侧价格轴正上方）常驻的「现价 + 涨跌」。
 *
 * 竖屏那块 42sp 的 Hero 是全 App 最大的现价展示，全屏横屏后整块让位给了图 ——
 * 此前全屏只剩右侧轴上 8sp 的价格签能读到现价。牌子与 Hero 同源
 * （ticker 最新价，[DetailUiState.price]），价位随涨跌着色，用的也是 Hero 那套色规。
 * 字号受读数带高度约束（46~58dp）只能收到 22sp；且不跟着十字光标走 ——
 * 按住长按时图该读的是那一根 K 线，现价是另一件事。
 */
@Composable
private fun LastPriceBoard(state: DetailUiState) {
    if (state.price == PriceFormatter.NO_DATA) return
    val colors = MarketTheme.colors
    val priceColor by animateColorAsState(
        targetValue = colors.forChange(state.changePercent),
        animationSpec = tween(Motion.BaseMs),
        label = "lastPriceBoardColor",
    )
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(
                text = "现价",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(Spacing.Xxs))
            Text(
                text = state.price,
                modifier = Modifier.alignByBaseline(),
                style = LastPriceBoardPriceStyle,
                color = priceColor,
                maxLines = 1,
            )
        }
        ChangeText(changePercent = state.changePercent, fontSize = 12.sp)
    }
}

/**
 * 现价牌的价格字：Hero 的缩水版，读数带只有 46~58dp 高，用 Hero 字号会压到蜡烛。
 * 行高压到 24sp —— 价格全是数字（无升部降部），24 的行盒足够，
 * 也把整个牌子（价格 + 涨跌）压在读数带里。
 */
private val LastPriceBoardPriceStyle = HeroPriceStyle.copy(
    fontSize = 22.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.8).sp,
)

/**
 * 顶栏：返回 + 币种名 + 自选 + 指标划线 + 预警铃铛。
 *
 * 动作直接摊开而不收进「三个点」：菜单里只有一两项，多点一次只为展开一个空菜单，
 * 而且那几项（预警、划线）恰恰是详情页的主行动之一，藏起来等于没人用。
 *
 * 铃铛打开本标的价格预警弹层（先看待有的，右上角再添新的）：预警页已不提供创建入口，
 * 「给这个标的加个预警」就地完成，标的上下文也不用重新选。
 * 划线图标打开独立的指标划线弹层：预警是「会响的规则」、划线是「图上跟着指标走的线」，
 * 两件事各管各的入口，不混在一张列表里。
 *
 * 「全屏」不在这里——它在图表左上角的角标上（见 [ChartArea]）：入口离图表越近越顺手，
 * 而详情页要滚动才能看到图表，按钮钉在顶栏等于每次先得把页面翻回去。
 * 「重新加载 K 线」也一并去掉：K 线本来就在实时刷新，手动重载只在取数失败时
 * 由空状态的「重新加载」按钮提供，日常没有第二个入口。
 */
@Composable
private fun DetailTopBar(
    state: DetailUiState,
    onBack: () -> Unit,
    onToggleWatch: () -> Unit,
    onShowAlerts: () -> Unit,
    onShowLines: () -> Unit,
) {
    val colors = MarketTheme.colors
    AppBar(
        title = state.title,
        subtitle = "${state.id.symbol} · ${state.id.market.label}",
        onBack = onBack,
        large = false,
        actions = {
            IconButton(onClick = onToggleWatch, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (state.watched) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (state.watched) "取消自选" else "加为自选",
                    modifier = Modifier.size(20.dp),
                    tint = if (state.watched) colors.accent else colors.muted,
                )
            }
            IconButton(
                onClick = onShowLines,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "管理该标的的指标划线" },
            ) {
                IndicatorLineGlyph(modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onShowAlerts, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "查看该标的的预警",
                    modifier = Modifier.size(20.dp),
                    tint = colors.ink,
                )
            }
        },
    )
}

/**
 * Hero 价格区：大字号价格 + 涨跌 + 24h 高低区间条。
 *
 * 这是详情页最重要的改动——价格是全屏最大的信息，必须占据最大的视觉权重。
 * 数字用负字距的等宽体，颜色随涨跌在绿/红之间平滑过渡。
 */
@Composable
private fun Hero(state: DetailUiState) {
    val colors = MarketTheme.colors
    val priceColor by animateColorAsState(
        targetValue = colors.forChange(state.changePercent),
        animationSpec = tween(Motion.BaseMs),
        label = "heroPriceColor",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        Text(
            text = state.price,
            style = HeroPriceStyle,
            color = priceColor,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.Xxs))
        ChangeText(
            changePercent = state.changePercent,
            showArrow = true,
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
        )

        val low = state.low24h
        val high = state.high24h
        val current = state.lastPrice
        if (low != null && high != null && current != null && high > low) {
            Spacer(Modifier.height(Spacing.Md))
            RangeBar(low = low, high = high, current = current)
            Spacer(Modifier.height(Spacing.Xs))
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricCell(
                    label = "24H 最低",
                    value = formatPriceFor(low, state),
                    valueStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "24H 区间",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                Spacer(Modifier.weight(1f))
                MetricCell(
                    label = "24H 最高",
                    value = formatPriceFor(high, state),
                    align = Alignment.End,
                    valueStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 用与主价格相同的精度格式化高低价。
 * 两者是不同数值，但精度规则一致，所以按同一个 tickSize 走 PriceFormatter。
 */
private fun formatPriceFor(value: Double, state: DetailUiState): String =
    PriceFormatter.format(BigDecimal(value.toString()), state.tickSize)

/** 横排控件带里的行内小标题：代替 [SectionOverline]，省掉一整行高度。 */
@Composable
private fun InlineLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MarketTheme.colors.muted,
        maxLines = 1,
    )
}

/**
 * 周期选择：横向可滚的方块标签。选中态用反色块，与图表区的指标条语言统一。
 * 行尾的「＋」打开周期管理对话框：自定义周期、删掉不看的、拖动排序。
 */
@Composable
private fun IntervalSelector(
    options: List<CandleInterval>,
    selected: CandleInterval,
    onSelect: (CandleInterval) -> Unit,
    onManage: () -> Unit,
    compact: Boolean = false,
    /**
     * 仅 compact 模式生效：钉在这一行**右侧**的固定控件（全屏里的「划线 / 退出」）。
     * 周期 chips 在左侧横向滚动，actions 不参与滚动、始终常驻可见——退出入口不能被滚走。
     */
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 垂直留白收到 4dp：横屏只有 540dp 高，周期 + 指标两条窄带若照竖屏的 12dp 走，
                // 这两行就要吃掉 92dp（≈17% 屏高）。chip 本体仍是 22dp，点击区不受影响。
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：周期可横滚，占满剩余宽度
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineLabel(text = "周期")
                options.forEach { option ->
                    FilterChip(
                        text = option.label,
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        compact = true,
                    )
                }
                FilterChip(text = "＋", selected = false, onClick = onManage, compact = true)
            }
            // 右侧：固定动作区（不随周期滚动）
            actions?.invoke(this)
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.Md)) {
        SectionOverline(text = "周期")
        Spacer(Modifier.height(Spacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            options.forEach { option ->
                FilterChip(
                    text = option.label,
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    compact = true,
                )
            }
            FilterChip(text = "＋", selected = false, onClick = onManage, compact = true)
        }
    }
}

/**
 * K 线详情小条：画进图表顶部、指标读数带的**正上方**（同一个容器，见 KlineChart 的
 * candleReadout 槽），替代从前浮在图上的十字光标弹窗。
 *
 * 弹窗那一大块常年压着左上那片蜡烛与形态；改成小条后图整块干净。
 * 常显而不是长按才出现：小条占的是读数带上方固定预留的一截，
 * 出现/消失不会把图上下顶一格。没长按就读最新一根，与读数带同语义、上下对读。
 * 字号与图上读数带同档（9sp 等宽）—— 同一套数字分两处出现，字号必须一致。
 * 竖排成四列而不是两行横排：横排时每格宽度随数字位数伸缩，上下两行的标签互相错位，
 * 「时间」这类长值还会把后面的格子整体挤歪。按列堆叠后列宽取两格内容的较宽者，
 * 上下天然对齐（开/收、高/量各归一列），行分组语义不变。
 * 水平起点与左内边距都不在这里挂：读数带容器已经给了左起点与行间隔。
 */
@Composable
private fun CandleReadoutRow(state: DetailUiState, index: Int?) {
    val colors = MarketTheme.colors
    val tip = remember(state.candles, index, state.interval, state.tickSize) {
        ChartModel.tooltip(state.candles, index ?: state.candles.lastIndex, state.interval, state.tickSize)
    } ?: return
    val changeColor = if (tip.up) colors.upSoft else colors.downSoft
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = 9.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        // 较上根 +x.xx%：前缀已在文案里，不再挂标签
        ReadoutStack {
            ReadoutCell("时间", tip.time, style = style)
            ReadoutCell(null, tip.changeText, valueColor = changeColor, style = style)
        }
        ReadoutStack {
            ReadoutCell("开", tip.open, style = style)
            ReadoutCell("收", tip.close, valueColor = changeColor, style = style)
        }
        ReadoutStack {
            ReadoutCell("高", tip.high, style = style)
            ReadoutCell("量", tip.volume, style = style)
        }
        ReadoutStack {
            ReadoutCell("低", tip.low, style = style)
        }
    }
}

/** 详情小条的一列：上下两格共享列宽（取较宽者），保证跨行竖直对齐。 */
@Composable
private fun ReadoutStack(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** 小条里的一格：标签浅色、数值墨色（涨跌格把数值色传进来）。 */
@Composable
private fun ReadoutCell(label: String?, value: String, valueColor: Color? = null, style: TextStyle) {
    val colors = MarketTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
        if (label != null) {
            Text(
                text = label,
                style = style,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            style = style,
            color = valueColor ?: colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * 图表区：主图高度按屏高自适应（屏高的 40%，钳制在 260–400dp），
 * 每开一块副图就在下方追加一整块 [SUB_PANE_HEIGHT_DP] ——
 * 主图与已有的副图都不随块数被挤薄，多开只是把整张图拉长、页面往下滚着看。
 *
 * 固定 360dp 的问题是：在 6.1 寸以下的机器上，图表 + 统计区会超出一屏，
 * 用户必须滚动才能看到 24h 量能；而在折叠屏展开后 360dp 又显得矮。
 */
@Composable
private fun ChartArea(
    state: DetailUiState,
    alertLines: List<AlertPriceLine>,
    indicatorGuides: List<IndicatorGuideLine>,
    bandGuides: List<BandGuideLine>,
    drawLines: List<DrawGuideLine>,
    alertLinesVisible: Boolean,
    onToggleAlertLines: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onFullscreen: () -> Unit,
    onCrosshairIndexChange: (Int?) -> Unit,
    initialView: ChartViewState,
    onViewChange: (ChartViewState) -> Unit,
    candleReadout: (@Composable () -> Unit)? = null,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight(state.subPaneCount))
            .padding(top = Spacing.Sm),
    ) {
        when {
            // 加载中：**空图表 + 遮罩层**，而不是「白底上一个转圈」——
            // 网格与轴先就位，数据到了是把蜡烛填进去，不是换一张图
            state.loadingCandles && state.candles.isEmpty() -> ChartLoading(state)

            state.candles.isEmpty() -> {
                HintRow(
                    title = "没有取到 K 线",
                    subtitle = state.error ?: "换个周期或重新加载试试",
                    actionLabel = "重新加载 →",
                    onAction = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                // 自研 Compose 画布而非 WebView：不依赖系统 WebView 与 GL 合成，
                // 模拟器/低端机上同样能画出来，也少一套 HTML/JS 资源要维护。
                val series = remember(state.candles, state.maPeriods, state.showBoll, state.subPanes, state.openInterest) {
                    ChartModel.build(
                        candles = state.candles,
                        maPeriods = state.maPeriods,
                        showBoll = state.showBoll,
                        subPanes = state.subPanes,
                        openInterest = state.openInterest,
                    )
                }
                KlineChart(
                    series = series,
                    interval = state.interval,
                    tickSize = state.tickSize,
                    symbolKey = state.id.storageKey,
                    initialView = initialView,
                    onViewChange = onViewChange,
                    onLoadMore = onLoadMore,
                    onCrosshairIndexChange = onCrosshairIndexChange,
                    candleReadout = candleReadout,
                    // 只画不能拖：竖屏没有划线模式，调整预警走全屏
                    alertLines = alertLines,
                    indicatorGuides = indicatorGuides,
                    bandGuides = bandGuides,
                    // 视觉画线是持久化的，竖屏同样显示（改/删仍在全屏竖栏里做）
                    drawLines = drawLines,
                    alertLinesVisible = alertLinesVisible,
                    onToggleAlertLines = onToggleAlertLines,
                    cornerAction = ChartCornerAction(
                        description = "全屏看图",
                        content = { FullscreenGlyph() },
                        onClick = onFullscreen,
                    ),
                )
            }
        }
        if (state.loadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                strokeWidth = 2.dp,
                color = colors.muted,
            )
        }
    }
}

/**
 * 加载中的图表：一张**空图** + 一层遮罩。
 *
 * 空图与真实图共用几何（见 [ChartSkeleton]），所以它是「这一张图还没画上蜡烛」，
 * 而不是另开一张图 —— 数据到位的瞬间只有蜡烛浮现，图形本身不重排。
 * 遮罩压在空图上：既把网格压淡到不喧宾夺主，又给「正在取」一个明确的位置
 * （正中的转圈），不至于让人以为这张空图就是全部。
 */
@Composable
private fun ChartLoading(state: DetailUiState) {
    val colors = MarketTheme.colors
    Box(Modifier.fillMaxSize()) {
        ChartSkeleton(
            subCount = state.subPaneCount,
            readoutLines = state.mainReadoutLines,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 半透明而不是实底：骨架的网格要透出来，否则就退回「白底转圈」了
                .background(colors.paper.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = colors.muted,
            )
        }
    }
}

/** 图表高度：屏高 40%（钳制到 [260,400]dp）整块留给主图，每开一块副图再往下加一个副图高；
 * 主图大小与副图块数无关。 */
@Composable
private fun chartHeight(subPaneCount: Int): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    return (screenHeight * 0.4f).coerceIn(260f, 400f).dp + (subPaneCount * SUB_PANE_HEIGHT_DP).dp
}

/**
 * 指标开关条：主图叠图（MA / BOLL）与副图选择各占一行。
 *
 * MA 与副图**都允许全不选**：MA 全关 = 裸 K 图，副图全关 = 只留主图。
 * 这是看图的基本需求，不做「至少留一个」的兜底。
 *
 * [compact] 给全屏横屏用：两行式（两条小标题 + 两行方块）在竖屏里很舒展，
 * 到横屏会吃掉近 40% 屏高、把蜡烛区整个压扁。横排把分组小标题降级成行内标签，
 * 收成一条常驻的窄带（见 [FullscreenChart]），与竖屏「图表下方常驻」的语义一致。
 */
@Composable
private fun IndicatorBar(
    /** 指标条上摊开的均线档位（用户可在「＋」里增删，见 [MaManagerDialog]）。 */
    maCatalog: List<Int>,
    activeMa: List<Int>,
    showBoll: Boolean,
    subPanes: List<SubPaneKind>,
    /** OI chip 的可用性：当前合约接口提供持仓量历史才亮，否则置灰（点了给原因）。 */
    oiSupported: Boolean,
    onToggleMa: (Int) -> Unit,
    /** 打开「均线周期」管理弹窗（增删目录、恢复默认），与周期条的「＋」同一定位。 */
    onManageMa: () -> Unit,
    onToggleBoll: () -> Unit,
    onToggleSubPane: (SubPaneKind) -> Unit,
    compact: Boolean = false,
    /**
     * 是否显示「副图」那一组 chip。全屏固定条传 false：全屏不画副图（见 [FullscreenChart]），
     * 留着能点却不生效的开关只会让人以为坏了。竖屏照常两组都出。
     */
    showSubPanes: Boolean = true,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // 与 [IntervalSelector] 的 compact 分支同一套收紧后的留白（见那里的说明）
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineLabel(text = "指标")
            maCatalog.forEach { period ->
                FilterChip(
                    text = "MA$period",
                    selected = period in activeMa,
                    onClick = { onToggleMa(period) },
                    compact = true,
                )
            }
            FilterChip(text = "＋", selected = false, onClick = onManageMa, compact = true)
            FilterChip(text = "BOLL", selected = showBoll, onClick = onToggleBoll, compact = true)
            if (showSubPanes) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.Xxs)
                        .width(1.dp)
                        .height(14.dp)
                        .background(MarketTheme.colors.hairline),
                )
                InlineLabel(text = "副图")
                SubPaneKind.entries.forEach { kind ->
                    FilterChip(
                        text = kind.label,
                        selected = kind in subPanes,
                        greyed = kind == SubPaneKind.OI && !oiSupported,
                        onClick = { onToggleSubPane(kind) },
                        compact = true,
                    )
                }
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.Md, bottom = Spacing.Md)) {
        SectionOverline(text = "指标")
        Spacer(Modifier.height(Spacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            maCatalog.forEach { period ->
                FilterChip(
                    text = "MA$period",
                    selected = period in activeMa,
                    onClick = { onToggleMa(period) },
                    compact = true,
                )
            }
            FilterChip(text = "＋", selected = false, onClick = onManageMa, compact = true)
            FilterChip(text = "BOLL", selected = showBoll, onClick = onToggleBoll, compact = true)
        }

        Spacer(Modifier.height(Spacing.Md))
        SectionOverline(text = "副图 · 可多选")
        Spacer(Modifier.height(Spacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            SubPaneKind.entries.forEach { kind ->
                FilterChip(
                    text = kind.label,
                    selected = kind in subPanes,
                    greyed = kind == SubPaneKind.OI && !oiSupported,
                    onClick = { onToggleSubPane(kind) },
                    compact = true,
                )
            }
        }
    }
}

/** 拖拽排序的行高：位移按它换算成交换次数，行必须定高才对得上。 */
private val DRAG_ROW_HEIGHT = 44.dp

/**
 * 自定义周期的可选单位：按固定折算换成分钟数，再走 [CandleInterval.custom] 校验。
 * 月按 30 天算——K 线聚合只有「分钟」这一个基准，没有日历月可言。
 */
private enum class CustomIntervalUnit(val label: String, val minutes: Long) {
    Minute("分", 1L),
    Hour("时", 60L),
    Day("天", 60L * 24),
    Week("周", 60L * 24 * 7),
    Month("月", 60L * 24 * 30),
}

/**
 * 周期条管理对话框：在草稿里增删排序，点「保存」才回写 ViewModel 并持久化。
 *
 * - 排序是整行长按拖动：手柄图标占一列不值当，长按起步也天然避开了外层滚动与
 *   行的点击；拖动的 pointerInput 消费纵向事件，列表超过一屏要先滚到位再拖。
 *   被拖行悬浮跟手，被它越过的行动画平移让出空位，松手才真正换位——
 *   拖动途中就改列表会有「被吸走」的观感。
 * - **当前选中的周期不可删**：删掉它会让「正在看的周期」从周期条上消失，图上显示
 *   的周期与条上的方块对不上，比多留一个方块更迷惑。
 * - 添加区在列表上方，只有「数字 + 单位」一条路：折成官方分钟数就加回官方周期，
 *   其余由官方周期聚合而来（[CandleInterval.custom] 负责范围与整除校验）。
 */
@Composable
private fun IntervalManagerDialog(
    initial: List<CandleInterval>,
    selected: CandleInterval,
    onDismiss: () -> Unit,
    onSave: (List<CandleInterval>) -> Unit,
) {
    // rows 用 remember 而不是 saveable：List<CandleInterval> 不可序列化，
    // 全屏进出必旋屏，saveable 会在重建时直接抛异常。草稿丢了重开即是，代价可忽略。
    var rows by remember { mutableStateOf(initial) }
    var customMinutes by rememberSaveable { mutableStateOf("") }
    var customUnit by rememberSaveable { mutableStateOf(CustomIntervalUnit.Minute) }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MarketTheme.colors
    // 拖拽排序：行定高，位移按「拖了多少个行高」换算成交换次数
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    fun append(target: CandleInterval) {
        if (rows.any { it.storageKey == target.storageKey }) {
            hint = "${target.label} 已在周期条上"
            return
        }
        rows = rows + target
        hint = null
    }

    fun move(from: Int, to: Int) {
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun commitCustom() {
        val count = customMinutes.trim().toLongOrNull()
        val minutes = count?.let { it * customUnit.minutes }
        // 官方周期没了快捷区，正好折出官方值的输入就是加回官方周期
        val official = minutes?.let { m -> OfficialInterval.entries.firstOrNull { it.minutes == m } }
        val target = official?.let(CandleInterval::of) ?: minutes?.let(CandleInterval::custom)
        if (target == null) {
            hint = "做不出这个周期：需 1 分钟～1 个月，且能被官方周期整除"
        } else {
            append(target)
            customMinutes = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "周期条", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        },
        text = {
            Column(
                modifier = Modifier
                    // 顺序不能反：heightIn 先定住视口，verticalScroll 才能算出滚动范围。
                    // 反过来写时滚动范围恒为 0，周期加多了输入框会被裁掉。
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "添加新周期",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                // 先挑单位再填数字：数字按单位折算成分钟，官方值加回官方、聚合值走 custom 校验
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    CustomIntervalUnit.entries.forEach { unit ->
                        FilterChip(
                            text = unit.label,
                            selected = unit == customUnit,
                            onClick = { customUnit = unit },
                            compact = true,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(Radius.xsShape)
                            .background(colors.wash)
                            .padding(horizontal = Spacing.Xs, vertical = Spacing.Sm),
                    ) {
                        BasicTextField(
                            value = customMinutes,
                            onValueChange = { input -> customMinutes = input.filter { it.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.ink),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitCustom() }),
                        )
                    }
                    TextAction("添加", ::commitCustom)
                }
                hint?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = Spacing.Xs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.down,
                    )
                }

                Text(
                    text = "长按条目拖动排序",
                    modifier = Modifier.padding(top = Spacing.Md),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted.copy(alpha = 0.6f),
                )
                // 拖拽中的源槽位与当前目标槽位：被越过的行按它平移让位
                val dragRowHPx = with(LocalDensity.current) { DRAG_ROW_HEIGHT.toPx() }
                val dragFrom = draggingKey?.let { key ->
                    rows.indexOfFirst { it.storageKey == key }
                } ?: -1
                val dragTo = if (dragFrom < 0) -1 else
                    (dragFrom + (dragOffsetPx / dragRowHPx).roundToInt())
                        .coerceIn(0, rows.lastIndex)

                rows.forEachIndexed { index, option ->
                    val dragging = option.storageKey == draggingKey
                    // 让位：拖拽行划过的那几条整体挪一行高，空出目标槽；动画过渡防瞬移
                    val shiftTarget = when {
                        dragging || dragFrom < 0 -> 0f
                        dragTo > dragFrom && index > dragFrom && index <= dragTo -> -dragRowHPx
                        dragTo < dragFrom && index in dragTo until dragFrom -> dragRowHPx
                        else -> 0f
                    }
                    val shift by animateFloatAsState(
                        targetValue = shiftTarget,
                        animationSpec = tween(150),
                        label = "rowShift",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DRAG_ROW_HEIGHT)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffsetPx else shift }
                            .clip(Radius.xsShape)
                            .background(if (dragging) colors.washStrong else Color.Transparent)
                            .pointerInput(option.storageKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = option.storageKey
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = {
                                        // 松手才真正换位：拖动途中列表纹丝不动，
                                        // 免得越过一半行高的瞬间整行被「吸」到新槽位
                                        val key = draggingKey
                                        val from = rows.indexOfFirst { it.storageKey == key }
                                        if (from >= 0) {
                                            val rowH = with(density) { DRAG_ROW_HEIGHT.toPx() }
                                            val steps = (dragOffsetPx / rowH).roundToInt()
                                            if (steps != 0) {
                                                move(from, (from + steps).coerceIn(0, rows.lastIndex))
                                            }
                                        }
                                        draggingKey = null
                                        dragOffsetPx = 0f
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragOffsetPx = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    val rowH = with(density) { DRAG_ROW_HEIGHT.toPx() }
                                    val from = rows.indexOfFirst { it.storageKey == option.storageKey }
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    // 钳在可移动范围内：首行拖不出上边界，尾行拖不出下边界
                                    dragOffsetPx = (dragOffsetPx + amount.y)
                                        .coerceIn(-from * rowH, (rows.lastIndex - from) * rowH)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (option == selected) colors.ink else colors.muted,
                        )
                        IconButton(
                            onClick = { rows = rows - option },
                            enabled = rows.size > 1 && option != selected,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "移除 ${option.label}",
                                modifier = Modifier.size(16.dp),
                                tint = colors.muted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextAction("保存", onClick = { onSave(rows) }) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}

/**
 * 均线目录管理对话框：指标条上有哪些 MA chip，整份在这里定。
 *
 * 出厂那五档（MA5/10/20/30/60）**不是特殊档位** —— 它们只是这份目录的初始值，一样可以删。
 * 2026-10-12 之前不是这样：五档写死在指标条上、「＋」里只能加自定义档，于是想只看 MA20
 * 就得留着另外四条不点。现在列表就是条上的全部 chip，行尾的 × 把它从条上移除，
 * 输入框再加回来，底部「恢复默认」一键回到出厂五档。
 * 与桌面端 `MaPanel` 同口径：这里只管「有哪些档位」，选不选仍照旧在指标条上点。
 *
 * 增删先在草稿里改，点「保存」才回写 ViewModel 并持久化 —— 与 [IntervalManagerDialog]
 * 同一套交互，删掉的目录项会连带从选中集移除（见 VM）：目录没了 chip 还画着，
 * 那条线在图上就撤不掉了。
 */
@Composable
private fun MaManagerDialog(
    catalog: List<Int>,
    onDismiss: () -> Unit,
    onSave: (List<Int>) -> Unit,
) {
    // rows 用 remember 而不是 saveable：草稿丢了重开即是（同周期条管理）
    var rows by remember { mutableStateOf(catalog) }
    var input by rememberSaveable { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MarketTheme.colors

    fun commitAdd() {
        val period = input.trim().toIntOrNull()
        when {
            period == null || period !in MA_PERIOD_MIN..MA_PERIOD_MAX ->
                hint = "填 $MA_PERIOD_MIN～$MA_PERIOD_MAX 的整数"
            period in rows -> hint = "MA$period 已在指标条上"
            rows.size >= MA_LIMIT -> hint = "最多 $MA_LIMIT 条均线，先删一条再加"
            else -> {
                rows = (rows + period).sorted()
                input = ""
                hint = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "均线周期", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        },
        text = {
            Column(
                modifier = Modifier
                    // heightIn 先定视口再挂滚动（同周期条管理，反了输入框会被裁）
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "指标条上有哪几档均线都在这里定（按期数升序排）；行尾的 × 把它从条上移除，" +
                        "点选仍照旧在条上点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                Text(
                    text = "添加均线",
                    modifier = Modifier.padding(top = Spacing.Md),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(Radius.xsShape)
                            .background(colors.wash)
                            .padding(horizontal = Spacing.Xs, vertical = Spacing.Sm),
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { raw -> input = raw.filter { it.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.ink),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitAdd() }),
                        )
                    }
                    TextAction("添加", ::commitAdd)
                }
                hint?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = Spacing.Xs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.down,
                    )
                }
                Text(
                    text = "指标条上的均线",
                    modifier = Modifier.padding(top = Spacing.Md),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                if (rows.isEmpty()) {
                    Text(
                        text = "一条都没有了，指标条上只剩「＋」",
                        modifier = Modifier.padding(top = Spacing.Sm),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted.copy(alpha = 0.6f),
                    )
                }
                rows.forEach { period ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "MA$period",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.ink,
                        )
                        IconButton(
                            onClick = { rows = rows - period },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "从指标条移除 MA$period",
                                modifier = Modifier.size(16.dp),
                                tint = colors.muted,
                            )
                        }
                    }
                }
                // 恢复默认摆在最下面：它是「推翻当前草稿」的兜底，不该挡在增删这条主路径上。
                // 同样只改草稿，仍要点「保存」才作数（与增删一致，不然一半即时一半不即时）
                TextAction(
                    text = "恢复默认（${DEFAULT_MA_CATALOG.joinToString("/") { "MA$it" }}）",
                    onClick = {
                        rows = DEFAULT_MA_CATALOG
                        hint = null
                    },
                    modifier = Modifier.padding(top = Spacing.Sm),
                    color = colors.muted,
                )
            }
        },
        confirmButton = { TextAction("保存", onClick = { onSave(rows) }) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}

/**
 * 统计区：单列「标签 —— 值」的长表，而不是 2×3 的网格。
 *
 * 网格在 6 项时最后一行会只剩一格，视觉上不平衡；而长表天然对齐、
 * 各行的值左边缘一致，扫读一列数值时更顺。极简风也更接受这种「表格」形态。
 */
@Composable
private fun StatsSection(stats: List<StatItem>) {
    if (stats.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Spacing.Lg))
        SectionOverline(text = "24H 概览")
        Spacer(Modifier.height(Spacing.Xs))
        // chunked(2) 分成左右两列，但保持成对排列（高低/量额各自成对，语义相关）
        stats.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
            ) {
                pair.forEachIndexed { index, item ->
                    MetricCell(
                        label = item.label,
                        value = item.value,
                        modifier = Modifier.weight(1f),
                        align = if (index == 0) Alignment.Start else Alignment.End,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 「指标划线」角标：一条上扬的折线。
 *
 * 自己画而不用 `Icons.Default.ShowChart` —— 那个矢量在 material-icons-extended 里，
 * 为一个图标拖进上千个图标不划算，本应用只依赖 icons-core（同 [FullscreenGlyph]）。
 */
@Composable
private fun IndicatorLineGlyph(modifier: Modifier = Modifier) {
    val colors = MarketTheme.colors
    Canvas(modifier.size(20.dp)) {
        val stroke = size.width * 0.09f
        // 线以端点为中心，不内缩的话描边会被画布切掉一半
        val i = stroke / 2f
        val points = listOf(
            Offset(i, size.height * 0.80f),
            Offset(size.width * 0.36f, size.height * 0.40f),
            Offset(size.width * 0.58f, size.height * 0.62f),
            Offset(size.width - i, i),
        )
        for ((from, to) in points.zipWithNext()) {
            drawLine(colors.ink, from, to, stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * 「全屏」角标：四向外翻的直角边框。
 *
 * 自己画而不用 `Icons.Default.Fullscreen` —— 那个矢量在 material-icons-extended 里，
 * 为一个图标拖进上千个图标不划算，本应用只依赖 icons-core。
 */
@Composable
private fun FullscreenGlyph(modifier: Modifier = Modifier) {
    val colors = MarketTheme.colors
    Canvas(modifier.size(14.dp)) {
        val stroke = size.width * 0.09f
        // 线以端点为中心，不内缩的话描边会被画布切掉一半
        val i = stroke / 2f
        val left = i
        val top = i
        val right = size.width - i
        val bottom = size.height - i
        val arm = (right - left) * 0.3f
        val corners = listOf(
            Offset(left, top) to (Offset(left + arm, top) to Offset(left, top + arm)),
            Offset(right, top) to (Offset(right - arm, top) to Offset(right, top + arm)),
            Offset(left, bottom) to (Offset(left + arm, bottom) to Offset(left, bottom - arm)),
            Offset(right, bottom) to (Offset(right - arm, bottom) to Offset(right, bottom - arm)),
        )
        for ((origin, arms) in corners) {
            drawLine(colors.muted, origin, arms.first, stroke)
            drawLine(colors.muted, origin, arms.second, stroke)
        }
    }
}
