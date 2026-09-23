package com.waxilo.marketmonitor.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.ChangePill
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.ListRow
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.market.MarketSwitcher
import com.waxilo.marketmonitor.ui.market.TickerRow
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 搜索交易对（PRD FR-1.2）。
 *
 * 版面取舍：搜索框不再是 Material 的 OutlinedTextField——它自带边框、浮动标签与
 * 56dp 的固有高度，在「搜索即一切」的页面里显得笨重。这里改成**无边框内联输入行**：
 * 放大镜 + 裸文本域 + 清空键，底下一条细线，视觉上更像一个搜索栏而不是一个表单字段。
 * 同时返回键并入同一行，省掉一整条标题栏的高度。
 */
@Composable
fun SearchScreen(
    onOpenDetail: (SymbolId) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = appViewModel { SearchViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MarketTheme.colors.paper)) {
        SearchField(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onBack = onBack,
        )

        MarketSwitcher(
            selected = state.market,
            onSelect = viewModel::selectMarket,
            modifier = Modifier.padding(horizontal = Spacing.Gutter),
        )

        if (state.rows.isEmpty()) {
            HintRow(
                title = state.emptyReason ?: "输入币种或交易对开始搜索",
                subtitle = if (state.query.isEmpty()) "支持代码模糊匹配，如 BTC、ETH、SOL" else null,
            )
        } else {
            Text(
                text = "${state.rows.size} 个结果",
                modifier = Modifier.padding(
                    start = Spacing.Gutter,
                    top = Spacing.Sm,
                    bottom = Spacing.Xs,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MarketTheme.colors.muted,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.Xl),
            ) {
                items(state.rows, key = { it.id.storageKey }, contentType = { "result" }) { row ->
                    ResultRow(
                        row = row,
                        onClick = { onOpenDetail(row.id) },
                        onToggleWatch = { viewModel.toggleWatch(row.id) },
                    )
                    Rule()
                }
            }
        }
    }
}

/** 搜索栏：返回键 + 无边框输入 + 清空键，底部一条细底线作为唯一结构线。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MarketTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Xs, end = Spacing.Md, top = Spacing.Xs, bottom = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.ink,
                )
            }
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.muted,
            )
            Spacer(Modifier.width(Spacing.Xs))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索币种或交易对",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.muted,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(16.dp),
                        tint = colors.muted,
                    )
                }
            }
        }
        Rule(inset = 0.dp)
    }
}

@Composable
private fun ResultRow(
    row: TickerRow,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    val colors = MarketTheme.colors
    ListRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.baseAsset,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.id.symbol} · ${row.marketLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.price != PriceFormatter.NO_DATA) {
            Column(
                modifier = Modifier.width(92.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = row.price,
                    style = PriceTextStyle,
                    color = colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                ChangePill(row.changePercent)
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(Radius.fullShape)
                .clickable(onClick = onToggleWatch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (row.watched) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (row.watched) "移出自选" else "加为自选",
                modifier = Modifier.size(20.dp),
                tint = if (row.watched) colors.accent else colors.muted,
            )
        }
    }
}
