package com.waxilo.marketmonitor.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.indicator.EmaAnalysis
import com.waxilo.marketmonitor.domain.indicator.EmaBuckets
import com.waxilo.marketmonitor.domain.indicator.EmaLevelRow
import com.waxilo.marketmonitor.domain.indicator.EmaLineRef
import com.waxilo.marketmonitor.domain.indicator.EmaScan
import com.waxilo.marketmonitor.domain.indicator.MaFamily
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.ui.chart.maRoleForSlot
import com.waxilo.marketmonitor.ui.common.Banner
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.theme.ChartLineColors
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import java.math.BigDecimal
import java.util.Locale

/**
 * EMA 压力支撑分析面板（桌面端 `EmaAnalysisPanel` 的 Android 版）。
 *
 * 一张对照最新价的表：所有已配置 EMA 档位 × 所有已配置周期，按现价分成上方压力 / 下方支撑。
 *
 * 与桌面端一致的几条取舍：
 *
 * - **静默刷新**：面板打开时先用上次的结果铺出来（状态在 ViewModel 里，见 `emaAnalysis`），
 *   后台重取，拿到新值整表替换 —— 不出现「先清空、再转圈、再重排」那段闪烁。刷新中只有
 *   右上角那个「刷新」说话（文案变「刷新中」、不可点），表格照旧可读可点。
 *   面板一直开着时每 1 分钟自动刷新（定时器在 ViewModel，旋屏不中断）。
 * - **刷新失败不清表**：哪个周期没取到就沿用上一轮该周期的各档，并在底部点明这些是上次的值。
 * - **失败与「算不出」分开报**：前者是网络/数据源不通（该重试），后者是这个周期取回的根数
 *   不够算这一档（粗周期上的长档就是这样）。混成一句话会让用户以为取数又坏了。
 * - **点一行画告警线**：每一行就是一个候选价位，点一下在图上落一条告警线（穿越弹通知），
 *   再点一下取消。面板**不关**，所以可以连着点几道压力位一起画。这条线是普通告警规则
 *   （名字 = `1时EMA50`），面板靠这个名字回显：行尾标「已画」；EMA 每分钟都在动、线停在
 *   画它的那一刻，所以价位对不上当前值的标「待对齐」，点一下把它拉到当前值。
 *
 * 版式与桌面端不同：桌面端两列并排（宽屏），这里**竖屏用页签切换**、横屏仍两列。
 * 竖屏一行放不下两列（每列不到 180dp，六个字段会挤成一团）；早先的做法是把两侧叠在一列里
 * （压力倒序在上、现价分隔、支撑正序在下），滚一趟要翻过整张表才能看见另一侧，而且压力有
 * 十几道时「下方支撑」被顶到了屏幕外——用户根本不知道下面还有内容。改成页签后一次只呈现
 * 一侧，两个页签各自带条数，切过去就知道另一侧有几道、不用翻。
 *
 * 页签内的排序改了：**两侧都按「离现价由近到远」**（压力从低到高、支撑从高到低）。原来
 * 单列那套「压力倒序」是为了与图的纵轴同向，页签化之后这个约束没了，第一条就该是最有用的
 * 那一条（最近的一道），也与表头写的「最近 X · EMA Y」对得上。横屏两列保持原序（贴在
 * 图旁边看，纵向对得上图更重要）。
 */
