package com.waxilo.marketmonitor.ui.settings

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.repository.ThemeMode
import com.waxilo.marketmonitor.domain.repository.UpdateInfo
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.Section
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.common.StatusPill
import com.waxilo.marketmonitor.ui.common.PillTone
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 设置页（PRD 8）。
 *
 * 版面重做：旧版每个输入项都是一个 OutlinedTextField（自带边框 + 浮动标签 + 56dp 高），
 * 一屏下来边框把页面切得七零八落，且「设置」本质上不是表单——用户是来扫一眼
 * 当前值的，不是来填表的。所以这里改成：
 *
 * - **标签在左、当前值在右**的一行式布局，值是可直接编辑的裸文本；
 * - 分组用 [Section] 的小标题 + 细线划分，不再用填充卡片；
 * - 布尔项用开关，枚举项用分段控件，各司其职。
 *
 * 这样一屏能放下所有设置项，「当前是什么」一目了然。
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MarketTheme.colors.paper)) {
        AppBar(title = "设置", subtitle = "本地存储 · 无账号", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.Xl),
        ) {
            item {
                Section(title = "外观") {
                    SegmentRow("主题") {
                        SegmentedControl(
                            options = ThemeMode.entries.toList(),
                            selected = state.themeMode,
                            labelOf = { it.shortLabel },
                            onSelect = viewModel::setThemeMode,
                            fillWidth = false,
                        )
                    }
                }
            }

            item {
                Section(title = "行情") {
                    ValueRow(
                        label = "计价币",
                        value = state.quoteAsset,
                        placeholder = "USDT",
                        onValue = viewModel::setQuoteAsset,
                    )
                }
            }

            item {
                Section(title = "通知") {
                    SwitchRow("允许通知", state.notificationEnabled, viewModel::setNotificationEnabled)
                    Rule()
                    SwitchRow("声音提醒", state.soundEnabled, viewModel::setSoundEnabled)
                    Rule()
                    SwitchRow("振动", state.vibrateEnabled, viewModel::setVibrateEnabled)
                }
            }

            item {
                Section(title = "预警轮询") {
                    SegmentRow("轮询间隔") {
                        SegmentedControl(
                            options = listOf(5, 10, 30),
                            selected = state.alertPollingSeconds,
                            labelOf = { "${it}s" },
                            onSelect = viewModel::setAlertPollingSeconds,
                        )
                    }
                }
            }

            item {
                Section(title = "更新") {
                    SwitchRow("启动时自动检查", state.autoUpdateCheck, viewModel::setAutoUpdateCheck)
                    Rule()
                    DropdownRow(
                        label = "下载加速站",
                        options = UpdateMirror.entries.toList(),
                        selected = state.updateMirror,
                        labelOf = { it.label },
                        onSelect = viewModel::setUpdateMirror,
                    )
                    Text(
                        text = "加速站同时用于检查更新与下载；某一站不通时会自动回退到直连和其他站。开 VPN 检查更新报 403，就是 GitHub 拒了你的出口 IP，选一个加速站即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MarketTheme.colors.muted,
                        modifier = Modifier.padding(
                            start = Spacing.Gutter,
                            end = Spacing.Gutter,
                            bottom = Spacing.Xs,
                        ),
                    )
                    Rule()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前版本 ${state.versionName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MarketTheme.colors.ink,
                            )
                            Text(
                                text = "从 GitHub Releases 检查并安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = MarketTheme.colors.muted,
                            )
                        }
                        if (state.updating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MarketTheme.colors.muted,
                            )
                        } else {
                            TextAction("检查更新", viewModel::checkUpdate)
                        }
                    }
                    state.updateError?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(
                                start = Spacing.Gutter,
                                end = Spacing.Gutter,
                                bottom = Spacing.Sm,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MarketTheme.colors.down,
                        )
                    }
                    state.updateInfo?.let { info ->
                        Column(modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "发现新版本 ${info.latestVersion}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MarketTheme.colors.ink,
                                )
                                Spacer(Modifier.width(Spacing.Xs))
                                StatusPill("可更新", PillTone.Positive)
                            }
                            Text(
                                text = info.notes ?: info.releaseName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MarketTheme.colors.muted,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                            UpdateActions(
                                info = info,
                                progress = state.downloadProgress,
                                downloaded = state.downloadedApk != null,
                                needPermission = state.needInstallPermission,
                                onDownload = viewModel::downloadUpdate,
                                onInstall = viewModel::installDownloaded,
                                onGrantPermission = viewModel::openInstallPermissionSettings,
                            )
                        }
                    }
                }
            }

            item {
                Section(title = "关于") {
                    Text(
                        text = "行情监控 · 现货数据来自币安、永续合约来自 Aster 的公开接口。本应用不构成投资建议。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MarketTheme.colors.muted,
                        modifier = Modifier.padding(
                            horizontal = Spacing.Gutter,
                            vertical = Spacing.Sm,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 「标签 —— 可编辑值」一行。
 *
 * 值用无边框 BasicTextField 而不是 OutlinedTextField：设置里的值通常是短字符串，
 * 边框不会带来任何可读性收益，只会让一列值看起来像一堆输入框。聚焦时给整行加一条
 * 底边线来表示「正在编辑」，比常驻边框克制得多。
 */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    valueWidth: Dp = 160.dp,
) {
    val colors = MarketTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Box(modifier = Modifier.width(valueWidth)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.labelMedium.copy(color = colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(colors.ink),
            )
        }
    }
}

@Composable
private fun SegmentRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MarketTheme.colors.ink,
        )
        Box(modifier = Modifier.width(168.dp)) { content() }
    }
}

