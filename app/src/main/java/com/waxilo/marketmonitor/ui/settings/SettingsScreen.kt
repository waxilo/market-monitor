package com.waxilo.marketmonitor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.SegmentPicker
import com.waxilo.marketmonitor.ui.common.ThinDivider
import com.waxilo.marketmonitor.ui.common.appViewModel

/** 设置页（PRD 8）：计价币/默认市场、镜像域名、通知、预警轮询、更新检查与关于。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(title = "设置", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionTitle("通用")
                TextFieldRow(
                    label = "计价币",
                    value = state.quoteAsset,
                    hint = "USDT",
                    onValue = viewModel::setQuoteAsset,
                )
                SegmentRow("默认市场") {
                    SegmentPicker(
                        options = MarketType.entries.toList(),
                        selected = state.defaultMarket,
                        labelOf = { it.label },
                        onSelect = viewModel::setDefaultMarket,
                    )
                }
            }
            item {
                SectionTitle("备用域名镜像")
                TextFieldRow("现货 REST", state.spotRestMirror, "留空=官方域名", viewModel::setSpotRestMirror)
                TextFieldRow("合约 REST", state.futuresRestMirror, "留空=官方域名", viewModel::setFuturesRestMirror)
                TextFieldRow("WebSocket", state.wsMirror, "留空=官方域名", viewModel::setWsMirror)
                Text(
                    "镜像用于国内网络直连币安受限时兜底；现货大陆可用 data-api.binance.vision。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                SectionTitle("通知")
                SwitchRow("允许通知", state.notificationEnabled, viewModel::setNotificationEnabled)
                SwitchRow("声音提醒", state.soundEnabled, viewModel::setSoundEnabled)
                SwitchRow("振动", state.vibrateEnabled, viewModel::setVibrateEnabled)
                SwitchRow("Webhook 推送", state.webhookEnabled, viewModel::setWebhookEnabled)
            }
            item {
                SectionTitle("预警轮询")
                SegmentRow("轮询间隔") {
                    SegmentPicker(
                        options = listOf(30, 60, 120),
                        selected = state.alertPollingSeconds,
                        labelOf = { "${it}s" },
                        onSelect = viewModel::setAlertPollingSeconds,
                    )
                }
            }
            item {
                SectionTitle("更新")
                SwitchRow("启动时自动检查", state.autoUpdateCheck, viewModel::setAutoUpdateCheck)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("当前版本 ${state.versionName}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = viewModel::checkUpdate,
                        enabled = !state.updating,
                    ) { Text(if (state.updating) "检查中…" else "检查更新") }
                }
                state.updateError?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 无更新
                if (!state.updating && state.updateError == null && state.updateInfo == null && state.versionName.isNotBlank()) {
                    Text(
                        "(启动或手动检查后可在此看到结果)",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.updateInfo?.let { info ->
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("发现新版本 ${info.latestVersion}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            info.notes ?: info.releaseName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionTitle("关于")
                Text(
                    "行情监控 · 数据来自币安。本应用不构成投资建议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    ThinDivider()
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SegmentRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}