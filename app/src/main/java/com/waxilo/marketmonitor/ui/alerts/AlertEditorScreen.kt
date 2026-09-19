package com.waxilo.marketmonitor.ui.alerts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import java.math.BigDecimal

/**
 * 规则编辑页（PRD FR-3.1）。从详情页带交易对进入时 [presetSymbol] 已填好，
 * 从预警页新建则留空由用户填写或点选自选列表。
 */
@Composable
fun AlertEditorScreen(
    ruleId: Long?,
    presetMarket: MarketType,
    presetSymbol: String,
    onBack: () -> Unit,
    viewModel: AlertEditorViewModel = appViewModel(key = "alert-editor:${ruleId ?: presetSymbol}") {
        AlertEditorViewModel(it, ruleId, presetMarket, presetSymbol)
    },
) {
    val form by viewModel.state.collectAsStateWithLifecycle()
    val hints by viewModel.hints.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (ruleId == null) "新建预警规则" else "编辑预警规则",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        form.error?.let { OfflineBanner(it) }

        Field(
            label = "预警名称",
            value = form.name,
            placeholder = "留空则用交易对名",
            onValueChange = { value -> viewModel.on { state -> state.copy(name = value) } },
        )
        MarketAndSymbol(
            form = form,
            hints = hints,
            onMarket = { market -> viewModel.on { it.copy(market = market) } },
            onSymbol = { symbol -> viewModel.on { it.copy(symbol = symbol.uppercase()) } },
        )
        ChipRow(
            label = "触发条件",
            options = AlertCondition.entries.toList(),
            selected = form.condition,
            labelOf = { it.label },
            onSelect = { condition -> viewModel.on { it.copy(condition = condition) } },
        )
        ConditionFields(
            form = form,
            currentPrice = hints.currentPrice,
            tickSize = hints.tickSize,
            onThreshold = { value -> viewModel.on { it.copy(threshold = value) } },
            onLower = { value -> viewModel.on { it.copy(rangeLower = value) } },
            onUpper = { value -> viewModel.on { it.copy(rangeUpper = value) } },
            onPercent = { value -> viewModel.on { it.copy(percent = value) } },
        )
        ChipRow(
            label = "提醒方式",
            options = AlertRepeatMode.entries.toList(),
            selected = form.repeatMode,
            labelOf = { it.label },
            onSelect = { mode -> viewModel.on { it.copy(repeatMode = mode) } },
        )
        if (form.usesCooldown) {
            Field(
                label = "冷却分钟数",
                value = form.cooldownMinutes,
                placeholder = "两次提醒的最小间隔",
                numeric = true,
                onValueChange = { value -> viewModel.on { it.copy(cooldownMinutes = value) } },
            )
        }
        SwitchRow("启用规则", form.enabled) { checked -> viewModel.on { it.copy(enabled = checked) } }
        SwitchRow("提示音", form.playSound) { checked -> viewModel.on { it.copy(playSound = checked) } }
        SwitchRow("振动", form.vibrate) { checked -> viewModel.on { it.copy(vibrate = checked) } }
        WebhookPicker(
            endpoints = hints.endpoints,
            selected = form.webhookIds,
            onToggle = { id, checked ->
                viewModel.on { state ->
                    state.copy(
                        webhookIds = if (checked) (state.webhookIds + id).distinct()
                        else state.webhookIds - id,
                    )
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { viewModel.save(onBack) },
                enabled = !form.saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (form.saving) "保存中…" else "保存") }
            if (ruleId != null) {
                OutlinedButton(onClick = { viewModel.delete(onBack) }) { Text("删除") }
            }
        }
        Text(
            text = "提醒依赖进程存活：请保留后台权限，杀掉进程后监测会停止。",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarketAndSymbol(
    form: AlertEditorState,
    hints: EditorHints,
    onMarket: (MarketType) -> Unit,
    onSymbol: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("市场与交易对", style = MaterialTheme.typography.labelLarge)
        SegmentPicker(
            modifier = Modifier.padding(vertical = 6.dp),
            options = MarketType.entries.toList(),
            selected = form.market,
            labelOf = { it.label },
            onSelect = onMarket,
        )
        OutlinedTextField(
            value = form.symbol,
            onValueChange = { onSymbol(it.trim()) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("如 BTCUSDT") },
            supportingText = { Text("当前价 ${hints.currentPrice}") },
            singleLine = true,
        )
        if (hints.quickPicks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hints.quickPicks.forEach { pick ->
                    OutlinedButton(onClick = { onSymbol(pick) }) { Text(pick, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun ConditionFields(
    form: AlertEditorState,
    currentPrice: String,
    tickSize: BigDecimal?,
    onThreshold: (String) -> Unit,
    onLower: (String) -> Unit,
    onUpper: (String) -> Unit,
    onPercent: (String) -> Unit,
) {
    val hint = "当前价 $currentPrice" +
        (tickSize?.let { " · 价格需为 $it 的整数倍" } ?: "")
    when {
        form.needsThreshold -> Field("目标价格", form.threshold, "如 100000", onThreshold, supporting = hint)
        form.needsRange -> Column {
            Field("区间下界（可留空）", form.rangeLower, "低于此价触发", onLower, supporting = hint)
            Field("区间上界（可留空）", form.rangeUpper, "高于此价触发", onUpper)
        }

        form.needsPercent -> Field(
            label = "涨跌幅百分比",
            value = form.percent,
            placeholder = "按 24h 开盘价计算，填正数",
            onValueChange = onPercent,
            numeric = true,
        )
    }
}

@Composable
private fun WebhookPicker(
    endpoints: List<WebhookEndpoint>,
    selected: List<Long>,
    onToggle: (Long, Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Webhook 推送", style = MaterialTheme.typography.labelLarge)
        if (endpoints.isEmpty()) {
            Text(
                text = "尚未配置端点，可在预警页的发送图标里添加",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = if (selected.isEmpty()) "未选中：推送到全部启用端点" else "仅推送到选中的 ${selected.size} 个端点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        endpoints.forEach { endpoint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = endpoint.id in selected,
                    onCheckedChange = { checked -> onToggle(endpoint.id, checked) },
                )
                Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { supporting?.let { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
        ),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                if (option == selected) {
                    Button(onClick = { }) { Text(labelOf(option)) }
                } else {
                    OutlinedButton(onClick = { onSelect(option) }) { Text(labelOf(option)) }
                }
            }
        }
    }
}