/**
 * 只读下拉框：标签在左、当前值在右，点开选一个。
 *
 * 没用 M3 的 `ExposedDropdownMenuBox`——那是一只自带边框 + 浮动标签的输入框，
 * 而本页刻意不留边框（见文件头）。也没用分段控件：加速站有 5 个选项且标签长短不一，
 * 排成一行会把值挤到看不清。
 */
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = MarketTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = labelOf(selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "展开选项",
                    modifier = Modifier.size(20.dp),
                    tint = colors.muted,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        trailingIcon = {
                            if (option == selected) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 更新操作区：下载 → 校验 → 安装。
 *
 * 为什么不是「一个按钮全自动跑完」：Android 的安装动作要有 `REQUEST_INSTALL_PACKAGES`
 * 权限，而该权限**无法在应用内静默申请**，必须跳系统设置页让用户手动打开。所以这里
 * 把流程拆成两个可中断的步骤（下载 / 安装），一旦安装没起来就切到「去授权」引导态 ——
 * 用户从系统设置回来后再点一次「安装」即可，不必重新下载。
 *
 * 进度用 `LinearProgressIndicator` + 百分比数字：下载 APK 是十几 MB 的等待，
 * 没有百分比用户会以为卡死。
 */
@Composable
private fun UpdateActions(
    info: UpdateInfo,
    progress: Float?,
    downloaded: Boolean,
    needPermission: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val colors = MarketTheme.colors
    Column(modifier = Modifier.padding(top = Spacing.Xs)) {
        when {
            // 正在下载：进度条 + 百分比
            progress != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(2.dp),
                        color = colors.accent,
                        trackColor = colors.hairline,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.width(Spacing.Sm))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                    )
                }
                Text(
                    text = "正在下载 ${info.apkName}",
                    modifier = Modifier.padding(top = Spacing.Xxs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 已下载但缺安装权限：引导去系统设置
            needPermission -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "需要「安装未知应用」权限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink,
                        )
                        Text(
                            text = "下载已完成，授权后回来点「安装」即可",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                    TextAction("去授权", onGrantPermission)
                }
                TextAction("重新安装", onInstall, modifier = Modifier.padding(top = Spacing.Xxs))
            }

            // 已下载：直接装
            downloaded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已下载 ${info.apkName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink,
                        )
                        Text(
                            text = "校验通过，可以安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                    TextAction("安装", onInstall)
                }
            }

            // 初始态
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.apkName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextAction("下载并安装", onDownload)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val colors = MarketTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Xs),
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
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
            ),
        )
    }
}
