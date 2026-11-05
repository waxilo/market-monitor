package com.waxilo.marketmonitor.ui.detail

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import com.waxilo.marketmonitor.domain.indicator.MaAnalysis
import com.waxilo.marketmonitor.domain.indicator.MaBuckets
import com.waxilo.marketmonitor.domain.indicator.MaCurve
import com.waxilo.marketmonitor.domain.indicator.MaLevelRow
import com.waxilo.marketmonitor.domain.indicator.MaLineRef
import com.waxilo.marketmonitor.domain.indicator.MaScan
import com.waxilo.marketmonitor.domain.indicator.MaFamily
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.ui.chart.maRoleForSlot
import com.waxilo.marketmonitor.ui.common.Banner
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.theme.ChartLineColors
import com.waxilo.marketmonitor.ui.theme.InlineActionStyle
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 均线压力支撑分析面板（桌面端 `MaAnalysisPanel` 的 Android 版；MA / EMA 共用）。
 *
 * 一张对照最新价的表：当前一族所有已配置档位 × 所有已配置周期，按现价分成上方压力 / 下方支撑。
 *
 * 与桌面端一致的几条取舍：
 *
 * - **静默刷新**：面板打开时先用上次的结果铺出来（状态在 ViewModel 里，见 `maAnalysis`），
 *   后台重取，拿到新值整表替换 —— 不出现「先清空、再转圈、再重排」那段闪烁。刷新中只有
 *   右上角那个「刷新」说话（文案变「刷新中」、不可点），表格照旧可读可点。
 *   面板一直开着时每 1 分钟自动刷新（定时器在 ViewModel，旋屏不中断）。
 * - **刷新失败不清表**：哪个周期没取到就沿用上一轮该周期的各档，并在底部点明这些是上次的值。
 * - **失败与「算不出」分开报**：前者是网络/数据源不通（该重试），后者是这个周期取回的根数
 *   不够算这一档（粗周期上的长档就是这样）。混成一句话会让用户以为取数又坏了。
 * - **点一行 = 选中**：缩略图跟着换成那一档的形态（见下），行铺淡黄底 + 右缘黄条。
 *   「已画」是另一层状态：已有图上的告警线 → 告警红底 + 左缘红条（两层互不覆盖）。
 * - **画告警线是缩略图那一栏的按钮**：选中一条后按「画告警线 / 对齐到当前值 / 删除告警线」。
 *   这条线是普通告警规则（名字 = `1时MA20` / `1时EMA50`），面板靠这个名字回显。均线每分钟
 *   都在动，所以**每轮刷新都会把这些自动线拉到新值**（见 `DetailViewModel.alignMaRules` →
 *   `MaAnalysis.alignments`）—— 线是用来看的一道墙，得长在均线现在的位置上。
 *   手画的线、另一族画的线一概不动。
 *
 * 版式：一栏缩略图 + 一栏页签列表。「上方压力 / 下方支撑」两个页签一次只呈现一侧
 * （早先两侧叠在一列里，压力十几道时支撑被顶到屏幕外）；**竖屏缩略图在上半屏**、
 * **横屏缩略图在左半屏**（屏高只有 360dp 上下时竖着叠两边都矮）。
 *
 * 缩略图：收盘价淡灰 + 这一档均线（色槽色）共用一根纵轴，**双指缩放**可见根数
 * （16 ～ 曲线全长 240，默认 120）、双击复位，「− / ＋」兜底。数据来自
 * `MaAnalysisState.curves`（与表同一次递推算出来的尾段）。
 *
 * 排序：**两侧都按「离现价由近到远」**（压力从低到高、支撑从高到低）。表的第一行
 * 应当是最有用的那一条（最近的一道），也与表头写的「最近 X · MA Y」对得上。
 */
