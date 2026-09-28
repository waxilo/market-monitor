package com.waxilo.marketmonitor.ui.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AnimatedBanner
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.ChangePill
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.ListRow
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.Sparkline
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Motion
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 行情首页（PRD 4.3）：仅展示关注（自选）列表。
 *
 * 版面结构模仿杂志目录：
 * 刊头（大标题 + 市场概览）→ 粗分隔线 → 条目流（每条左边币种名、中间迷你走势、右边数字）。
 * 列表本身不轮询——ViewModel 的定时器轮询 REST 落库，Room 流每 500ms 合并一次渲染。
 *
 * 条目交互：**长按拖动排序**、**左滑露出「移除」**。
 * 旧版是长按弹菜单（移除/加入），既不直观也和排序无关，已废弃。
 */
@Composable
fun MarketScreen(
    onOpenDetail: (SymbolId) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: MarketViewModel = appViewModel { MarketViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MarketTheme.colors.paper)) {
        AppBar(
            title = "行情",
            subtitle = "自选 · ${state.quoteAsset} · ${state.rows.size} 个标的",
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "搜索交易对")
                }
            },
        )

        MarketSwitcher(
            selected = state.market,
            onSelect = viewModel::selectMarket,
            modifier = Modifier.padding(horizontal = Spacing.Gutter),
        )

        if (state.rows.isNotEmpty()) {
            MarketPulse(advancing = state.advancing, declining = state.declining)
        }

        Rule(inset = 0.dp, strong = true)

        AnimatedBanner(
            visible = state.offline,
            text = "网络中断，正在展示最近一次缓存",
        )
        state.error?.let { AnimatedBanner(visible = true, text = it, tone = BannerTone.Error) }

        PullToRefresh(
            refreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.rows.isEmpty() && state.loading -> CenteredSpinner()
                state.rows.isEmpty() -> EmptyState(onOpenSearch)
                else -> TickerList(
                    rows = state.rows,
                    market = state.market,
                    onOpenDetail = onOpenDetail,
                    onRemove = viewModel::removeWatch,
                    onMove = viewModel::moveWatch,
                )
            }
        }
    }
}

/**
 * QQ 式下拉刷新：**列表整体跟着手指下移**，顶部空出来的那一条里露出转圈，松手吸附。
 *
 * 不用 Material3 的 `PullToRefreshBox`：它只在顶部叠一个指示器，列表本身纹丝不动，
 * 手势与内容之间没有联系，看上去像「另一个控件在响应」，而不是「列表被拉下来了」。
 *
 * 三个手感要点：
 * - **阻尼**：[PullDamping] 让手指每走 1px 内容只下移半像素，越拉越沉；
 * - **跟手**：位移直接由滚动事件写成，没有中间动画，手指停在哪内容就停在哪；
 * - **吸附**：松手后用 spring 回 0 或 [RefreshThreshold]，而刷新的停留高度**就是**阈值高度，
 *   所以「拉到能刷新」与「松手后停在哪」是同一个位置，没有第二次跳动。
 *
 * 下拉量取自 `onPostScroll`：列表滚到顶后子节点吃不下的那部分才会落到这里，
 * 因此不需要自己判断「有没有到顶」，也不会和列表自身滚动抢事件。
 */
