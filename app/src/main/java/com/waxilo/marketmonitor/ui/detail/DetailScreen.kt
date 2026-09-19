package com.waxilo.marketmonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.LabelValueRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel

/**
 * 详情页（PRD 4.1 第三级）。K 线画布在图表引擎任务里接入，
 * 这里先把数据、周期切换与翻页跑通，图表位置用等高的占位框保留。
 */
@Composable
fun DetailScreen(
    symbolId: SymbolId,
    onBack: () -> Unit,
    viewModel: DetailViewModel = appViewModel(key = "detail:${symbolId.storageKey}") {
        DetailViewModel(it, symbolId)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            state = state,
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onToggleWatch = viewModel::toggleWatch,
        )
        state.error?.let { OfflineBanner(it) }
        if (state.origin == DataOrigin.CACHE && state.error == null) {
            OfflineBanner("K 线来自本地缓存")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.intervals.forEach { option ->
                val selected = option == state.interval
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        })
                        .clickable { viewModel.selectInterval(option) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        ChartSlot(
            state = state,
            onRetry = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
        )
        ThinDivider()

        state.stats.forEach { item ->
            LabelValueRow(label = item.label, value = item.value)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(
    state: DetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${state.id.symbol} · ${state.id.market.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.price,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.End,
            )
            ChangeText(state.changePercent)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        }
        IconButton(onClick = onToggleWatch) {
            Icon(
                imageVector = if (state.watched) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (state.watched) "移出自选" else "加为自选",
                tint = if (state.watched) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 图表引擎接入前的占位：先把「有多少根、能否翻页」这两件事验证掉。 */
@Composable
private fun ChartSlot(state: DetailUiState, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.loadingCandles -> CircularProgressIndicator()
            state.candles.isEmpty() -> HintRow(
                title = "没有取到 K 线",
                subtitle = state.error ?: "换个周期或下拉刷新试试",
                actionLabel = "重新加载",
                onAction = onRetry,
            )
            else -> HintRow(
                title = "${state.interval.label} · ${state.candles.size} 根蜡烛",
                subtitle = if (state.hasMore) "图表引擎接入后在此渲染蜡烛与指标" else "已取到最早的历史数据",
                actionLabel = if (state.hasMore) "加载更早" else null,
                onAction = if (state.hasMore) onLoadMore else null,
            )
        }
    }
}