@Composable
fun EmaAnalysisSheet(
    state: DetailUiState,
    analysis: EmaAnalysisState,
    /** 本标的的全部告警规则（回显用：名字 = 自动备注的那条就是「这一档画过」）。 */
    rules: List<AlertRule>,
    /** 一次性提示（画上 / 对齐 / 取消的结果）。竖屏详情页不显示 notice，所以面板自己带一条。 */
    notice: String?,
    onRefresh: () -> Unit,
    onToggleLine: (EmaLevelRow) -> Unit,
    onPickInterval: (CandleInterval) -> Unit,
    onDismiss: () -> Unit,
) {
    // 横屏（全屏看图那一档）屏高只有 360dp 上下：30 行单列要滚很久，改两列并排 ——
    // 与桌面端的版式一致，也正是「宽大于高」时才划算的排法。
    val twoColumn = LocalConfiguration.current.screenHeightDp < 480
    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false：让窗口铺到状态栏下面，Surface 的纸色才盖得住
        // 那一块（否则状态栏那一条露的是系统窗口背景，屏幕上像多了一道灰边）
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val colors = MarketTheme.colors
        // 窗口铺到状态栏下面了（见上面的 decorFitsSystemWindows），状态栏图标的明暗就得自己声明：
        // dialog 是新窗口，不继承 Activity 的 edge-to-edge 设置，默认用浅色图标 ——
        // 画在纸白顶栏上等于整片消失（真机上表现为「时间、电量不见了」）。
        // 判据取纸色的亮度，深色主题下自动翻成浅色图标，不用再往主题里加一个 isDark 开关。
        val view = LocalView.current
        val lightBars = colors.paper.luminance() > 0.5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        val scan = analysis.scan
        // 比较基准：实时价优先（行情一来就跟着动），没有才用末根收盘
        val live = state.lastPrice?.takeIf { it.isFinite() && it > 0 }
        val base = live ?: scan.fallbackPrice
        val buckets = remember(scan.levels, base) { EmaAnalysis.split(scan.levels, base) }
        /**
         * 一档 → 图上那条线。整张表一次算好：行标记、列头「已画 N」都读它，
         * 别让同一个匹配在一帧里跑几十遍。
         */
        val refs = remember(rules, scan.levels) {
            buildMap {
                for (level in scan.levels) {
                    put(lineKey(level.interval, level.period), EmaAnalysis.lineOf(rules, level))
                }
            }
        }
        val refOf: (EmaLevelRow) -> EmaLineRef? = { row -> refs[lineKey(row.interval, row.period)] }
        val orphans = remember(rules, state.intervals, state.emaCatalog) {
            EmaAnalysis.orphanLineNames(rules, state.intervals, state.emaCatalog)
        }
        val hasTable = scan.levels.isNotEmpty()

        // 竖屏页签，默认「上方压力」。`-1` = 用户还没手动切过：这种情况下只在第一次有表时
        // 按「哪边有货」定一次初值（现价站在所有 EMA 之上时压力侧是空的，开面板迎面一张空表
        // 会让人以为取数坏了）。一旦手动点过就认用户的选择，哪怕那一侧这次是空的 ——
        // 强行弹回去比空表更让人迷惑（点一下自己跑回去了）。
        var picked by rememberSaveable { mutableStateOf(-1) }
        val side = when {
            picked == 1 -> Side.SUPPORT
            picked == 0 -> Side.RESISTANCE
            buckets.resistance.isEmpty() && buckets.support.isNotEmpty() -> Side.SUPPORT
            else -> Side.RESISTANCE
        }
        // 两侧各一个滚动状态，都挂在**这一层**：切页签时它们都不重建，切回来还停在原处。
        // 别拿 `rememberSaveable(side, ...)` 图省事 —— inputs 一变它会丢掉旧值重新 init，
        // 结果就是「切过去再切回来跳回顶部」（真机上验过）。也不能共用一个 state：
        // 从压力滚到一半切去支撑，行数少的一侧会直接落到空白处。
        val resistanceScroll = rememberLazyListState()
        val supportScroll = rememberLazyListState()

        Surface(modifier = Modifier.fillMaxSize(), color = colors.paper) {
            // 外层 Box 只为一件事：让「操作回执」浮在版面之上。
            // 挂在 Column 里当普通子节点会顶掉 LazyColumn 的高度，列表会跟着跳。
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    PanelHead(
                        title = state.title,
                        base = base,
                        live = live != null,
                        tickSize = state.tickSize,
                        refreshing = analysis.refreshing,
                        done = analysis.done,
                        total = state.intervals.size,
                        updatedAt = analysis.updatedAt,
                        onRefresh = onRefresh,
                        onDismiss = onDismiss,
                    )
                    Rule(inset = Spacing.Gutter)

                    // 页签固定在这一层（不随列表滚）：它同时是「另一侧有几道」的入口，
                    // 滚到第十几行时还得能看见、还得能切过去。
                    if (!twoColumn && hasTable) {
                        SideTabs(
                            side = side,
                            buckets = buckets,
                            onSelect = { picked = if (it == Side.SUPPORT) 1 else 0 },
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.intervals.isEmpty() -> EmptyHint(
                                "周期条上还没有配置 K 线周期",
                                "先在周期那一行的「＋」里加一个，再来分析",
                            )

                            state.emaCatalog.isEmpty() -> EmptyHint(
                                "EMA 一档都没配置",
                                "先在指标栏「＋」里加几档 EMA，再来分析",
                            )

                            !hasTable && analysis.refreshing -> EmptyHint(
                                "正在取 ${state.intervals.size} 个周期的 K 线… ${analysis.done} / ${state.intervals.size}",
                                null,
                                numeric = true,
                            )

                            !hasTable -> EmptyHint("所有周期都没能算出 EMA", "原因见下方提示")

                            twoColumn -> Row(modifier = Modifier.fillMaxSize()) {
                                ColumnColumn(
                                    rows = buckets.resistance,
                                    side = Side.RESISTANCE,
                                    reversed = true,
                                    modifier = Modifier.weight(1f),
                                    state = state,
                                    refOf = refOf,
                                    onToggleLine = onToggleLine,
                                    onPickInterval = onPickInterval,
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxSize()
                                        .background(colors.hairline),
                                )
                                ColumnColumn(
                                    rows = buckets.support,
                                    side = Side.SUPPORT,
                                    reversed = false,
                                    modifier = Modifier.weight(1f),
                                    state = state,
                                    refOf = refOf,
                                    onToggleLine = onToggleLine,
                                    onPickInterval = onPickInterval,
                                )
                            }

                            else -> TabbedColumn(
                                side = side,
                                rows = if (side == Side.RESISTANCE) buckets.resistance else buckets.support,
                                listState = if (side == Side.RESISTANCE) resistanceScroll else supportScroll,
                                state = state,
                                refOf = refOf,
                                onToggleLine = onToggleLine,
                                onPickInterval = onPickInterval,
                            )
                        }
                    }

                    Footnotes(
                        scan = scan,
                        orphans = orphans,
                        tickSize = state.tickSize,
                        onPickInterval = onPickInterval,
                    )
                }

            // 操作回执：竖屏详情页不显示 notice，面板自己带一条，
            // 否则「点了一下到底画上没画上」只剩行尾那个小标记可说。
            //
            // **浮层**（Box 的第二个子节点 + BottomCenter），不参与 Column 的高度分配 ——
            // 插进 Column 会让 LazyColumn 少掉一条提示条的高度，列表在那一秒里会自己挪几行
            // （用户读到的是「点一下，我正在看的那行跑了」）。
            AnimatedVisibility(
                visible = notice != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
            ) {
                // Banner 自己的底色只有 7% 透明度（贴在页面里够用），浮起来会透出底下的行文字，
                // 所以外面再垫一层不透明的纸色 + 圆角 + 投影。
                Surface(
                    color = colors.paper,
                    shape = RoundedCornerShape(Radius.Xs),
                    shadowElevation = 6.dp,
                ) {
                    Banner(text = notice.orEmpty(), tone = BannerTone.Success)
                }
            }
            }
        }
    }
}