@Composable
private fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { RefreshThreshold.toPx() }
    val maxPullPx = with(density) { RefreshMaxPull.toPx() }
    /** 当前下拉位移（px，>= 0）。只在 `offset`/`layout` 的 lambda 里读，不触发重组。 */
    val pull = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    /** 吸附动画的句柄；这三个状态都只在回调里读写，不参与组合，因此写入不会引发重组。 */
    val settleJob = remember { mutableStateOf<Job?>(null) }
    /** 「已够阈值、正在等 refreshing 变 true」的那一小段窗口。 */
    val armed = remember { mutableStateOf(false) }
    val latestRefresh by rememberUpdatedState(onRefresh)

    fun settleTo(target: Float) {
        settleJob.value?.cancel()
        settleJob.value = scope.launch {
            animate(
                initialValue = pull.floatValue,
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) { value, _ -> pull.floatValue = value }
        }
    }

    // 刷新结束后收回。首次组合时 refreshing 就是 false、pull 也是 0，这里会空跑一次。
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            armed.value = false
            if (pull.floatValue > 0f) settleTo(0f)
        }
    }

    val connection = remember(thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 手指上行时先把已拉开的部分收回去，再让列表滚 —— 否则拉下来之后
                // 必须先「空滑」完这段距离列表才开始动
                if (source != NestedScrollSource.UserInput || available.y >= 0f || pull.floatValue <= 0f) {
                    return Offset.Zero
                }
                settleJob.value?.cancel()
                val used = (-available.y).coerceAtMost(pull.floatValue)
                pull.floatValue -= used
                return Offset(0f, -used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 列表到顶后剩下来的下拉量：这就是「拉出来的那一段」
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                settleJob.value?.cancel()
                val before = pull.floatValue
                pull.floatValue = (before + available.y * PullDamping).coerceAtMost(maxPullPx)
                return Offset(0f, pull.floatValue - before)
            }
        }
    }

    /**
     * 手指离开：够阈值就触发刷新并停在阈值高度，否则弹回顶部。
     *
     * 松手信号不用 `onPreFling` 拿——列表在顶部且没有惯性时那条链路并不保证走到，
     * 直接在容器上等「所有手指抬起」最可靠，且这里只观察、不消费，不影响列表滚动。
     */
    fun onGestureEnd() {
        if (pull.floatValue <= 0f) return
        if (pull.floatValue >= thresholdPx) {
            armed.value = true
            latestRefresh()
            settleTo(thresholdPx)
        } else {
            settleTo(0f)
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    onGestureEnd()
                }
            },
    ) {
        IndicatorStrip(
            pull = { pull.floatValue },
            refreshing = refreshing,
            thresholdPx = thresholdPx,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.offset { IntOffset(0, pull.floatValue.roundToInt()) }.fillMaxSize()) {
            content()
        }
    }
}

/**
 * 顶部随下拉高度长出来的指示器条：拉多少就露多少，拉过阈值就转圈。
 *
 * 高度用 [layout] 而不是 `height(x.dp)` 来写：后者要在组合期读 `pull`，
 * 每帧都会让整个内容子树跟着重组；前者只在测量期读，只有摆放被重新算。
 */
@Composable
private fun IndicatorStrip(
    pull: () -> Float,
    refreshing: Boolean,
    thresholdPx: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val height = pull().roundToInt().coerceIn(0, constraints.maxHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, height) { placeable.place(0, 0) }
            }
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.muted,
            )
        } else {
            // 未刷新时进度环随下拉填充，拉满半圈即到阈值，松开就转起来
            CircularProgressIndicator(
                progress = { (pull() / thresholdPx).coerceIn(0f, 1f) },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.muted,
                trackColor = colors.hairline,
            )
        }
    }
}

/**
 * 市场概览：涨跌家数用一根双色条 + 两个数字表达。
 *
 * 加这一行的理由——自选列表有十几个标的时，「今天整体是红还是绿」需要逐个看才知道，
 * 一根比例条 0.2 秒就能给出答案。没有自选时不显示（空列表本来就没有概览可言）。
 */
@Composable
private fun MarketPulse(advancing: Int, declining: Int) {
    val colors = MarketTheme.colors
    val total = advancing + declining
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "涨 $advancing",
                style = MaterialTheme.typography.labelMedium,
                color = colors.up,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "跌 $declining",
                style = MaterialTheme.typography.labelMedium,
                color = colors.down,
            )
        }
        Spacer(Modifier.height(Spacing.Xs))
        // 涨跌比例条：整宽按涨/跌家数分割，都没有时留一根灰线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(Radius.fullShape)
                .background(colors.hairline),
        ) {
            if (total > 0 && advancing > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(advancing.toFloat() / total)
                        .height(3.dp)
                        .background(colors.up),
                )
            }
        }
    }
}

