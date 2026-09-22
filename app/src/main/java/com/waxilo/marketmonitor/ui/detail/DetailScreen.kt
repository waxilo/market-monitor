package com.waxilo.marketmonitor.ui.detail

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.chart.AlertPriceLine
import com.waxilo.marketmonitor.ui.chart.ChartCornerAction
import com.waxilo.marketmonitor.ui.chart.ChartModel
import com.waxilo.marketmonitor.ui.chart.KlineChart
import com.waxilo.marketmonitor.ui.chart.SubPaneKind
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
    viewModel: DetailViewModel = appViewModel(key = "detail:${symbolId.storageKey}") {
        DetailViewModel(it, symbolId)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 提到这一层是因为竖屏图与全屏图都要画告警线：线由预警规则派生，在哪个模式下看都该一致
    val alertLines by viewModel.alertLines.collectAsStateWithLifecycle()
    /**
     * 用 `rememberSaveable` 而不是普通 state：全屏要横屏，旋屏会让 Activity 重建，
     * 普通 state 会连同「正在全屏」一起丢掉，用户看到的是自动退出全屏。
     */
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // 周期管理对话框：竖屏与全屏共用一个开关，挂在最外层免得两条分支各存一份
    var intervalManagerOpen by rememberSaveable { mutableStateOf(false) }

    FullscreenController(active = fullscreen)

    Box(modifier = Modifier.fillMaxSize().background(MarketTheme.colors.paper)) {
        if (fullscreen) {
            FullscreenChart(
                state = state,
                alertLines = alertLines,
                viewModel = viewModel,
                onManageIntervals = { intervalManagerOpen = true },
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
                    onCreateAlert = onCreateAlert,
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
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::refresh,
                    onFullscreen = { fullscreen = true },
                )

                IndicatorBar(
                    maChoices = state.maChoices,
                    activeMa = state.maPeriods,
                    showBoll = state.showBoll,
                    subPanes = state.subPanes,
                    onToggleMa = viewModel::toggleMaPeriod,
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
 * 全屏图表：整屏只有图，顶部一条窄操作栏。
 *
 * 「划线」不需要长按 —— 在这个模式下划线就是唯一目的，再要求长按只是多余一步；
 * 单指拖动直接移动告警线，松手即按线相对现价的位置落一条上破/下破预警，双指缩放照旧可用。
 * 已有的告警线本来就画在图上，划线模式下按到它（容差 24dp）就是**改它对应的预警**，
 * 而不是在它旁边又叠一条 —— 历史上设置的告警因此都能在这里直接调整。
 *
 * 换周期与改指标也在这里给到：全屏是横屏，竖屏那套控件（周期条、指标条）整块被图表顶掉了，
 * 只剩一个「退出全屏」的话，用户每次想换个周期都得先退出、改完、再进来。
 * 周期是**常驻摊开**的一条（放在图表下方，不压时间轴）——换周期是全屏里最高频的动作，
 * 每次都要先展开再选、选完又收起的话，连换两个周期就得点三次。
 * 指标仍旧做成**按需展开的底部浮层**：它选项多、改得少，常驻会把蜡烛区压掉一大截。
 */
@Composable
private fun FullscreenChart(
    state: DetailUiState,
    alertLines: List<AlertPriceLine>,
    viewModel: DetailViewModel,
    onManageIntervals: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MarketTheme.colors
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    // 挂在全屏内部：退出全屏再进来就该回到普通看图态，不该还停在划线模式
    var alertMode by rememberSaveable { mutableStateOf(false) }
    // 同样挂在全屏内部：面板只是「临时看一眼」，退出全屏没必要带着走
    var indicatorOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        // 顶端这一行同时承载「周期」与「指标 / 划线 / 退出」：横屏时底部要留给时间轴，
        // 把这些开关浮在图表上会盖住 K 线；并入周期行后图表整块干净，且和竖屏「周期在图表上方」一致。
        // 周期在左侧可横滚，右侧三个动作固定常驻（退出入口不能被滚走）。
        IntervalSelector(
            options = state.intervals,
            selected = state.interval,
            onSelect = viewModel::selectInterval,
            onManage = onManageIntervals,
            compact = true,
            actions = {
                FilterChip(
                    text = "指标",
                    selected = indicatorOpen,
                    onClick = { indicatorOpen = !indicatorOpen },
                    compact = true,
                )
                FilterChip(
                    text = "划线",
                    selected = alertMode,
                    onClick = { alertMode = !alertMode },
                    compact = true,
                )
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
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.candles.isEmpty()) {
                HintRow(
                    title = "没有取到 K 线",
                    subtitle = state.error ?: "换个周期或重新加载试试",
                    actionLabel = "重新加载 →",
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val series = remember(state.candles, state.maPeriods, state.showBoll, state.subPanes) {
                    ChartModel.build(
                        candles = state.candles,
                        maPeriods = state.maPeriods,
                        showBoll = state.showBoll,
                        subPanes = state.subPanes,
                    )
                }
                KlineChart(
                    series = series,
                    interval = state.interval,
                    tickSize = state.tickSize,
                    symbolKey = state.id.storageKey,
                    onLoadMore = viewModel::loadMore,
                    alertLines = alertLines,
                    alertLineMode = alertMode,
                    onAlertLineDrag = viewModel::dragAlertLine,
                    onAlertLineCommit = { viewModel.commitAlertLine() },
                    onAlertLineDelete = viewModel::deleteAlertLine,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 指标浮层压在图表底部而不是顶部：顶部要留给指标读数带，
            // 底部只有时间轴，被临时盖住不影响看形态。
            if (indicatorOpen) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colors.paper)
                        .padding(bottom = Spacing.Sm),
                ) {
                    Rule(inset = 0.dp)
                    IndicatorBar(
                        maChoices = state.maChoices,
                        activeMa = state.maPeriods,
                        showBoll = state.showBoll,
                        subPanes = state.subPanes,
                        onToggleMa = viewModel::toggleMaPeriod,
                        onToggleBoll = viewModel::toggleBoll,
                        onToggleSubPane = viewModel::toggleSubPane,
                        compact = true,
                    )
                }
            }

            if (alertMode) {
                Text(
                    text = "上下拖动放置告警线 · 按住已有的线可直接改价位 · 拖到右上角垃圾桶删除 · 松手自动判定上破/下破",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Spacing.Lg)
                        .clip(Radius.fullShape)
                        .background(colors.washStrong)
                        .padding(horizontal = Spacing.Sm, vertical = Spacing.Xxs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                )
            }

            // 提示浮在底部：顶部是指标读数带与最新蜡烛，浮层压上去代价最大
            AnimatedBanner(
                visible = notice != null,
                text = notice.orEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.Gutter),
            )
        }
    }
}

/**
 * 顶栏：返回 + 币种名 + 自选 + 溢出菜单。
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
    onCreateAlert: () -> Unit,
) {
    val colors = MarketTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = colors.ink,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("为该交易对建预警") },
                        leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onCreateAlert()
                        },
                    )
                }
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
     * 仅 compact 模式生效：钉在这一行**右侧**的固定控件（全屏里的「指标 / 划线 / 退出」）。
     * 周期 chips 在左侧横向滚动，actions 不参与滚动、始终常驻可见——退出入口不能被滚走。
     */
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
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
 * 图表区：高度按屏高自适应（屏高的 40%，钳制在 260–380dp）。
 *
 * 固定 360dp 的问题是：在 6.1 寸以下的机器上，图表 + 统计区会超出一屏，
 * 用户必须滚动才能看到 24h 量能；而在折叠屏展开后 360dp 又显得矮。
 */
@Composable
private fun ChartArea(
    state: DetailUiState,
    alertLines: List<AlertPriceLine>,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight())
            .padding(top = Spacing.Sm),
    ) {
        when {
            state.loadingCandles && state.candles.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = colors.muted,
                    )
                }
            }

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
                val series = remember(state.candles, state.maPeriods, state.showBoll, state.subPanes) {
                    ChartModel.build(
                        candles = state.candles,
                        maPeriods = state.maPeriods,
                        showBoll = state.showBoll,
                        subPanes = state.subPanes,
                    )
                }
                KlineChart(
                    series = series,
                    interval = state.interval,
                    tickSize = state.tickSize,
                    symbolKey = state.id.storageKey,
                    onLoadMore = onLoadMore,
                    // 只画不能拖：竖屏没有划线模式，调整预警走全屏
                    alertLines = alertLines,
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

/** 图表高度：屏高 40%，钳制到 [260, 400] dp。 */
@Composable
private fun chartHeight(): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    return (screenHeight * 0.4f).coerceIn(260f, 400f).dp
}