/** 一档 (周期, 档位) 的稳定键 —— 只在这一层做映射，别和 `EmaAnalysis` 内部的口径混用。 */
private fun lineKey(interval: CandleInterval, period: Int): String = "${interval.storageKey}|$period"

private enum class Side(val title: String, val empty: String) {
    RESISTANCE("上方压力", "现价上方还没有已配置的 EMA"),
    SUPPORT("下方支撑", "现价下方还没有已配置的 EMA"),
}

/**
 * 抬头：标的名 + 现价 + 刷新 + 关闭 + 面板身份，压成两行 64dp 上下（原来是三行 124dp）。
 *
 * 早先是三行（「EMA · 压力支撑分析」+ 关闭 / 标的 + 现价 + 刷新 / 自动刷新提示）。
 * 横屏时屏高只有 360dp 上下，那三行连分隔线要吃掉 120dp 出头 —— 表才是这块面板的主体，
 * 抬头只该是一道边，不该占掉近半个屏。压成两行后一屏能多看出四五道 EMA。
 *
 * 两行怎么分按**可用宽度**决定（`screenWidthDp`，不是方向）：
 * - 宽屏一行装得下：标的名 · 现价 · 刷新 · 关闭全在第一行，第二行只有元信息；
 * - 窄屏（竖屏 360dp）一行装不下：第一行只放标的名与两个操作（标的名绝不能被价格挤成
 *   「BTC/U…」—— 这块面板说的就是它），现价与元信息挪到第二行。
 */