/**
 * 市场切换胶囊（现货 / 永续合约）。列表页与搜索页共用。
 *
 * 自选、行情缓存都以市场为维度隔离，切换即整体换数据源；
 * 版面沿用「选中才给底色」的杂志风——未选中只留灰字，避免两处都抢注意力。
 */
@Composable
internal fun MarketSwitcher(
    selected: MarketType,
    onSelect: (MarketType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    Row(
        modifier = modifier.padding(vertical = Spacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        MarketType.entries.forEach { market ->
            val isSelected = market == selected
            Text(
                text = market.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) colors.paper else colors.muted,
                modifier = Modifier
                    .clip(Radius.fullShape)
                    .background(if (isSelected) colors.ink else colors.wash)
                    .clickable { onSelect(market) }
                    .padding(horizontal = Spacing.Md, vertical = 6.dp),
            )
        }
    }
}

/**
 * 自选列表。长按拖动排序与「配置周期」对话框同款手感，三段式：
 *
 * 1. **拖动中悬浮跟手**：被拖行按 [dragOffset] 直接平移，列表顺序纹丝不动——
 *    途中就换序、再靠补偿抵消基准位移的做法在和布局重排赛跑（行情流重组、
 *    布局帧交错都会让补偿差半拍），被拖行就会抖。周期对话框的结论是
 *    「拖动途中改列表会有被吸走的观感」，这里同样成立。
 * 2. **被越过的行动画让位**：按拖动量换算目标槽 [dragTo]，区间内的行以
 *    tween(150) 平移一行让出空位，松手前谁都不真正换位。
 * 3. **松手才换位 + 落库**：一次性重排 [orderOverride] 并调 [onMove]；
 *    让位行的新槽位 = 让位后的视觉位置，所以落位瞬间 shift 要 snap 归零、
 *    placement 动画要关一帧（[placementSuppressed]），否则 animateItem 会
 *    把行先弹回旧槽再滑回来，重复一遍刚播完的动画。
 *
 * 本地乐观顺序不会被 500ms 行情流打回原样：rows 的**顺序**只由自选表决定，
 * 行情推送只改内容不改序；落库回流后顺序与 override 一致，本地状态随即清空。
 */
@Composable
private fun TickerList(
    rows: List<TickerRow>,
    market: MarketType,
    onOpenDetail: (SymbolId) -> Unit,
    onRemove: (SymbolId) -> Unit,
    onMove: (SymbolId, Int) -> Unit,
) {
    val density = LocalDensity.current
    val maxSwipePx = with(density) { RemoveActionWidth.toPx() }
    val listState = rememberLazyListState()
    /** 正在被长按拖动的条目 key（同一时刻只可能有一个）。 */
    var draggingKey by remember { mutableStateOf<String?>(null) }
    /** 被左滑露出「移除」的条目 key（同一时刻只可能有一个）。 */
    var revealedKey by remember { mutableStateOf<String?>(null) }
    /**
     * 被拖行的悬浮位移（px）。放进 [mutableFloatStateOf] 而不是普通 `var`：
     * 它每帧都在变，只有包成快照状态，`Modifier.offset { }` 里的读取才会
     * 只触发重新摆放、不触发整列表重组。
     */
    val dragOffset = remember { mutableFloatStateOf(0f) }
    /** 换序落位的那一帧关掉 placement 动画：让位行已视觉就位，再播一遍会先弹回旧槽。 */
    var placementSuppressed by remember { mutableStateOf(false) }
    /** 松手换位后的本地乐观顺序；落库流回来即清空。 */
    var orderOverride by remember { mutableStateOf<List<SymbolId>?>(null) }

    /** 换位后的显示顺序：override 优先，order 之外的行（拖动中新增的自选）按原序补齐。 */
    val displayRows = remember(rows, orderOverride) {
        val order = orderOverride ?: return@remember rows
        val byId = rows.associateBy { it.id }
        order.mapNotNull { byId[it] } + rows.filter { it.id !in order.toSet() }
    }

    // 落库后的顺序从流里回来、与 override 一致 → 本地状态功成身退
    LaunchedEffect(rows) {
        val order = orderOverride ?: return@LaunchedEffect
        if (rows.size == order.size && rows.map { it.id } == order) orderOverride = null
    }

    // 换序落位只保留一帧的无动画状态，下一帧恢复 placement 动画（服务删行收拢）
    LaunchedEffect(placementSuppressed) {
        if (placementSuppressed) {
            withFrameNanos { }
            placementSuppressed = false
        }
    }

    // 滚动一开始就把滑开的条目收回去：滑动中的列表还挂着一个按钮既难看也易误触
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) revealedKey = null
    }

    // ---- 拖动中的派生量：被拖行停在原槽（from），目标槽按拖动量换算（to） ----
    val layout = listState.layoutInfo
    /** 被拖行的槽位。拖动途中不换序，所以整个拖动期间恒定。 */
    val draggingIndex = draggingKey
        ?.let { k -> displayRows.indexOfFirst { it.id.storageKey == k } }
        ?.takeIf { layout.visibleItemsInfo.any { info -> info.index == it } }
        ?: -1
    /** 行高基准：取第一可见行情行的实测高（各行同模板等高），兜底 72dp。 */
    val rowHPx = layout.visibleItemsInfo
        .firstOrNull { it.index < displayRows.size }
        ?.size?.toFloat() ?: with(density) { 72.dp.toPx() }
    /** 被拖行按拖动量折算的目标槽位：跨过整行才算一步，半行内松手回原槽。 */
    val draggingTo = if (draggingIndex < 0) -1 else
        (draggingIndex + (dragOffset.floatValue / rowHPx).roundToInt())
            .coerceIn(0, displayRows.lastIndex)

    /** 槽位 [i] 的顶部偏移；行滚出视口时返回 null（让位动画随之回正）。 */
    fun slotOffsetOf(i: Int): Int? =
        layout.visibleItemsInfo.firstOrNull { it.index == i && it.index < displayRows.size }?.offset

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.Xl),
    ) {
        itemsIndexed(
            items = displayRows,
            key = { _, row -> row.id.storageKey },
            contentType = { _, _ -> "ticker" },
        ) { index, row ->
            val key = row.id.storageKey
            val dragging = draggingKey == key
            // 让位：被拖行划过的区间整体挪一行，动画过渡防瞬移。
            // 让位量取相邻槽位的实测 offset 差，行高不均时也对得齐。
            val shiftTarget = when {
                draggingIndex < 0 || dragging -> 0f
                draggingTo > draggingIndex && index > draggingIndex && index <= draggingTo -> {
                    val to = slotOffsetOf(index - 1)
                    val self = slotOffsetOf(index)
                    if (to != null && self != null) (to - self).toFloat() else 0f
                }
                draggingTo < draggingIndex && index >= draggingTo && index < draggingIndex -> {
                    val to = slotOffsetOf(index + 1)
                    val self = slotOffsetOf(index)
                    if (to != null && self != null) (to - self).toFloat() else 0f
                }
                else -> 0f
            }
            val shift by animateFloatAsState(
                targetValue = shiftTarget,
                // 拖动中让位走 tween；换序落位瞬间 snap 归零——此刻行的新槽位
                // 恰好就是它让位时的视觉位置，平移过去才是对的，动画反而会抖
                animationSpec = if (draggingKey != null) tween(150) else snap(),
                label = "rowShift",
            )
            TickerRowItem(
                row = row,
                dragging = dragging,
                // 只有被拖行才应用悬浮位移：这个 lambda 若无条件读 dragOffset，
                // 所有行的内容层都会跟着手指平移，露出底下的红色「移除」垫层
                dragOffsetY = { if (dragging) dragOffset.floatValue else 0f },
                shiftY = { shift },
                revealed = revealedKey == key,
                onRevealChange = { open -> revealedKey = if (open) key else null },
                onGestureStart = { if (revealedKey != key) revealedKey = null },
                onDragStart = {
                    revealedKey = null
                    draggingKey = key
                    dragOffset.floatValue = 0f
                },
                onDrag = { dy ->
                    if (draggingIndex >= 0) {
                        // 首行拖不出上边界，尾行拖不出下边界
                        dragOffset.floatValue = (dragOffset.floatValue + dy)
                            .coerceIn(
                                -draggingIndex * rowHPx,
                                (displayRows.lastIndex - draggingIndex) * rowHPx,
                            )
                    }
                },
                onDragEnd = {
                    val from = draggingIndex
                    val to = draggingTo
                    draggingKey = null
                    dragOffset.floatValue = 0f
                    if (from >= 0 && to >= 0 && from != to) {
                        // 松手才真正换位：本地一次重排 + 一次落库
                        val current = displayRows.map { it.id }.toMutableList()
                        current.add(to, current.removeAt(from))
                        orderOverride = current
                        placementSuppressed = true
                        onMove(row.id, to)
                    }
                },
                onClick = { onOpenDetail(row.id) },
                onRemove = { onRemove(row.id) },
                // 拖动中不能开位置动画：被拖行自己跟手，让位行有自己的 tween。
                // 换序落位那一帧也要关（见 placementSuppressed），否则 animateItem
                // 不知道行已被手动让位，会从旧槽再滑一遍。
                // 淡入/淡出始终关掉：滚动时新进入视口的行不该补一次淡入。
                modifier = if (dragging || placementSuppressed) Modifier
                else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
            )
        }
        item(key = "footer", contentType = "footer") {
            Text(
                text = "数据来自${market.sourceName}公开接口 · 每 500ms 合并一次推送",
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Lg),
                style = MaterialTheme.typography.labelSmall,
                color = MarketTheme.colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 行情行：左（币种名 + 市场/量）· 中（迷你走势线）· 右（价格 + 涨跌胶囊）。
 *
 * 三栏固定在同一个 Row 里而不是分两个 Column，是为了让三栏基线在
 * 任何字号下都对齐——旧版把走势线的位置空着，右侧数字区宽度写死 120dp，
 * 结果不同长度的价格会让右侧看起来在抖。
 *
 * 左滑的红色「移除」垫在内容层底下，内容层往左挪多少就露出多少。
 */
@Composable
private fun TickerRowItem(
    row: TickerRow,
    dragging: Boolean,
    dragOffsetY: () -> Float,
    shiftY: () -> Float,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onGestureStart: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    val priceColor by animateColorAsState(
        targetValue = colors.forChange(row.changePercent),
        animationSpec = tween(Motion.BaseMs),
        label = "rowPriceColor",
    )
    val density = LocalDensity.current
    val maxSwipePx = with(density) { RemoveActionWidth.toPx() }
    /** 内容的横向位置（<= 0，左滑为负）。拖动期间直接跟手，松手才吸附。 */
    val swipeOffset = remember { mutableFloatStateOf(0f) }
    /**
     * 吸附动画的触发计数。
     *
     * 只用 `revealed` 当 key 不够：本来就开着、被再往左拖一点又松手时
     * `revealed` 前后都是 true，effect 不会重跑，内容就停在手指离开的那一点上。
     * 加一个自增值保证每次松手都重新吸附一次。
     */
    var settleToken by remember { mutableIntStateOf(0) }
    val key = row.id.storageKey

    // pointerInput(key) 的 lambda 只在 key 变化时重启；行每次重组传入的新回调闭包
    // （捕获着最新的拖动派生量 draggingIndex/draggingTo/displayRows）它根本看不到。
    // 不转这一手，长按拖动就永远跑在行挂载那一刻的旧闭包上——换位派生量全是 -1，
    // 松手什么都不发生。
    val currentGestureStart by rememberUpdatedState(onGestureStart)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)

    // revealed 变化（自己被滑开、或被别行挤着关掉）与每次松手，都重新吸附到两端之一
    LaunchedEffect(revealed, settleToken) {
        if (settleToken == 0) return@LaunchedEffect
        val target = if (revealed) -maxSwipePx else 0f
        animate(
            initialValue = swipeOffset.floatValue,
            targetValue = target,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        ) { value, _ -> swipeOffset.floatValue = value }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 被拖动的行压在邻居上面，否则它一移动就被后面的行盖住
            .zIndex(if (dragging) 1f else 0f),
    ) {
        // 底层：左滑后露出的「移除」。只在横向滑开时参与组合——纵向长按拖动时
        // 内容层会跟手离开原槽，垫层若常驻就会从空槽里裸露出来（红色一片）。
        if (revealed || swipeOffset.floatValue < 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.down)
                    .clickable(enabled = revealed) { onRemove() },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "移除",
                    modifier = Modifier.width(RemoveActionWidth),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.paper,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 纵向 = 拖动悬浮位移 + 排序让位位移；横向 = 左滑位移
                .offset {
                    IntOffset(
                        swipeOffset.floatValue.roundToInt(),
                        (dragOffsetY() + shiftY()).roundToInt(),
                    )
                }
                // 不透明底色：用来盖住下面左滑后露出的红色「移除」按钮；
                // 被拖行换深一档的底色，悬浮的层次感和「配置周期」对话框一致
                .background(if (dragging) colors.washStrong else colors.paper)
                .clickable {
                    if (revealed) {
                        // 已滑开时，点内容先收起，而不是跳详情
                        onRevealChange(false)
                        settleToken++
                    } else {
                        onClick()
                    }
                }
                // 手势节点排在 clickable 之后（更靠内），保证 Main 阶段先拿到事件，
                // 定性为滑动/拖动后能立刻 consume 掉，clickable 自然让位
                .pointerInput(key) {
                    // 回调要经 rememberUpdatedState 转发且**在调用时才解引用**：
                    // pointerInput 的 lambda 只在 key 变化时执行一次，把 currentDrag
                    // 直接当参数传进去等于把「挂载那一刻」的闭包焊死在手势循环里。
                    detectRowGestures(
                        longPressMs = viewConfiguration.longPressTimeoutMillis,
                        touchSlop = viewConfiguration.touchSlop,
                        onGestureStart = { currentGestureStart() },
                        onSwipe = { dx ->
                            swipeOffset.floatValue =
                                (swipeOffset.floatValue + dx).coerceIn(-maxSwipePx, 0f)
                        },
                        onSwipeEnd = {
                            onRevealChange(swipeOffset.floatValue <= -maxSwipePx / 2f)
                            settleToken++
                        },
                        onDragStart = { currentDragStart() },
                        onDrag = { dy -> currentDrag(dy) },
                        onDragEnd = { currentDragEnd() },
                    )
                },
        ) {
            ListRow(onClick = null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.baseAsset,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${row.marketLabel} · 额 ${row.quoteVolume}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 迷你走势线：最近 24 根 1h 收盘价；缓存里没有就留空（不占位、不画假线）
                if (row.trend.size >= 2) {
                    Sparkline(
                        values = row.trend,
                        modifier = Modifier.width(56.dp).padding(horizontal = Spacing.Xs),
                    )
                } else {
                    Spacer(Modifier.width(56.dp))
                }
                Column(
                    modifier = Modifier.width(104.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = row.price,
                        style = PriceTextStyle,
                        color = priceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    ChangePill(row.changePercent)
                }
            }
            // 分隔线跟着条目走：这样滚动时线与内容一起移动，不会出现「线先到、内容后到」的错位。
            // 放在内容层里而不是外面，左滑时它才和内容一起让位给红色按钮。
            Rule()
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
            color = MarketTheme.colors.muted,
        )
    }
}

@Composable
private fun EmptyState(onOpenSearch: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        HintRow(
            title = "自选列表是空的",
            subtitle = "搜索币种并加入自选，这里会实时显示价格、涨跌与最近一天的走势",
            actionLabel = "搜索并添加 →",
            onAction = onOpenSearch,
        )
    }
}

