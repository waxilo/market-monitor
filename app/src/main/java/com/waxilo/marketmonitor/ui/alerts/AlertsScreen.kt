package com.waxilo.marketmonitor.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.ChangeText
import com.waxilo.marketmonitor.ui.common.HintRow
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
 * 规则页是**三段式扁平行**（标题 / 条件+距触发进度 / 元信息），纸底 + 发丝线分隔，
 * 与行情、搜索等列表页同一套视觉语言。手动规则与指标划线挂的线都在这里展示
 * （划线线带「划线」标记，只给开关与删除，编辑在详情页划线管理）。
 * **本页不提供创建入口**：新建预警从行情详情页的预警图标进入，那里有标的上下文。
 *
 * 整行可点进编辑页（手动规则），开关与删除就地操作。
 */
@Composable
fun AlertsScreen(
    onBack: (() -> Unit)? = null,
    onOpenDetail: (SymbolId) -> Unit,
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
                modifier = Modifier.width(176.dp),
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
                onClick = { row -> if (!row.indicator) onEditRule(row.rule.id) },
                onToggle = viewModel::setEnabled,
                onDelete = viewModel::delete,
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
) {
    if (rows.isEmpty()) {
        HintRow(
            title = "还没有预警规则",
            subtitle = "在行情详情页点预警图标可设目标价预警，点划线图标可管理指标划线",
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
 * 规则行：标题 / 条件+距触发进度 / 元信息 三段，扁平行 + 发丝线，与其余列表页同语言。
 *
 * 距离进度条把「现价离阈值还有多远」画出来——只有百分比文字时还得自己心算，
 * 一根条能立刻判断这条规则是「快到了」还是「还早」（满量程见 ViewModel）。
 * 删除挂在元信息区行尾而不是标题行：标题行已有开关，再挤一颗红色图标就是误删现场。
 *
 * 指标划线挂的线带「划线」标记：阈值跟随指标值，进编辑页改它是没有意义的，
 * 所以这类行整行不响应点击，管理入口在详情页的划线面板。
 */
@Composable
private fun RuleItem(
    row: AlertRuleRow,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !row.indicator, onClick = onClick)
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        // ---- 标题区 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.rule.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.indicator) {
                StatusPill(text = "划线", tone = PillTone.Neutral)
                Spacer(Modifier.width(Spacing.Xs))
            }
            StatusPill(
                text = if (row.rule.enabled) "监测中" else "已暂停",
                tone = if (row.rule.enabled) PillTone.Positive else PillTone.Neutral,
            )
            Switch(
                checked = row.rule.enabled,
                onCheckedChange = onToggle,
                // M3 开关默认尺寸在紧凑行里显得过大，整体缩到八成（触控区随之缩小）
                modifier = Modifier
                    .padding(start = Spacing.Xs)
                    .graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
            )
        }
        Spacer(Modifier.height(2.dp))
        Row {
            Text(
                text = "${row.rule.symbol} · ${row.rule.market.label}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "建于 ${row.created}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(Spacing.Xs))

        // ---- 条件区：条件 + 现价，下面跟距离与进度条 ----
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.condition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.repeat,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
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
                Spacer(Modifier.height(2.dp))
                ChangeText(row.changePercent)
            }
        }
        row.progress?.let { progress ->
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = row.distanceText.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (progress >= 1f) colors.accent else colors.muted,
            )
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(Radius.fullShape)
                    .background(colors.hairline),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(Radius.fullShape)
                        .background(colors.accent),
                )
            }
        }

        Spacer(Modifier.height(Spacing.Xs))
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
        Spacer(Modifier.height(Spacing.Xs))

        // ---- 元信息区：提醒方式一行，触发状态 + 删除一行 ----
        Text(
            text = row.notify,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.status,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除规则",
                    modifier = Modifier.size(16.dp),
                    tint = colors.muted,
                )
            }
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