@Composable
private fun PanelHead(
    title: String,
    base: Double,
    /** 基准是真·实时价（false = 用的是末根收盘，得说清）。 */
    live: Boolean,
    tickSize: BigDecimal?,
    refreshing: Boolean,
    done: Int,
    total: Int,
    updatedAt: Long,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MarketTheme.colors
    // 一行放得下三个数值 + 两个操作吗（横屏 640dp 起算宽裕，竖屏 360dp 不够）
    val roomy = LocalConfiguration.current.screenWidthDp >= 480
    // 元信息：窄屏承不起「面板身份 + 完整刷新节奏」一长串（价格占掉第二行左半边了），
    // 就只留节奏与时刻 —— 身份本来也不用写，竖屏这两行上面就是「上方压力 / 下方支撑」页签
    val meta = if (roomy) {
        buildString {
            append("EMA · 压力支撑分析 · 每 1 分钟自动刷新")
            // 刷新中不报「上次」：那一秒里它正被新值取代，写出来反而让人去核对一个将过期的时刻
            if (!refreshing && updatedAt > 0) append(" · 上次 ${clockText(updatedAt)}")
        }
    } else {
        buildString {
            append("每分钟刷新")
            if (!refreshing && updatedAt > 0) append(" · ${clockText(updatedAt)} 更新")
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 不给它 weight：标的名只按内容占位，价格紧随其后，剩余宽度留给尾部那个 Spacer
            // （两个 weight 会平分空间，结果价格与「刷新」都被推到屏幕中间）
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (roomy) {
                Spacer(Modifier.width(Spacing.Sm))
                PriceBlock(base = base, live = live, tickSize = tickSize)
            }
            // 这一格吃掉剩余宽度：数值贴着标的名，刷新与关闭靠右
            Spacer(Modifier.weight(1f))
            TextAction(
                text = if (refreshing) "刷新中 $done/$total" else "刷新",
                onClick = { if (!refreshing) onRefresh() },
                color = if (refreshing) colors.muted else colors.ink,
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!roomy) {
                PriceBlock(base = base, live = live, tickSize = tickSize)
                Spacer(Modifier.width(Spacing.Sm))
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 现价 + 「最新价 / 末根收盘价」—— 一行还是两行的版式里都长这样，抽出来省得两处走样。 */
@Composable
private fun PriceBlock(base: Double, live: Boolean, tickSize: BigDecimal?) {
    val colors = MarketTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatPrice(base, tickSize),
            style = PriceTextStyle,
            color = colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(Spacing.Xxs))
        Text(
            text = if (live) "最新价" else "末根收盘价",
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
        )
    }
}

/** 竖屏页签：两个页签等宽，各自带条数 —— 切过去之前就知道另一侧有几道。 */
@Composable
private fun SideTabs(side: Side, buckets: EmaBuckets, onSelect: (Side) -> Unit) {
    SegmentedControl(
        options = listOf(Side.RESISTANCE, Side.SUPPORT),
        selected = side,
        labelOf = { s -> "${s.title} ${if (s == Side.RESISTANCE) buckets.resistance.size else buckets.support.size}" },
        onSelect = onSelect,
        modifier = Modifier.padding(horizontal = Spacing.Gutter),
    )
}

/**
 * 竖屏页签里的那一侧。
 *
 * 行序直接用 `buckets` 里的原序：压力升序、支撑降序，两者都是**离现价由近到远**，
 * 所以第一条就是最近的一道 —— 与表头「最近 X · EMA Y」说的就是同一行。
 * （横屏两列仍用 [ColumnColumn] 的「压力倒序」，那里贴着图看、纵向与图同向更好。）
 */
@Composable
private fun TabbedColumn(
    side: Side,
    rows: List<EmaLevelRow>,
    listState: LazyListState,
    state: DetailUiState,
    refOf: (EmaLevelRow) -> EmaLineRef?,
    onToggleLine: (EmaLevelRow) -> Unit,
    onPickInterval: (CandleInterval) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "head") {
            ColumnHead(
                side = side,
                rows = rows,
                marked = rows.count { refOf(it) != null },
                showTitle = false,
            )
        }
        items(rows, key = { lineKey(it.interval, it.period) }) { row ->
            EmaRow(
                row = row,
                line = refOf(row),
                side = side,
                state = state,
                onToggle = { onToggleLine(row) },
                onPickInterval = onPickInterval,
            )
        }
        if (rows.isEmpty()) item(key = "empty") { EmptyRow(side.empty) }
    }
}