/** 左滑露出的「移除」按钮宽度。 */
private val RemoveActionWidth = 84.dp

/** 触发刷新所需的下拉位移（阻尼之后）。 */
private val RefreshThreshold = 64.dp

/** 下拉位移上限（阻尼之后）：再拉也只是停在这里。 */
private val RefreshMaxPull = 96.dp

/** 下拉阻尼：手指每走 1px，内容只下移半像素，越拉越沉。 */
private const val PullDamping = 0.5f

/** 一次条目手势最终被定性成什么。 */
private enum class RowGesture {
    /** 抬手时位移还没过阈值 → 点击，交给同行的 `clickable`。 */
    TAP,

    /** 纵向为主 → 列表滚动，本节点完全不参与。 */
    SCROLL,

    /** 横向为主 → 左滑露出「移除」。 */
    SWIPE,

    /** 位移没动、时间先到 → 长按拖动排序。 */
    DRAG,
}

/**
 * 条目手势：长按拖动排序 + 左滑露出「移除」，写在**同一个**手势循环里。
 *
 * 不拆成 `detectDragGesturesAfterLongPress` + 一个横向拖动检测器两层节点：
 * 同一条 Modifier 链上两个检测器谁先拿到事件取决于链序，改一次排版顺序就可能
 * 静默失效——图表里「缩放被打断」就是这么来的。一个循环只有一个裁判。
 *
 * 判定顺序（先到者胜）：
 * 1. 位移先越过 [touchSlop] → 横向为主的 [RowGesture.SWIPE]、纵向为主的 [RowGesture.SCROLL]；
 * 2. 位移始终没越阈值、[longPressMs] 先到 → [RowGesture.DRAG]；
 * 3. 都没发生就抬手 → [RowGesture.TAP]。
 *
 * down 事件**不消费**：消费了列表就滚不动、点击也收不到。
 * 只有定性为滑动/拖动之后才开始消费，把后续事件从父滚动容器与 clickable 手里截下来。
 */
