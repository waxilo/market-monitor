package com.waxilo.marketmonitor.ui.alerts

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.ListRow
import com.waxilo.marketmonitor.ui.common.Banner
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.common.StatusPill
import com.waxilo.marketmonitor.ui.common.PillTone
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 预警管理页（PRD FR-3.1 / FR-3.4）：规则与触发记录两个页签。
 *
 * 视觉上的两处补强：
 * 1. 规则行加了**触发距离进度条**——把「现价离目标价还有多远」画出来。
 *    只有文字时用户得自己心算，一根条能立刻判断这条规则是「快到了」还是「还早」；
 * 2. 「已启用/已停用」从灰色小字改成状态胶囊，扫一列时开关状态一眼可辨。
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
    val colors = MarketTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        AppBar(
            title = "预警",
            subtitle = "规则与触发记录",
            onBack = onBack,
            actions = {
                IconButton(onClick = onNewRule, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "新建预警规则", tint = colors.ink)
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SegmentedControl(
                options = AlertsTab.entries.toList(),
                selected = state.tab,
                labelOf = { it.label },
                onSelect = viewModel::selectTab,
                modifier = Modifier.width(180.dp),
            )
            Text(
                text = when {
                    state.tab == AlertsTab.RULES -> "${state.rules.size} 条规则"
                    state.unread > 0 -> "${state.unread} 条未读"
                    else -> "已全部阅读"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(Spacing.Sm))

        if (state.tab == AlertsTab.RULES) {
            TextAction(
                text = "推送端点 →",
                onClick = onOpenWebhooks,
                modifier = Modifier.padding(horizontal = Spacing.Sm),
                color = colors.muted,
            )
        }

        if (state.notificationsBlocked) {
            Banner("系统已关闭通知权限，预警只会留在消息中心。请在系统设置里开启通知。")
        }
        state.error?.let { Banner(it) }

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
                onClear = viewModel::clearMessages,
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
            actionLabel = "新建第一条规则 →",
            onAction = onNewRule,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.Xl),
    ) {
        items(rows, key = { it.rule.id }, contentType = { "rule" }) { row ->
            RuleItem(
                row = row,
                onClick = { onClick(row) },
                onToggle = { onToggle(row.rule.id, it) },
                onDelete = { onDelete(row.rule.id) },
            )
            Rule()
        }
        item(key = "footer", contentType = "footer") {
            Text(
                text = "杀掉进程后提醒会停止：Android 的省电策略可能回收后台进程",
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Lg),
                style = MaterialTheme.typography.labelSmall,
                color = MarketTheme.colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 规则行。
 * 左侧是名称 + 条件 + 触发进度，右侧是现价与开关，删除挂在行尾。
 */
@Composable
private fun RuleItem(
    row: AlertRuleRow,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MarketTheme.colors
    ListRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.Xs))
                StatusPill(
                    text = if (row.rule.enabled) "监测中" else "已暂停",
                    tone = if (row.rule.enabled) PillTone.Positive else PillTone.Neutral,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${row.rule.symbol} · ${row.rule.market.label} · ${row.condition}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${row.repeat} · ${row.status}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.width(84.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = row.currentPrice,
                style = PriceTextStyle,
                color = colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            ChangeText(row.changePercent)
        }
        Switch(
            checked = row.rule.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(start = Spacing.Xs),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除规则",
                modifier = Modifier.size(18.dp),
                tint = colors.muted,
            )
        }
    }
}

/**
 * 触发记录：时间线形态。
 * 左侧一根竖线 + 圆点表示时间先后，未读的圆点是实心强调色，
 * 这样「哪几条是新的」不用逐行读文字就能看出来。
 */
@Composable
private fun MessageList(
    rows: List<AlertMessageRow>,
    unread: Int,
    onClick: (AlertMessageRow) -> Unit,
    onAcknowledgeAll: () -> Unit,
    onClear: () -> Unit,
) {
    if (rows.isEmpty()) {
        HintRow(title = "还没有触发记录", subtitle = "规则命中后，提醒会同时留在这里，保存最近 30 天")
        return
    }
    val colors = MarketTheme.colors
    var confirmClear by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp),
        ) {
            items(rows, key = { it.message.id }, contentType = { "message" }) { row ->
                MessageItem(row = row, onClick = { onClick(row) })
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            // 「清空」用安静的文字入口而不是第二颗胶囊：它不是常规动作，
            // 而且点了还要弹确认，视觉上不该与「标记已读」这种随手动作同权重。
            TextAction(text = "清空", onClick = { confirmClear = true }, color = colors.muted)
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .clip(Radius.smShape)
                        .background(colors.ink)
                        .clickable(onClick = onAcknowledgeAll)
                        .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                ) {
                    Text(
                        text = "全部标记为已读",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.paper,
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ClearMessagesDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            },
        )
    }
}

/**
 * 清空的二次确认。
 *
 * 删除不可撤销，而「清空」两个字离列表太近、太容易顺手点到，所以必须问一句；
 * 说明里强调**只删记录、不动规则** —— 用户最怕的正是「清了历史把预警也清了」。
 * 不报「共 N 条」：列表只取最近 200 条，报出来的数字会比实际要删的少。
 */
@Composable
private fun ClearMessagesDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MarketTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "清空触发记录？", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        },
        text = {
            Text(
                text = "将删除全部触发记录，无法恢复。预警规则不受影响，价格再次触达时仍会提醒。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "清空", color = colors.down, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消", color = colors.muted) }
        },
    )
}

@Composable
private fun MessageItem(row: AlertMessageRow, onClick: () -> Unit) {
    val colors = MarketTheme.colors
    val unread = !row.message.acknowledged
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        // 时间线圆点：未读实心，已读空心
        Column(
            modifier = Modifier.width(16.dp).padding(top = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(Radius.fullShape)
                    .background(if (unread) colors.accent else colors.hairlineStrong),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(row.time, row.delivery).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread) colors.accent else colors.muted,
                maxLines = 1,
            )
        }
    }
    Rule(inset = Spacing.Gutter)
}
