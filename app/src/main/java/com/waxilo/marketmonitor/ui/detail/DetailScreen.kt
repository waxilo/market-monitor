package com.waxilo.marketmonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.DataOrigin
import com.waxilo.marketmonitor.ui.chart.ChartModel
import com.waxilo.marketmonitor.ui.chart.KlineChart
import com.waxilo.marketmonitor.ui.chart.SubPaneKind
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.LabelValueRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel

/**
 * 详情页（PRD 4.1 第三级、4.2 图表）。
 * 图表高度固定，下面的统计与指标开关随页面滚动，避免小屏上蜡烛被压扁。
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
    val series = remember(state.candles, state.maPeriods, state.showBoll, state.subPane) {
        ChartModel.build(
            candles = state.candles,
            maPeriods = state.maPeriods,
            showBoll = state.showBoll,
            subPane = state.subPane,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Header(
            state = state,
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onToggleWatch = viewModel::toggleWatch,
        )
        val error = state.error
        when {
            error != null -> OfflineBanner(error)
            state.origin == DataOrigin.CACHE -> OfflineBanner("K 线来自本地缓存")
            else -> Unit
        }

        IntervalChips(
            options = state.intervals,
            selected = state.interval,
            onSelect = viewModel::selectInterval,
        )

        Box(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
            if (state.loadingCandles && state.candles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.candles.isEmpty()) {
                HintRow(
                    title = "没有取到 K 线",
                    subtitle = state.error ?: "换个周期或点右上角刷新试试",
                    actionLabel = "重新加载",
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                KlineChart(
                    series = series,
                    interval = state.interval,
                    tickSize = state.tickSize,
                    onLoadMore = viewModel::loadMore,
                )
            }
            if (state.loadingMore) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        IndicatorControls(
            maChoices = state.maChoices,
            activeMa = state.maPeriods,
            showBoll = state.showBoll,
            subPane = state.subPane,
            onToggleMa = viewModel::toggleMaPeriod,
            onToggleBoll = viewModel::toggleBoll,
            onSelectSubPane = viewModel::setSubPane,
        )
        ThinDivider()
        state.stats.forEach { LabelValueRow(label = it.label, value = it.value) }
        Text(
            text = "提示：切到后台后价格检测会停止，预警依赖前台保活",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val CHART_HEIGHT_DP = 360

@Composable
private fun Header(
    state: DetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    val watched = state.watched
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
            Text(state.price, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.End)
            ChangeText(state.changePercent)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        }
        IconButton(onClick = onToggleWatch) {
            Icon(
                imageVector = if (watched) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (watched) "取消自选" else "加为自选",
                tint = if (watched) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun IntervalChips(
    options: List<CandleInterval>,
    selected: CandleInterval,
    onSelect: (CandleInterval) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            Chip(
                text = option.label,
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun IndicatorControls(
    maChoices: List<Int>,
    activeMa: List<Int>,
    showBoll: Boolean,
    subPane: SubPaneKind,
    onToggleMa: (Int) -> Unit,
    onToggleBoll: () -> Unit,
    onSelectSubPane: (SubPaneKind) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            maChoices.forEach { period ->
                Chip(text = "MA$period", selected = period in activeMa, onClick = { onToggleMa(period) })
            }
            Chip(text = "BOLL", selected = showBoll, onClick = onToggleBoll)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "副图",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentPicker(
                options = SubPaneKind.entries.toList(),
                selected = subPane,
                labelOf = { it.label },
                onSelect = onSelectSubPane,
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