private suspend fun PointerInputScope.detectRowGestures(
    longPressMs: Long,
    touchSlop: Float,
    onGestureStart: () -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeEnd: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        var lastX = down.position.x
        var lastY = down.position.y
        var accumX = 0f
        var accumY = 0f
        // 初值即「长按」：只要第一阶段的等待是超时结束的，定性结果就不会被改写
        var mode = RowGesture.DRAG

        // 等「先定性」或「长按到点」。超时直接结束等待块，与 Compose 内部
        // awaitLongPressOrCancellation 是同一个原理（它也是 withTimeout 包一层）。
        withTimeoutOrNull(longPressMs) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    mode = RowGesture.TAP
                    return@withTimeoutOrNull
                }
                accumX += change.position.x - lastX
                accumY += change.position.y - lastY
                lastX = change.position.x
                lastY = change.position.y
                if (abs(accumX) > touchSlop || abs(accumY) > touchSlop) {
                    mode = if (abs(accumX) > abs(accumY)) RowGesture.SWIPE else RowGesture.SCROLL
                    return@withTimeoutOrNull
                }
            }
        }

        when (mode) {
            // 点击交给 clickable、滚动交给 LazyColumn：本节点提前退场，事件全不消费
            RowGesture.TAP, RowGesture.SCROLL -> return@awaitEachGesture
            RowGesture.SWIPE -> {
                onGestureStart()
                // 把判定阶段攒下的位移补上，否则滑开前会先「僵」住一个 touchSlop
                onSwipe(accumX)
            }

            // 走完超时：位移一直没过阈值，按长按处理
            RowGesture.DRAG -> onDragStart()
        }

        val swiping = mode == RowGesture.SWIPE
        val dragging = mode == RowGesture.DRAG
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null || !change.pressed) {
                // 抬手也要消费：不然 clickable 会把「长按拖动后原地松手」当成一次点击
                if (swiping || dragging) change?.consume()
                break
            }
            val dx = change.position.x - lastX
            val dy = change.position.y - lastY
            lastX = change.position.x
            lastY = change.position.y
            when {
                dragging -> {
                    change.consume()
                    onDrag(dy)
                }

                swiping -> {
                    change.consume()
                    onSwipe(dx)
                }
            }
        }
        if (swiping) onSwipeEnd()
        if (dragging) onDragEnd()
    }
}