/** 横屏：两列并排（各自的滚动条），与桌面端同版式。 */
@Composable
private fun ColumnColumn(
    rows: List<EmaLevelRow>,
    side: Side,
    reversed: Boolean,
    modifier: Modifier,
    state: DetailUiState,
    refOf: (EmaLevelRow) -> EmaLineRef?,
    onToggleLine: (EmaLevelRow) -> Unit,
    onPickInterval: (CandleInterval) -> Unit,
) {
    val ordered = if (reversed) rows.asReversed() else rows
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "head") { ColumnHead(side = side, rows = rows, marked = rows.count { refOf(it) != null }) }
        items(ordered, key = { lineKey(it.interval, it.period) }) { row ->
            EmaRow(
                row = row,
                line = refOf(row),
                side = side,
                state = state,
                onToggle = { onToggleLine(row) },
                onPickInterval = onPickInterval,
            )
        }
        if (rows.isEmpty()) item(key = "empty") { EmptyRow(side.empty) }
    }
}

/**
 * 一列 / 一页的表头：名字 + 条数 + 已画数 + 「最近的一道」。
 *
 * [showTitle] = false 用于竖屏页签：名字与条数已经在页签上了，再写一遍是重复；
 * 留下的「已画 N」用本侧的色（压力红 / 支撑绿），页签是墨黑的，这点色正好接住
 * 「这列画的线是哪一类」的语义。
 */
@Composable
private fun ColumnHead(
    side: Side,
    rows: List<EmaLevelRow>,
    marked: Int,
    showTitle: Boolean = true,
) {
    val colors = MarketTheme.colors
    val nearest = rows.firstOrNull()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTitle) {
                Text(
                    text = side.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (side == Side.RESISTANCE) colors.down else colors.up,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = "${rows.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
                if (marked > 0) {
                    Spacer(Modifier.width(Spacing.Xs))
                    Text(
                        text = "已画 $marked",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            } else if (marked > 0) {
                Text(
                    text = "已画 $marked",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (side == Side.RESISTANCE) colors.down else colors.up,
                )
            }
            Spacer(Modifier.weight(1f))
            if (nearest != null) {
                Text(
                    text = "最近 ${nearest.interval.label} · EMA${nearest.period}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        }
    }
}

/**
 * 一行 = 一个候选价位。
 *
 * 整行可点（画 / 对齐 / 取消告警线）；行内那枚周期小方框**单独可点**（切到那个周期看图，
 * 面板随即关掉）。两种动作分开是刻意的：把切周期放在整行上，点一下就离开表，
 * 没法连着点几道压力位一起画。
 */
@Composable
private fun EmaRow(
    row: EmaLevelRow,
    line: EmaLineRef?,
    side: Side,
    state: DetailUiState,
    onToggle: () -> Unit,
    onPickInterval: (CandleInterval) -> Unit,
) {
    val colors = MarketTheme.colors
    val slot = state.emaColors[row.period] ?: 0
    val dot = ChartLineColors[maRoleForSlot(MaFamily.EMA, slot).ordinal]
    val drifted = line != null && !line.aligned
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(if (line != null && !drifted) colors.wash else Color.Transparent)
            .padding(horizontal = Spacing.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            text = "EMA${row.period}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        IntervalBadge(row.interval.label, onPick = { onPickInterval(row.interval) })
        Spacer(Modifier.weight(1f))
        Text(
            text = formatPrice(row.value, state.tickSize),
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            // 颜色按**列**取（压力红 / 支撑绿），不按正负号取 ——
            // 压力列的偏离恒为正，交给 forChange 会渲染成「涨绿」，与列头的红色自相矛盾。
            text = PriceFormatter.formatChange(row.gapPercent),
            style = MaterialTheme.typography.labelSmall,
            color = if (side == Side.RESISTANCE) colors.down else colors.up,
            textAlign = TextAlign.End,
            modifier = Modifier.width(54.dp),
            maxLines = 1,
        )
        Spacer(Modifier.width(Spacing.Xs))
        // 回显：这条线在图上（已画），还是画在旧价位上（待对齐）。
        // 用文字不用图形 —— 这一行已经有色点、底色、两处变色数值，再加符号会看不清谁在说什么。
        Text(
            text = when {
                line == null -> ""
                line.aligned -> "已画"
                else -> "待对齐"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                line == null -> Color.Transparent
                line.aligned -> colors.down
                else -> colors.muted
            },
            textAlign = TextAlign.End,
            modifier = Modifier.width(42.dp),
            maxLines = 1,
        )
    }
}

/** 周期小方框：点它 = 切到那个周期看图（与整行那个动作不同，所以自带背景框）。 */
@Composable
private fun IntervalBadge(text: String, onPick: () -> Unit) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Xs))
            .background(colors.washStrong)
            .clickable(onClick = onPick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = colors.ink,
            maxLines = 1,
        )
    }
}

