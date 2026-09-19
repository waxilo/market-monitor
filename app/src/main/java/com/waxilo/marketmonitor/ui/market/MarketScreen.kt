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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.appViewModel

/**
 * 行情首页（PRD 4.1）：市场切换 + 自选/行情两个页签。
 * 列表本身不轮询——WS 合并流会持续推进内存快照。
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
        TitleBar(
            onRefresh = viewModel::refresh,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenAlerts = onOpenAlerts,
            refreshing = state.refreshing,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SegmentPicker(
                options = MarketType.entries.toList(),
                selected = state.market,
                labelOf = { it.label },
                onSelect = viewModel::selectMarket,
            )
            SegmentPicker(
                options = MarketTab.entries.toList(),
                selected = state.tab,
                labelOf = { it.label },
                onSelect = viewModel::selectTab,
            )
        }
        if (state.offline) OfflineBanner("网络中断，正在展示最近一次缓存")
        state.error?.let { OfflineBanner(it) }

        when {
            state.rows.isEmpty() && state.loading -> CenteredSpinner()
            state.rows.isEmpty() -> EmptyState(state.tab, onRefresh = viewModel::refresh)
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id.storageKey }) { row ->
                    TickerRowItem(
                        row = row,
                        onClick = { onOpenDetail(row.id) },
                        onToggleWatch = { viewModel.toggleWatch(row.id) },
                    )
                }
                item(key = "footer") {
                    Text(
                        text = "共 ${state.rows.size} 个 · 计价币 ${state.quoteAsset}",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleBar(
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
    refreshing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "行情监控",
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Default.Search, contentDescription = "搜索交易对")
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        }
        IconButton(onClick = onOpenAlerts) {
            Icon(Icons.Default.Notifications, contentDescription = "价格预警")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun TickerRowItem(
    row: TickerRow,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.baseAsset, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${row.id.symbol} · ${row.marketLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.width(104.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(row.price, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                ChangeText(row.changePercent)
            }
            IconButton(onClick = onToggleWatch, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (row.watched) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (row.watched) "移出自选" else "加为自选",
                    tint = if (row.watched) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            text = "成交额 ${row.quoteVolume}",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(tab: MarketTab, onRefresh: () -> Unit) {
    when (tab) {
        MarketTab.WATCHLIST -> HintRow(
            title = "还没有自选交易对",
            subtitle = "在行情页或搜索页点收藏，即可加入自选",
            actionLabel = "去刷新行情",
            onAction = onRefresh,
        )
        MarketTab.ALL -> HintRow(
            title = "暂无行情数据",
            subtitle = "首次启动需要拉取一次全市场快照",
            actionLabel = "立即刷新",
            onAction = onRefresh,
        )
    }
}
