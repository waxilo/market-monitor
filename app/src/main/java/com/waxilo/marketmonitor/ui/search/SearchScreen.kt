package com.waxilo.marketmonitor.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.market.TickerRow

/** 搜索交易对（PRD FR-1.2）：现货/合约切换后在本地标的集合内过滤。 */
@Composable
fun SearchScreen(
    onOpenDetail: (SymbolId) -> Unit,
    viewModel: SearchViewModel = appViewModel { SearchViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("输入币种或交易对，如 BTC / BTCUSDT") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentPicker(
                options = MarketType.entries.toList(),
                selected = state.market,
                labelOf = { it.label },
                onSelect = viewModel::selectMarket,
            )
            Text(
                text = "${state.rows.size} 个结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.rows.isEmpty()) {
            HintRow(title = state.emptyReason ?: "开始输入以搜索交易对")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id.storageKey }) { row ->
                    ResultRow(
                        row = row,
                        onClick = { onOpenDetail(row.id) },
                        onToggleWatch = { viewModel.toggleWatch(row.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    row: TickerRow,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.baseAsset, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${row.id.symbol} · ${row.marketLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.price != PriceFormatter.NO_DATA) {
                Column(modifier = Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                    Text(row.price, style = MaterialTheme.typography.bodyLarge)
                    ChangeText(row.changePercent)
                }
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
    }
}
