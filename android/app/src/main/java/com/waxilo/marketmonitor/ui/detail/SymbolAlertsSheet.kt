package com.waxilo.marketmonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 详情页铃铛入口打开的「本标的预警」整页，从右往左滑入、盖住详情页
 * （进入动画由 DetailScreen 的 AnimatedVisibility 负责，这里只画内容）。
 *
 * 预警页不再提供创建入口后，「给这个标的加个预警」必须就地完成：
 * 顶栏右上角的「＋」打开新建规则页（标的已按当前图填好）。
 */
@Composable
fun SymbolAlertsPage(
    symbolId: SymbolId,
    manualRules: List<AlertRule>,
    onDismiss: () -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onToggleRule: (Long, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        AppBar(
            title = "预警",
            subtitle = symbolId.symbol,
            onBack = onDismiss,
            actions = {
                IconButton(onClick = onNewRule, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "为该标的新建预警",
                        tint = colors.ink,
                    )
                }
            },
        )
        Rule()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.Xl),
        ) {
            if (manualRules.isEmpty()) {
                Text(
                    text = "这个标的还没有任何预警。点右上角「＋」设一条目标价预警。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Xl),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }

            manualRules.forEach { rule ->
                SheetRuleRow(
                    rule = rule,
                    onClick = { onEditRule(rule.id) },
                    onToggle = { onToggleRule(rule.id, it) },
                    onDelete = { onDeleteRule(rule.id) },
                )
                Rule(inset = Spacing.Gutter)
            }
        }
    }
}

@Composable
private fun SheetRuleRow(
    rule: AlertRule,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MarketTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AlertText.conditionLabel(rule),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    rule.name.takeIf { it.isNotBlank() },
                    AlertText.repeatLabel(rule),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除预警",
                modifier = Modifier.size(16.dp),
                tint = colors.muted,
            )
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            // 与预警页同款：M3 开关默认尺寸在紧凑行里过大，整体缩到八成
            modifier = Modifier
                .padding(start = Spacing.Xs)
                .graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
        )
    }
}
