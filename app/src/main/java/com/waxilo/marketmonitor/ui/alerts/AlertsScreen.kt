package com.waxilo.marketmonitor.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel

/**
 * 预警管理页（PRD FR-3.1 / FR-3.4）：规则与触发记录两个页签。
 * 提醒由进程级引擎产生，本页只做配置与回看，所以离开页面不影响检测。
 */
@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    onOpenDetail: (SymbolId) -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onOpenWebhooks: () -> Unit,
    viewModel: AlertsViewModel = appViewModel { AlertsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = "价格预警",
            onBack = onBack,
            actions = {
                TextButton(onClick = onOpenWebhooks) { Text("推送端点") }
                IconButton(onClick = onNewRule) {
                    Icon(Icons.Default.Add, contentDescription = "新建预警规则")
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SegmentPicker(
                options = AlertsTab.entries.toList(),
                selected = state.tab,
                labelOf = { it.label },
                onSelect = viewModel::selectTab,
            )
            Text(
                text = if (state.tab == AlertsTab.RULES) "${state.rules.size} 条规则"
                else if (state.unread > 0) "${state.unread} 条未读" else "已全部阅读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.notificationsBlocked) {
            OfflineBanner("系统已关闭通知权限，预警只会留在消息中心。请在系统设置里为本应用开启通知。")
        }
        state.error?.let { OfflineBanner(it) }

        when (state.tab) {
            AlertsTab.RULES -> RuleList(
                rows = state.rules,
                onClick = { onEditRule(it.rule.id) },
                onToggle = viewModel::setEnabled,
                onDelete = viewModel::delete,
                onNewRule = onNewRule,
            )

            AlertsTab.MESSAGES -> MessageList(
                rows = state.messages,
                unread = state.unread,
                onClick = { row ->
                    viewModel.acknowledge(row.message.id)
                    onOpenDetail(SymbolId(row.message.market, row.message.symbol))
                },
                onAcknowledgeAll = viewModel::acknowledgeAll,
            )
        }
    }
}

@Composable
private fun RuleList(
    rows: List<AlertRuleRow>,
    onClick: (AlertRuleRow) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onNewRule: () -> Unit,
) {
    if (rows.isEmpty()) {
        HintRow(
            title = "还没有预警规则",
            subtitle = "为关心的交易对设置目标价，价格触达时在通知栏提醒",
            actionLabel = "新建第一条规则",
            onAction = onNewRule,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.rule.id }) { row ->
            RuleItem(
                row = row,
                onClick = { onClick(row) },
                onToggle = { onToggle(row.rule.id, it) },
                onDelete = { onDelete(row.rule.id) },
            )
            ThinDivider()
        }
        item(key = "footer") {
            Text(
                text = "杀掉进程后提醒会停止：Android 的省电策略可能回收后台进程",
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RuleItem(
    row: AlertRuleRow,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Text(row.rule.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${row.rule.symbol} · ${row.rule.market.label} · ${row.condition}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${row.repeat} · ${row.status}",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.width(88.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(row.currentPrice, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
            ChangeText(row.changePercent)
        }
        Switch(checked = row.rule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除规则",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageList(
    rows: List<AlertMessageRow>,
    unread: Int,
    onClick: (AlertMessageRow) -> Unit,
    onAcknowledgeAll: () -> Unit,
) {
    if (rows.isEmpty()) {
        HintRow(title = "还没有触发记录", subtitle = "规则命中后，提醒会同时留在这里，保存最近 30 天")
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.message.id }) { row ->
                MessageItem(row = row, onClick = { onClick(row) })
                ThinDivider()
            }
        }
        if (unread > 0) {
            TextButton(modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), onClick = onAcknowledgeAll) {
                Text("全部标记为已读")
            }
        }
    }
}

@Composable
private fun MessageItem(row: AlertMessageRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = row.summary,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = listOfNotNull(row.time, row.delivery).joinToString(" · "),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (row.message.acknowledged) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