/**
 * 指标开关条：主图叠图（MA / BOLL）与副图选择各占一行。
 *
 * MA 与副图**都允许全不选**：MA 全关 = 裸 K 图，副图全关 = 只留主图。
 * 这是看图的基本需求，不做「至少留一个」的兜底。
 *
 * [compact] 给全屏横屏用：两行式（两条小标题 + 两行方块）在竖屏里很舒展，
 * 到横屏会吃掉近 40% 屏高、把副图区整个盖住 —— 开副图却看不见副图，等于白开。
 * 横排把分组小标题降级成行内标签，只留一条窄带压住时间轴。
 */
@Composable
private fun IndicatorBar(
    maChoices: List<Int>,
    activeMa: List<Int>,
    showBoll: Boolean,
    subPanes: List<SubPaneKind>,
    onToggleMa: (Int) -> Unit,
    onToggleBoll: () -> Unit,
    onToggleSubPane: (SubPaneKind) -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineLabel(text = "叠加")
            maChoices.forEach { period ->
                FilterChip(
                    text = "MA$period",
                    selected = period in activeMa,
                    onClick = { onToggleMa(period) },
                    compact = true,
                )
            }
            FilterChip(text = "BOLL", selected = showBoll, onClick = onToggleBoll, compact = true)
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
                    onClick = { onToggleSubPane(kind) },
                    compact = true,
                )
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.Md, bottom = Spacing.Md)) {
        SectionOverline(text = "叠加指标")
        Spacer(Modifier.height(Spacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            maChoices.forEach { period ->
                FilterChip(
                    text = "MA$period",
                    selected = period in activeMa,
                    onClick = { onToggleMa(period) },
                    compact = true,
                )
            }
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
