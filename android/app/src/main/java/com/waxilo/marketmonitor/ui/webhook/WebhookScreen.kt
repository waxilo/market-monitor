package com.waxilo.marketmonitor.ui.webhook

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import com.waxilo.marketmonitor.domain.webhook.WebhookTemplate
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.Banner
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.ListRow
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.StatusPill
import com.waxilo.marketmonitor.ui.common.PillTone
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Webhook 端点管理页（PRD FR-4.1）。端点数量少，列表直接全量渲染，不做分页。
 *
 * 每行把「启用状态、绑定范围、模板类型」都做成可见的状态胶囊——
 * 旧版这些信息挤在一行 labelSmall 里，扫读时很难判断某个端点是否真的生效。
 */
@Composable
fun WebhookScreen(
    onBack: () -> Unit,
    viewModel: WebhookViewModel = appViewModel { WebhookViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MarketTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        AppBar(
            title = "推送端点",
            subtitle = "命中预警时 POST JSON",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::openNew, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "新增端点", tint = colors.ink)
                }
            },
        )

        state.notice?.let { text ->
            Banner(
                text = text,
                modifier = Modifier.clickable { viewModel.consumeNotice() },
                tone = com.waxilo.marketmonitor.ui.common.BannerTone.Success,
            )
            LaunchedEffect(text) {
                delay(NOTICE_MS)
                viewModel.consumeNotice()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("总开关", style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(
                    text = if (state.globalEnabled) "命中预警时向启用的端点推送" else "已关闭，所有端点都不会推送",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            Switch(
                checked = state.globalEnabled,
                onCheckedChange = viewModel::setGlobalEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                ),
            )
        }
        Rule(inset = 0.dp, strong = true)

        if (state.endpoints.isEmpty()) {
            HintRow(
                title = "还没有推送端点",
                subtitle = "支持钉钉、飞书、Slack 等自定义机器人；地址含密钥，已加密存储",
                actionLabel = "添加第一个端点 →",
                onAction = viewModel::openNew,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.Xl),
            ) {
                items(state.endpoints, key = { it.id }, contentType = { "endpoint" }) { endpoint ->
                    EndpointItem(
                        endpoint = endpoint,
                        busy = state.busy,
                        onClick = { viewModel.openEditor(endpoint) },
                        onToggle = { checked -> viewModel.setEnabled(endpoint.id, checked) },
                        onTest = { viewModel.testExisting(endpoint) },
                        onDelete = { viewModel.delete(endpoint.id) },
                    )
                    Rule()
                }
                item(key = "footer", contentType = "footer") {
                    Text(
                        text = "推送失败会在下一轮检测自动补发 · ${state.ruleCount} 条启用规则参与监测",
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Lg),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    state.draft?.let { current ->
        EditorDialog(
            draft = current,
            busy = state.busy,
            onDismiss = viewModel::dismissEditor,
            onChange = viewModel::onDraft,
            onSave = viewModel::saveDraft,
            onTest = viewModel::testDraft,
        )
    }
}

@Composable
private fun EndpointItem(
    endpoint: WebhookEndpoint,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MarketTheme.colors
    ListRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = endpoint.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.Xs))
                StatusPill(
                    text = if (endpoint.enabled) "启用" else "停用",
                    tone = if (endpoint.enabled) PillTone.Positive else PillTone.Neutral,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = endpoint.url,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = endpoint.bindingLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
            Switch(
                checked = endpoint.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                ),
            )
            Row {
                TextAction(if (busy) "发送中…" else "测试", onTest, color = colors.muted)
                TextAction("删除", onDelete, color = colors.muted)
            }
        }
    }
}

@Composable
private fun EditorDialog(
    draft: WebhookDraft,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChange: ((WebhookDraft) -> WebhookDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = MarketTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = if (draft.isNew) "新增端点" else "编辑端点",
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 460.dp),
            ) {
                draft.error?.let {
                    Text(
                        text = it,
                        color = colors.down,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = Spacing.Xs),
                    )
                }
                DialogField(
                    label = "名称",
                    value = draft.name,
                    placeholder = "如 运维群机器人",
                    onChange = { value -> onChange { it.copy(name = value) } },
                )
                DialogField(
                    label = "HTTPS 地址",
                    value = draft.url,
                    placeholder = "https://hooks.example.com/…",
                    onChange = { value -> onChange { it.copy(url = value) } },
                )
                Text(
                    text = "JSON 模板（留空使用内置）",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(top = Spacing.Sm),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp)
                        .clip(Radius.smShape)
                        .background(colors.wash)
                        .padding(Spacing.Xs),
                ) {
                    if (draft.template.isEmpty()) {
                        Text(
                            text = "留空即使用内置 payload",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted.copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = draft.template,
                        onValueChange = { value -> onChange { it.copy(template = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.ink),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextAction("测试发送", onTest, color = colors.muted)
                    Spacer(Modifier.weight(1f))
                    TextAction(
                        "填入默认模板",
                        { onChange { it.copy(template = WebhookTemplate.DEFAULT_PAYLOAD) } },
                        color = colors.muted,
                    )
                }
                Text(
                    text = "可用变量：${WebhookTemplate.demoVariables().keys.joinToString(", ")}；带 Raw 的输出 JSON 裸值",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        },
        confirmButton = { TextAction("保存", onSave) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}

/** 对话框内字段：标签在上、值在下，与编辑页的 FieldRow 同构。 */
@Composable
private fun DialogField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    val colors = MarketTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.Sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        Spacer(Modifier.height(Spacing.Xxs))
        Box {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
            )
        }
        Spacer(Modifier.height(Spacing.Xs))
        Rule(inset = 0.dp)
    }
}

private fun WebhookEndpoint.bindingLabel(): String {
    val scope = if (boundRuleIds.isEmpty()) "全部规则" else "绑定 ${boundRuleIds.size} 条规则"
    val custom = if (template.isBlank()) "默认模板" else "自定义模板"
    return "$scope · $custom"
}

private const val NOTICE_MS = 4_000L
