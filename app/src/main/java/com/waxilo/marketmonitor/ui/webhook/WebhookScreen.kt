package com.waxilo.marketmonitor.ui.webhook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import com.waxilo.marketmonitor.domain.webhook.WebhookTemplate
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.OfflineBanner
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel
import kotlinx.coroutines.delay

/**
 * Webhook 端点管理页（PRD FR-4.1）。端点数量少，列表直接全量渲染，不做分页。
 */
@Composable
fun WebhookScreen(
    onBack: () -> Unit,
    viewModel: WebhookViewModel = appViewModel { WebhookViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("Webhook 推送", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = viewModel::openNew) {
                Icon(Icons.Default.Add, contentDescription = "新增端点")
            }
        }

        state.notice?.let { text ->
            OfflineBanner(
                text = text,
                modifier = Modifier.clickable { viewModel.consumeNotice() },
            )
            LaunchedEffect(text) {
                delay(NOTICE_MS)
                viewModel.consumeNotice()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("总开关", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (state.globalEnabled) "命中预警时向启用的端点 POST JSON" else "已关闭，所有端点都不会推送",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.globalEnabled, onCheckedChange = viewModel::setGlobalEnabled)
        }
        ThinDivider()

        if (state.endpoints.isEmpty()) {
            HintRow(
                title = "还没有推送端点",
                subtitle = "支持钉钉、飞书、Slack 等自定义机器人；地址含密钥，已加密存储",
                actionLabel = "添加第一个端点",
                onAction = viewModel::openNew,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.endpoints, key = { it.id }) { endpoint ->
                    EndpointItem(
                        endpoint = endpoint,
                        busy = state.busy,
                        onClick = { viewModel.openEditor(endpoint) },
                        onToggle = { checked -> viewModel.setEnabled(endpoint.id, checked) },
                        onTest = { viewModel.testExisting(endpoint) },
                        onDelete = { viewModel.delete(endpoint.id) },
                    )
                    ThinDivider()
                }
                item(key = "footer") {
                    Text(
                        text = "推送失败会在下一轮检测自动补发；${state.ruleCount} 条启用规则参与监测",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(endpoint.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = endpoint.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = endpoint.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = endpoint.bindingLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onTest, enabled = !busy) {
                Text(if (busy) "发送中…" else "测试发送", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onDelete) { Text("删除", style = MaterialTheme.typography.labelMedium) }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isNew) "新增端点" else "编辑端点") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                draft.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onChange { it.copy(name = value) } },
                    label = { Text("名称") },
                    placeholder = { Text("如 运维群机器人") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { value -> onChange { it.copy(url = value) } },
                    label = { Text("https 地址") },
                    placeholder = { Text("https://hooks.example.com/…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = draft.template,
                    onValueChange = { value -> onChange { it.copy(template = value) } },
                    label = { Text("JSON 模板（留空使用默认）") },
                    supportingText = { Text("留空即使用内置 payload") },
                    trailingIcon = {
                        TextButton(onClick = { onChange { it.copy(template = WebhookTemplate.DEFAULT_PAYLOAD) } }) {
                            Text("默认", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                TextButton(onClick = onTest, enabled = !busy) {
                    Text(if (busy) "发送中…" else "测试发送")
                }
                Text(
                    text = "可用变量：${WebhookTemplate.demoVariables().keys.joinToString(", ")}；带 Raw 的输出 JSON 裸值",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun WebhookEndpoint.bindingLabel(): String {
    val scope = if (boundRuleIds.isEmpty()) "全部规则" else "绑定 ${boundRuleIds.size} 条规则"
    val custom = if (template.isBlank()) "默认模板" else "自定义模板"
    return "$scope · $custom"
}

private const val NOTICE_MS = 4_000L