/** 空列 / 空表的一行说明。 */
@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MarketTheme.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    )
}

@Composable
private fun EmptyHint(title: String, subtitle: String?, numeric: Boolean = false) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = if (numeric) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 表尾的三类提示（失败 / 根数不够 / 孤儿线）。
 *
 * 失败与「算不出」**分开报**：前者是网络不通（该重试），后者是这个周期取回的根数不够算
 * 这一档（粗周期上的长档就是这样）。混成一句话会让用户以为取数又坏了。
 * 点周期标签能直接切过去看 —— 这是「为什么这一档没进表」最省事的回答。
 */
@Composable
private fun Footnotes(
    scan: EmaScan,
    orphans: List<String>,
    tickSize: BigDecimal?,
    onPickInterval: (CandleInterval) -> Unit,
) {
    val colors = MarketTheme.colors
    if (scan.failures.isEmpty() && scan.missing.isEmpty() && orphans.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs)) {
        if (scan.failures.isNotEmpty()) {
            Text(
                text = buildString {
                    append("${scan.failures.size} 个周期没取到：")
                    append(scan.failures.joinToString(" / ") { it.interval.label })
                    if (scan.stale.isNotEmpty()) {
                        append("（")
                        append(scan.stale.joinToString(" / ") { it.label })
                        append(" 显示的是上次的值）")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.down,
            )
            Text(
                text = scan.failures.joinToString("\n") { "${it.interval.label}：${it.reason}" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (scan.missing.isNotEmpty()) {
            Text(
                text = buildString {
                    append("${scan.missing.size} 档根数不够算不出：")
                    append(scan.missing.take(6).joinToString(" / ") { "${it.interval.label}·EMA${it.period}" })
                    if (scan.missing.size > 6) append(" …")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.padding(top = if (scan.failures.isEmpty()) 0.dp else Spacing.Xxs),
            )
            Text(
                text = scan.missing.joinToString("\n") {
                    "${it.interval.label} 的 EMA${it.period}：只取到 ${it.bars} 根，至少要 ${it.period} 根"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (orphans.isNotEmpty()) {
            Text(
                text = buildString {
                    append("图上还有 ${orphans.size} 条 EMA 划线不在当前配置里（档位或周期被删掉了）：")
                    append(orphans.take(4).joinToString(" / "))
                    if (orphans.size > 4) append(" …")
                    append("（改回配置即可回显，或在图上删掉）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.padding(top = if (scan.failures.isEmpty() && scan.missing.isEmpty()) 0.dp else Spacing.Xxs),
            )
        }
    }
}

private fun formatPrice(value: Double, tickSize: BigDecimal?): String {
    if (!value.isFinite() || value <= 0) return PriceFormatter.NO_DATA
    return PriceFormatter.format(BigDecimal.valueOf(value), tickSize)
}

/** `HH:mm:ss`。只进提示文案，不占版面（版面多一个字都会挤掉价位）。 */
private fun clockText(at: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(at))
