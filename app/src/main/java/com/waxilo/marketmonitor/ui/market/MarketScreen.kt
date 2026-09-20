package com.waxilo.marketmonitor.ui.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AnimatedBanner
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.ChangePill
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.ListRow
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SectionOverline
import com.waxilo.marketmonitor.ui.common.Sparkline
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.common.rememberPriceFlash
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Motion
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 行情首页（PRD 4.3）：仅展示关注（自选）列表。
 *
 * 版面结构模仿杂志目录：
 * 刊头（大标题 + 市场概览）→ 粗分隔线 → 条目流（每条左边币种名、中间迷你走势、右边数字）。
 * 列表本身不轮询——WS 合并流持续推进内存快照，每 500ms 合并一次渲染。
 */
@Composable
fun MarketScreen(
    onOpenDetail: (SymbolId) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
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
                IconButton(onClick = onOpenAlerts) {
                    Icon(Icons.Default.Notifications, contentDescription = "价格预警")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            },
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

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.rows.isEmpty() && state.loading -> CenteredSpinner()
                state.rows.isEmpty() -> EmptyState(onOpenSearch)
                else -> TickerList(rows = state.rows, onOpenDetail = onOpenDetail, onToggleWatch = viewModel::toggleWatch)
            }
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

@Composable
private fun TickerList(
    rows: List<TickerRow>,
    onOpenDetail: (SymbolId) -> Unit,
    onToggleWatch: (SymbolId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.Xl),
    ) {
        items(
            items = rows,
            key = { it.id.storageKey },
            contentType = { "ticker" },
        ) { row ->
            TickerRowItem(
                row = row,
                onClick = { onOpenDetail(row.id) },
                onToggleWatch = { onToggleWatch(row.id) },
                modifier = Modifier.animateItem(),
            )
            // 分隔线跟着条目走：这样滚动时线与内容一起移动，不会出现「线先到、内容后到」的错位
            Rule()
        }
        item(key = "footer", contentType = "footer") {
            Text(
                text = "数据来自币安公开接口 · 每 500ms 合并一次推送",
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickerRowItem(
    row: TickerRow,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 涨跌闪现：价格真正变化时行背景短暂铺一层极淡的绿/红再淡出（纯背景，不挤压列布局）
    val flash = rememberPriceFlash(row.changePercent)
    val menuOpen = remember { mutableStateOf(false) }
    val colors = MarketTheme.colors
    val priceColor by animateColorAsState(
        targetValue = colors.forChange(row.changePercent),
        animationSpec = tween(Motion.BaseMs),
        label = "rowPriceColor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(flash)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen.value = true }),
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
        DropdownMenu(
            expanded = menuOpen.value,
            onDismissRequest = { menuOpen.value = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (row.watched) "移除自选" else "加入自选") },
                onClick = {
                    menuOpen.value = false
                    onToggleWatch()
                },
            )
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
