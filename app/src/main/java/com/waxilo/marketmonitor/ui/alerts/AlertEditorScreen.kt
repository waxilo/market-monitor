package com.waxilo.marketmonitor.ui.alerts

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.FilterChip
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.Section
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import java.math.BigDecimal

/**
 * 规则编辑页（PRD FR-3.1）。
 *
 * 版面重做：旧版是十余个 OutlinedTextField 与两组 Button/OutlinedButton 拼成的长表单，
 * 字段之间只有默认间距，用户很难看出「哪些是一组」。
 * 这里按语义切成四个 [Section]：标的 → 条件 → 提醒 → 推送，
 * 每段有自己的小标题与细线边界；枚举项（触发条件、提醒方式）用方块标签而不是
 * 一选一按钮组，选中态由反色块表达，一行能放下全部选项。
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
    val colors = MarketTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        AppBar(
            title = if (ruleId == null) "新建预警" else "编辑预警",
            subtitle = if (ruleId == null) "为交易对设置触发条件" else form.name,
            onBack = onBack,
        )

        form.error?.let {
            com.waxilo.marketmonitor.ui.common.Banner(
                it,
                tone = com.waxilo.marketmonitor.ui.common.BannerTone.Error,
            )
        }

        Section(title = "交易对", trailing = "必填") {
            SegmentedControl(
                options = MarketType.entries.toList(),
                selected = form.market,
                labelOf = { it.label },
                onSelect = { market -> viewModel.on { it.copy(market = market) } },
                modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
            )
            Rule()
            FieldRow(
                label = "交易对代码",
                value = form.symbol,
                placeholder = "如 BTCUSDT",
                supporting = "当前价 ${hints.currentPrice}",
                onChange = { symbol -> viewModel.on { it.copy(symbol = symbol.uppercase()) } },
            )
            if (hints.quickPicks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    hints.quickPicks.forEach { pick ->
                        FilterChip(
                            text = pick,
                            selected = pick == form.symbol,
                            onClick = { viewModel.on { it.copy(symbol = pick) } },
                        )
                    }
                }
            }
            Rule()
            FieldRow(
                label = "规则名称",
                value = form.name,
                placeholder = "留空则用交易对名",
                onChange = { value -> viewModel.on { it.copy(name = value) } },
            )
        }

        Section(title = "触发条件") {
            ChipRow(
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
        }

        Section(title = "提醒方式") {
            ChipRow(
                options = AlertRepeatMode.entries.toList(),
                selected = form.repeatMode,
                labelOf = { it.label },
                onSelect = { mode -> viewModel.on { it.copy(repeatMode = mode) } },
            )
            if (form.usesCooldown) {
                FieldRow(
                    label = "冷却分钟数",
                    value = form.cooldownMinutes,
                    placeholder = "两次提醒的最小间隔",
                    numeric = true,
                    onChange = { value -> viewModel.on { it.copy(cooldownMinutes = value) } },
                )
            }
            Rule()
            SwitchRow("启用规则", form.enabled) { checked -> viewModel.on { it.copy(enabled = checked) } }
            SwitchRow("提示音", form.playSound) { checked -> viewModel.on { it.copy(playSound = checked) } }
            SwitchRow("振动", form.vibrate) { checked -> viewModel.on { it.copy(vibrate = checked) } }
        }

        Section(title = "Webhook 推送") {
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
        }

        // 底部动作区：保存是主操作（实心块），删除退到文字按钮，且与保存拉开足够距离
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radius.smShape)
                    .background(if (form.saving) colors.hairlineStrong else colors.ink)
                    .clickable(enabled = !form.saving) { viewModel.save(onBack) }
                    .padding(vertical = Spacing.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (form.saving) "保存中…" else "保存规则",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.paper,
                )
            }
            if (ruleId != null) {
                TextAction("删除", { viewModel.delete(onBack) }, color = colors.down)
            }
        }

        Text(
            text = "提醒依赖进程存活：请保留后台权限，杀掉进程后监测会停止。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Gutter)
                .padding(bottom = Spacing.Xl),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
    }
}

/** 条件参数：按当前选中的触发条件，只显示相关的那几个输入项。 */
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
    val hint = "当前价 $currentPrice" + (tickSize?.let { " · 价格需为 $it 的整数倍" } ?: "")
    Rule()
    when {
        form.needsThreshold -> FieldRow("目标价", form.threshold, "如 100000", hint, onChange = onThreshold)
        form.needsRange -> {
            FieldRow("区间下界", form.rangeLower, "低于此价触发", hint, onChange = onLower)
            Rule()
            FieldRow("区间上界", form.rangeUpper, "高于此价触发", onChange = onUpper)
        }

        form.needsPercent -> FieldRow(
            label = "涨跌幅",
            value = form.percent,
            placeholder = "按 24h 开盘价计算，填正数",
            numeric = true,
            onChange = onPercent,
        )
    }
}

/** Webhook 端点多选：未选表示推送到全部启用端点，这是 PRD 定义的默认语义。 */
@Composable
private fun WebhookPicker(
    endpoints: List<WebhookEndpoint>,
    selected: List<Long>,
    onToggle: (Long, Boolean) -> Unit,
) {
    val colors = MarketTheme.colors
    if (endpoints.isEmpty()) {
        Text(
            text = "尚未配置端点，可在预警页的「推送端点」里添加",
            modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        return
    }
    Text(
        text = if (selected.isEmpty()) "未选中：推送到全部启用端点"
        else "仅推送到选中的 ${selected.size} 个端点",
        modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
    )
    endpoints.forEach { endpoint ->
        val checked = endpoint.id in selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(endpoint.id, !checked) }
                .padding(start = Spacing.Xs, end = Spacing.Gutter, top = Spacing.Xxs, bottom = Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(endpoint.id, it) },
                colors = CheckboxDefaults.colors(checkedColor = colors.ink),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = endpoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = endpoint.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 「标签 + 可编辑值」行，底部一条细线。
 * 与设置页的 ValueRow 同构，但支持上下排布（标签在上、值在下），
 * 因为这里的值常带 supporting 说明文字，左右排布会被挤得很窄。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    supporting: String? = null,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    val colors = MarketTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(Spacing.Xxs))
        Box {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                ),
            )
        }
        if (supporting != null) {
            Spacer(Modifier.height(Spacing.Xxs))
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MarketTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
            ),
        )
    }
}

/** 枚举选项行：横向可滚的方块标签，选中态反色。 */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            FilterChip(
                text = labelOf(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}