@Composable
fun MaAnalysisSheet(
    /** 分析哪一族均线：MA（简单）/ EMA（指数）。 */
    family: MaFamily,
    state: DetailUiState,
    analysis: MaAnalysisState,
    /** 本标的的全部告警规则（回显用：名字 = 自动备注的那条就是「这一档画过」）。 */
    rules: List<AlertRule>,
    /** 一次性提示（画上 / 对齐 / 取消的结果）。竖屏详情页不显示 notice，所以面板自己带一条。 */
    notice: String?,
    onRefresh: () -> Unit,
    onToggleLine: (MaLevelRow) -> Unit,
    /** 一键清掉本标的图上全部本族分析划线（面板自动名的那批告警规则）。 */
    onClearLines: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 横屏：左半屏缩略图、右半屏页签 + 列表（与桌面端同构）；竖屏：上半屏缩略图、下半屏列表。
    // 判据用**屏幕方向**而不是屏高：MuMu 这类 1080p 横屏设备 heightDp 有 540，按高度会被误判成竖屏。
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        val buckets = remember(scan.levels, base) { MaAnalysis.split(scan.levels, base) }
        /**
         * 一档 → 图上那条线。整张表一次算好：行标记、列头「已画 N」都读它，
         * 别让同一个匹配在一帧里跑几十遍。
         */
        val refs = remember(rules, scan.levels, family) {
            buildMap {
                for (level in scan.levels) {
                    put(lineKey(level.interval, level.period), MaAnalysis.lineOf(rules, family, level))
                }
            }
        }
        val refOf: (MaLevelRow) -> MaLineRef? = { row -> refs[lineKey(row.interval, row.period)] }
        val catalog = state.catalogOf(family)
        val orphans = remember(rules, state.intervals, catalog, family) {
            MaAnalysis.orphanLineNames(rules, family, state.intervals, catalog)
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

        /**
         * 当前看的那一档 —— 缩略图跟着它。
         *
         * 存（周期 storageKey, 档位）而不是行对象：每分钟刷新都会重建 `MaLevelRow`，
         * 存行会让选中项被替换成上一轮的那个快照。没存过 / 那一档已不在当前页签里时，
         * 落到这一侧**离现价最近**的一条（列表本来就是这么排的），所以切页签、现价穿档
         * 都能自动跟到一条有效的水位。
         */
        var selectedIv by rememberSaveable { mutableStateOf("") }
        var selectedPeriod by rememberSaveable { mutableStateOf(0) }
        val sideRows = if (side == Side.RESISTANCE) buckets.resistance else buckets.support
        val otherSide = if (side == Side.RESISTANCE) Side.SUPPORT else Side.RESISTANCE
        val otherRows = if (side == Side.RESISTANCE) buckets.support else buckets.resistance
        /**
         * 一侧为空时的解释：现价刚穿过**全部** EMA 时（单边急拉 / 急跌很常见），这一侧
         * 一条都没有 —— 空表本身没错，但只写一句「还没有」看着像取数坏了。这里说清
         * 「现价已在全部 N 档之上/之下」，并把另一侧最近的一道做成可点的入口。
         */
        val emptyHint = if (sideRows.isEmpty() && otherRows.isNotEmpty()) {
            val nearest = otherRows.first()
            EmptySideHint(
                title = if (side == Side.RESISTANCE) {
                    "现价上方没有已配置的 ${family.label}"
                } else {
                    "现价下方没有已配置的 ${family.label}"
                },
                detail = buildString {
                    append("现价 ${formatPrice(base, state.tickSize)} ")
                    append("已在全部 ${buckets.resistance.size + buckets.support.size} 档 ${family.label} ")
                    append(if (side == Side.RESISTANCE) "之上" else "之下")
                    append("；最近的一道在")
                    append(if (side == Side.RESISTANCE) "下方" else "上方")
                    append("：${nearest.interval.label} · ${family.lineLabel(nearest.period)}（")
                    append(PriceFormatter.formatChange(nearest.gapPercent))
                    append("）")
                },
                jumpLabel = "看${otherSide.title}最近的一道",
            )
        } else {
            null
        }
        /** 空态里的「看另一侧」：切页签 + 选中那一侧离现价最近的一条。 */
        val jumpToOther: () -> Unit = {
            otherRows.firstOrNull()?.let { nearest ->
                picked = if (otherSide == Side.SUPPORT) 1 else 0
                selectedIv = nearest.interval.storageKey
                selectedPeriod = nearest.period
            }
        }
        val selectedRow = sideRows.firstOrNull {
            it.interval.storageKey == selectedIv && it.period == selectedPeriod
        }
        LaunchedEffect(sideRows, selectedIv, selectedPeriod) {
            if (selectedRow == null) {
                sideRows.firstOrNull()?.let {
                    selectedIv = it.interval.storageKey
                    selectedPeriod = it.period
                }
            }
        }
        val selectionKey = selectedRow?.let { lineKey(it.interval, it.period) }.orEmpty()
        val selectedCurve = selectedRow?.let { analysis.curves[lineKey(it.interval, it.period)] }
        val selectedLine = selectedRow?.let(refOf)

        Surface(modifier = Modifier.fillMaxSize(), color = colors.paper) {
            // 外层 Box 只为一件事：让「操作回执」浮在版面之上。
            // 挂在 Column 里当普通子节点会顶掉 LazyColumn 的高度，列表会跟着跳。
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    PanelHead(
                        family = family,
                        title = state.title,
                        base = base,
                        live = live != null,
                        tickSize = state.tickSize,
                        refreshing = analysis.refreshing,
                        done = analysis.done,
                        total = state.intervals.size,
                        updatedAt = analysis.updatedAt,
                        marked = remember(rules, family) {
                            rules.count { MaAnalysis.isFamilyAutoNote(it.name, family) }
                        },
                        onRefresh = onRefresh,
                        onClearLines = onClearLines,
                        onDismiss = onDismiss,
                    )
                    Rule(inset = Spacing.Gutter)

                    // 选中那一档的色槽（缩略图的均线用它）
                    val selectedColor = selectedRow
                        ?.let { ChartLineColors[maRoleForSlot(family, state.colorsOf(family)[it.period] ?: 0).ordinal] }
                        ?: colors.muted
                    val selectTab: (Side) -> Unit = { picked = if (it == Side.SUPPORT) 1 else 0 }
                    val selectRow: (MaLevelRow) -> Unit = {
                        selectedIv = it.interval.storageKey
                        selectedPeriod = it.period
                    }
                    val listState = if (side == Side.RESISTANCE) resistanceScroll else supportScroll

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.intervals.isEmpty() -> EmptyHint(
                                "周期条上还没有配置 K 线周期",
                                "先在周期那一行的「＋」里加一个，再来分析",
                            )

                            catalog.isEmpty() -> EmptyHint(
                                "${family.label} 一档都没配置",
                                "先在指标栏「＋」里加几档 ${family.label}，再来分析",
                            )

                            !hasTable && analysis.refreshing -> EmptyHint(
                                "正在取 ${state.intervals.size} 个周期的 K 线… ${analysis.done} / ${state.intervals.size}",
                                null,
                                numeric = true,
                            )

                            !hasTable -> EmptyHint("所有周期都没能算出 ${family.label}", "原因见下方提示")

                            // 横屏：左半屏缩略图、右半屏页签 + 列表
                            landscape -> Row(modifier = Modifier.fillMaxSize()) {
                                MaShapePane(
                                    family = family,
                                    row = selectedRow,
                                    curve = selectedCurve,
                                    side = side,
                                    line = selectedLine,
                                    color = selectedColor,
                                    tickSize = state.tickSize,
                                    resetKey = selectionKey,
                                    emptyHint = emptyHint,
                                    onJump = jumpToOther,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onToggle = { selectedRow?.let(onToggleLine) },
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(colors.hairline),
                                )
                                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    SideTabs(side = side, buckets = buckets, onSelect = selectTab)
                                    TabbedColumn(
                                        family = family,
                                        side = side,
                                        rows = sideRows,
                                        listState = listState,
                                        state = state,
                                        refOf = refOf,
                                        selectedKey = selectionKey,
                                        onSelect = selectRow,
                                        emptyHint = emptyHint,
                                        onJump = jumpToOther,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            // 竖屏：上半屏缩略图、下半屏页签 + 列表
                            else -> Column(modifier = Modifier.fillMaxSize()) {
                                MaShapePane(
                                    family = family,
                                    row = selectedRow,
                                    curve = selectedCurve,
                                    side = side,
                                    line = selectedLine,
                                    color = selectedColor,
                                    tickSize = state.tickSize,
                                    resetKey = selectionKey,
                                    emptyHint = emptyHint,
                                    onJump = jumpToOther,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    onToggle = { selectedRow?.let(onToggleLine) },
                                )
                                SideTabs(side = side, buckets = buckets, onSelect = selectTab)
                                TabbedColumn(
                                    family = family,
                                    side = side,
                                    rows = sideRows,
                                    listState = listState,
                                    state = state,
                                    refOf = refOf,
                                    selectedKey = selectionKey,
                                    onSelect = selectRow,
                                    emptyHint = emptyHint,
                                    onJump = jumpToOther,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    Footnotes(
                        family = family,
                        scan = scan,
                        orphans = orphans,
                        tickSize = state.tickSize,
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

/** 一档 (周期, 档位) 的稳定键 —— 只在这一层做映射，别和 `MaAnalysis` 内部的口径混用。 */
private fun lineKey(interval: CandleInterval, period: Int): String = "${interval.storageKey}|$period"

private enum class Side(val title: String) {
    RESISTANCE("上方压力"),
    SUPPORT("下方支撑"),
}

/** 空侧的一句话说明（族名随面板走）。 */
private fun emptySideText(side: Side, family: MaFamily): String = when (side) {
    Side.RESISTANCE -> "现价上方没有已配置的 ${family.label}"
    Side.SUPPORT -> "现价下方没有已配置的 ${family.label}"
}

/** 一侧为空时的说明：现价已在全部 EMA 之上/之下，另附「另一侧最近的一道」跳转入口。 */
private data class EmptySideHint(
    val title: String,
    val detail: String,
    val jumpLabel: String,
)

/**
 * 空侧提示（列表里 / 缩略图那一栏共用）。
 *
 * 空表本身是正确结果（急拉或急跌时现价会穿过全部 EMA），但只写一句「还没有」会被读成
 * 「取数坏了」。这里把判据说清，并把另一侧最近的一道做成可点入口 —— 用户点一下就能
 * 看到那一档的形态，不用自己切页签再翻。
 */
@Composable
private fun EmptySideBlock(
    hint: EmptySideHint,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val colors = MarketTheme.colors
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(hint.title, style = MaterialTheme.typography.labelMedium, color = colors.ink, textAlign = align)
        Spacer(Modifier.height(Spacing.Xxs))
        Text(hint.detail, style = MaterialTheme.typography.labelSmall, color = colors.muted, textAlign = align)
        Spacer(Modifier.height(Spacing.Xxs))
        TextAction(text = hint.jumpLabel, onClick = onJump, color = colors.ink, style = InlineActionStyle)
    }
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
    family: MaFamily,
    title: String,
    base: Double,
    /** 基准是真·实时价（false = 用的是末根收盘，得说清）。 */
    live: Boolean,
    tickSize: BigDecimal?,
    refreshing: Boolean,
    done: Int,
    total: Int,
    updatedAt: Long,
    /** 图上有几条本族划线（自动名的那批告警规则）：0 时不给「清空」入口。 */
    marked: Int,
    onRefresh: () -> Unit,
    onClearLines: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MarketTheme.colors
    // 一行放得下三个数值 + 两个操作吗（横屏 640dp 起算宽裕，竖屏 360dp 不够）
    val roomy = LocalConfiguration.current.screenWidthDp >= 480
    // 元信息：窄屏承不起「面板身份 + 完整刷新节奏」一长串（价格占掉第二行左半边了），
    // 就只留节奏与时刻 —— 身份本来也不用写，竖屏这两行上面就是「上方压力 / 下方支撑」页签
    val meta = if (roomy) {
        buildString {
            append("${family.label} · 压力支撑分析 · 每 1 分钟自动刷新")
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
            // 字号走「同行动作」口径：标的名是全大写拉丁（cap-height 只占 0.72em），
            // 用默认的 15sp 中文按钮会显得比 16sp 的标题还大（见 InlineActionStyle）
            TextAction(
                text = if (refreshing) "刷新中 $done/$total" else "刷新",
                onClick = { if (!refreshing) onRefresh() },
                color = if (refreshing) colors.muted else colors.ink,
                style = InlineActionStyle,
            )
            // 一键清空：只收面板自动名的那批线，用户手画的告警 / 划线一概不碰；
            // 画没了随时再点行补回来，所以不设二次确认。
            if (marked > 0) {
                Spacer(Modifier.width(Spacing.Sm))
                TextAction(
                    text = "清空划线",
                    onClick = onClearLines,
                    color = colors.ink,
                    style = InlineActionStyle,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                // ✕ 与「刷新」同一口径：三者在一行里，字号必须一套
                Text(text = "✕", style = InlineActionStyle, color = colors.ink)
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

/** 页签（竖屏在缩略图下方、横屏在右栏顶部）：两个等宽、各带条数 —— 切过去前就知道另一侧有几道。 */
@Composable
private fun SideTabs(side: Side, buckets: MaBuckets, onSelect: (Side) -> Unit) {
    SegmentedControl(
        options = listOf(Side.RESISTANCE, Side.SUPPORT),
        selected = side,
        labelOf = { s -> "${s.title} ${if (s == Side.RESISTANCE) buckets.resistance.size else buckets.support.size}" },
        onSelect = onSelect,
        modifier = Modifier.padding(horizontal = Spacing.Gutter),
    )
}

/**
 * 当前页签那一侧的条目（竖屏在下半屏、横屏在右半屏，同一份）。
 *
 * 行序直接用 `buckets` 里的原序：压力升序、支撑降序，两者都是**离现价由近到远**，
 * 所以第一条就是最近的一道。
 *
 * 点一行 = **选中**它（缩略图跟着换）。画线挪到缩略图那一栏的动作按钮 —— 选中与画线
 * 拆开后，想连着比较几档的形态就不必在图上落一串线了。
 */
@Composable
private fun TabbedColumn(
    family: MaFamily,
    side: Side,
    rows: List<MaLevelRow>,
    listState: LazyListState,
    state: DetailUiState,
    refOf: (MaLevelRow) -> MaLineRef?,
    selectedKey: String,
    onSelect: (MaLevelRow) -> Unit,
    /** 这一侧为空时的解释（现价已在全部均线之上/之下）；null = 没东西可解释。 */
    emptyHint: EmptySideHint?,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "head") {
            ColumnHead(
                family = family,
                side = side,
                rows = rows,
                marked = rows.count { refOf(it) != null },
                showTitle = false,
            )
        }
        items(rows, key = { lineKey(it.interval, it.period) }) { row ->
            MaRow(
                family = family,
                row = row,
                line = refOf(row),
                side = side,
                state = state,
                selected = selectedKey == lineKey(row.interval, row.period),
                onSelect = { onSelect(row) },
            )
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                if (emptyHint != null) {
                    EmptySideBlock(
                        hint = emptyHint,
                        onJump = onJump,
                        modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
                    )
                } else {
                    EmptyRow(emptySideText(side, family))
                }
            }
        }
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
    family: MaFamily,
    side: Side,
    rows: List<MaLevelRow>,
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
                    text = "最近 ${nearest.interval.label} · ${family.lineLabel(nearest.period)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        }
    }
}

/**
 * 一行 = 一个候选价位。整行可点（**选中**它，缩略图跟着换）。
 *
 * 两层状态互不包含：
 * · 「已画」= 这一档已有图上的告警线：告警红底 + 左缘 3dp 红条；
 * · 「选中」= 缩略图正在看的那一档：淡黄底 + 右缘 2dp 黄条。
 *
 * 行内的周期小方框**只是标注**，不可点 —— 点了会切周期并把面板关掉。
 */
@Composable
private fun MaRow(
    family: MaFamily,
    row: MaLevelRow,
    line: MaLineRef?,
    side: Side,
    state: DetailUiState,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MarketTheme.colors
    val slot = state.colorsOf(family)[row.period] ?: 0
    val dot = ChartLineColors[maRoleForSlot(family, slot).ordinal]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                when {
                    selected -> colors.accent.copy(alpha = 0.16f)
                    line != null -> colors.down.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                if (line != null) {
                    drawRect(color = colors.down, size = Size(3.dp.toPx(), size.height))
                }
                if (selected) {
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(size.width - 2.dp.toPx(), 0f),
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            }
            .padding(horizontal = Spacing.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            text = family.lineLabel(row.period),
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        IntervalBadge(row.interval.label)
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
    }
}

/** 缩略图默认可见根数 / 最小根数（上限是曲线全长）。 */
private const val SHAPE_DEFAULT_BARS = 120
private const val SHAPE_MIN_BARS = 16

/**
 * 缩略形态图那一栏（竖屏在上半屏、横屏在左半屏）。
 *
 * 收盘价淡灰 + 这一档均线（色槽同色），**共用一根纵轴** —— 要看的就是
 * 「均线相对价格是抬还是压、拐点在哪」，分开归一化会把这条信息抹掉。
 * 双指缩放可见根数、双击复位；「− / ＋」给没有多指或想微调的场景兜底。
 * 画线动作也在这里：选中一条 → 按按钮，图上落 / 对齐 / 删掉那条告警线。
 */
@Composable
private fun MaShapePane(
    family: MaFamily,
    row: MaLevelRow?,
    curve: MaCurve?,
    side: Side,
    line: MaLineRef?,
    color: Color,
    tickSize: BigDecimal?,
    /** 选中的那一档（变化时缩放回到默认值）。 */
    resetKey: String,
    /** 这一侧为空时的解释；有值就替代「点条目」那句通用提示。 */
    emptyHint: EmptySideHint? = null,
    onJump: () -> Unit = {},
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val colors = MarketTheme.colors
    Column(
        modifier = modifier
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs)
            .clip(RoundedCornerShape(Radius.Sm))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(Radius.Sm)),
    ) {
        if (row == null || curve == null) {
            Box(Modifier.fillMaxSize().padding(Spacing.Md), contentAlignment = Alignment.Center) {
                if (emptyHint != null) {
                    EmptySideBlock(hint = emptyHint, onJump = onJump, centered = true)
                } else {
                    Text(
                        text = "点列表里的条目，在这里看它的均线形态",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }
        val total = curve.values.size
        // 0 = 还没手动缩放过（用默认根数）；选中的档换了就回到默认
        var bars by remember(resetKey) { mutableIntStateOf(0) }
        val minBars = minOf(SHAPE_MIN_BARS, total)
        val defaultBars = minOf(SHAPE_DEFAULT_BARS, total)
        val shown = (if (bars <= 0) defaultBars else bars).coerceIn(minBars, total)

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.Sm, end = Spacing.Sm, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(
                text = family.lineLabel(row.period),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            IntervalBadge(row.interval.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = PriceFormatter.formatChange(row.gapPercent),
                style = MaterialTheme.typography.labelSmall,
                color = if (side == Side.RESISTANCE) colors.down else colors.up,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = formatPrice(row.value, tickSize),
                style = PriceTextStyle,
                color = colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(Spacing.Xs))
            Text(
                text = "${side.title} · 距现价",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
            )
        }
        MaCurveChart(
            curve = curve,
            color = color,
            bars = shown,
            onZoom = { scale ->
                val cur = if (bars <= 0) defaultBars else bars
                bars = (cur / scale).roundToInt().coerceIn(minBars, total)
            },
            onReset = { bars = 0 },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(color = colors.muted.copy(alpha = 0.5f), text = "收盘")
            Spacer(Modifier.width(Spacing.Xs))
            LegendSwatch(color = color, text = family.label)
            Spacer(Modifier.weight(1f))
            Text(
                text = "$shown / $total 根",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(Spacing.Xs))
            ZoomButton(text = "−", enabled = shown > minBars) {
                bars = (shown * 1.6f).roundToInt().coerceIn(minBars, total)
            }
            Spacer(Modifier.width(4.dp))
            ZoomButton(text = "＋", enabled = shown < total) {
                bars = (shown / 1.6f).roundToInt().coerceIn(minBars, total)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    line == null -> "图上未画告警线"
                    line.aligned -> "图上已画 ${formatPrice(line.price, tickSize)}"
                    else ->
                        "图上已画 ${formatPrice(line.price, tickSize)}（偏离 ${
                            PriceFormatter.formatChange(((line.price - row.value) / row.value) * 100)
                        }）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (line != null && !line.aligned) colors.down else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextAction(
                text = when {
                    line == null -> "画告警线"
                    line.aligned && line.enabled -> "删除告警线"
                    else -> "对齐到当前值"
                },
                onClick = onToggle,
                color = colors.ink,
                style = InlineActionStyle,
            )
        }
    }
}

/** 图例小横线 + 名字（收盘 / 均线）。 */
@Composable
private fun LegendSwatch(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 8.dp, height = 2.dp).background(color))
        Spacer(Modifier.width(3.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MarketTheme.colors.muted)
    }
}

/** 缩略图的「− / ＋」：给没有多指（或想一格一格调）的场景兜底。 */
@Composable
private fun ZoomButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.wash else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colors.ink else colors.muted,
        )
    }
}

/**
 * 缩略形态图本体：收盘（淡灰）+ 均线（色槽色），共用一根纵轴；
 * 双指缩放改**可见根数**（右端始终贴着最新一根），双击复位。
 */
@Composable
private fun MaCurveChart(
    curve: MaCurve,
    color: Color,
    /** 当前可见根数（已按上下限夹过）。 */
    bars: Int,
    onZoom: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    val total = curve.values.size
    val n = bars.coerceIn(1, total)
    val start = total - n
    Canvas(
        modifier = modifier
            // 只按 curve 重启手势：bars 一变就重启会把正在捏的这根手指打断
            .pointerInput(curve) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 0f && zoom != 1f) onZoom(zoom)
                }
            }
            .pointerInput(curve) {
                detectTapGestures(onDoubleTap = { onReset() })
            },
    ) {
        if (n < 2) return@Canvas
        val padX = 2.dp.toPx()
        val padY = 6.dp.toPx()
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in start until total) {
            val v = curve.values[i]
            val c = curve.closes[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
            if (c < lo) lo = c
            if (c > hi) hi = c
        }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val plotW = (size.width - padX * 2).coerceAtLeast(1f)
        val plotH = (size.height - padY * 2).coerceAtLeast(1f)
        val xAt = { i: Int -> padX + (i - start).toFloat() / (n - 1).toFloat() * plotW }
        val yAt = { v: Double -> (padY + plotH - ((v - lo) / span * plotH)).toFloat() }

        val closes = Path()
        val ema = Path()
        for (i in start until total) {
            val x = xAt(i)
            if (i == start) {
                closes.moveTo(x, yAt(curve.closes[i]))
                ema.moveTo(x, yAt(curve.values[i]))
            } else {
                closes.lineTo(x, yAt(curve.closes[i]))
                ema.lineTo(x, yAt(curve.values[i]))
            }
        }
        drawPath(
            path = closes,
            color = colors.muted.copy(alpha = 0.45f),
            style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round),
        )
        drawPath(
            path = ema,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = color,
            radius = 2.5.dp.toPx(),
            center = Offset(xAt(total - 1), yAt(curve.values[total - 1])),
        )
    }
}

/** 周期小方框：纯标注这一行属于哪个周期（曾经可点切周期，已撤）。 */
@Composable
private fun IntervalBadge(text: String) {
    val colors = MarketTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.Xs))
            .background(colors.washStrong)
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
 */
@Composable
private fun Footnotes(
    family: MaFamily,
    scan: MaScan,
    orphans: List<String>,
    tickSize: BigDecimal?,
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
                    append(scan.missing.take(6).joinToString(" / ") { "${it.interval.label}·${family.lineLabel(it.period)}" })
                    if (scan.missing.size > 6) append(" …")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.padding(top = if (scan.failures.isEmpty()) 0.dp else Spacing.Xxs),
            )
            Text(
                text = scan.missing.joinToString("\n") {
                    "${it.interval.label} 的 ${family.lineLabel(it.period)}：只取到 ${it.bars} 根，至少要 ${it.period} 根"
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
                    append("图上还有 ${orphans.size} 条 ${family.label} 划线不在当前配置里（档位或周期被删掉了）：")
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
