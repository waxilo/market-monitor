package com.waxilo.marketmonitor.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle

/**
 * 行情首页（PRD 4.3）：仅展示关注（自选）列表。市场切换 + WS 实时节流刷新。
 * 列表本身不轮询——WS 合并流会持续推进内存快照，每 300ms 合并一次渲染。
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

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = "行情监控",
            actions = {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "搜索交易对")
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新关注行情")
                }
                IconButton(onClick = onOpenAlerts) {
                    Icon(Icons.Default.Notifications, contentDescription = "价格预警")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentPicker(
                options = MarketType.entries.toList(),
                selected = state.market,
                labelOf = { it.label },
                onSelect = viewModel::selectMarket,
            )
        }
        if (state.offline) OfflineBanner("网络中断，正在展示最近一次缓存")
        state.error?.let { OfflineBanner(it) }

        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.rows.isEmpty() && state.loading -> CenteredSpinner()
                state.rows.isEmpty() -> EmptyState(onOpenSearch)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.id.storageKey }) { row ->
                        TickerRowItem(
                            row = row,
                            onClick = { onOpenDetail(row.id) },
                            onToggleWatch = { viewModel.toggleWatch(row.id) },
                        )
                        ThinDivider()
                    }
                    item(key = "footer") {
                        Text(
                            text = "共 ${state.rows.size} 个 · 计价币 ${state.quoteAsset}",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 行情行：左侧 symbol（主标题 + 副标题），右侧固定宽数字区（价格 + 涨跌幅右对齐）。
 * 价格用等宽 + 副标题并入成交额，三列对齐、可横向扫描。
 */
@Composable
private fun TickerRowItem(
    row: TickerRow,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.baseAsset,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.marketLabel} · 量 ${row.quoteVolume}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.width(120.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                row.price,
                style = PriceTextStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ChangeText(row.changePercent)
        }
        Spacer(Modifier.width(4.dp))
        Surface(
            modifier = Modifier.size(40.dp),
            color = if (row.watched) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = MaterialTheme.shapes.medium,
        ) {
            IconButton(onClick = onToggleWatch, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (row.watched) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (row.watched) "移出自选" else "加为自选",
                    tint = if (row.watched) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(onOpenSearch: () -> Unit) {
    HintRow(
        title = "还没有关注任何交易对",
        subtitle = "在搜索页搜索并点收藏，即可在此实时查看行情",
        actionLabel = "去搜索添加",
        onAction = onOpenSearch,
    )
}