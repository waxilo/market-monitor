package com.waxilo.marketmonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.alert.IndicatorKind
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
import com.waxilo.marketmonitor.domain.alert.LineMember
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.FilterChip
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.SegmentedControl
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Spacing

/**
 * 详情页铃铛入口打开的「本标的预警」整页，从右往左滑入、盖住详情页
 * （进入动画由 DetailScreen 的 AnimatedVisibility 负责，这里只画内容）。
 *
 * 预警页不再提供创建入口后，「给这个标的加个预警」必须就地完成：
 * 顶栏右上角的「＋」打开新建规则页（标的已按当前图填好）。
 * 指标划线不在此列——它有自己的顶栏入口（点图标直接进 [IndicatorLineDialog]）。
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

/**
 * 划线编辑对话框：顶栏「划线」图标点开直接进这里（有则回显编辑、无则新建），
 * 内容依次为 K 线周期 → 指标周期 → 这条线怎么用 → 启用开关。
 *
 * **一个标的至多一条划线**：保存即替换已有条目（见 DetailViewModel.saveIndicatorLine），
 * 所以不再需要列表——图标就是「编辑这一条」。已有的线可在对话框里用开关停用：
 * 关闭后引擎与图上都把它当不存在（配置原样保留，重开即恢复），取代旧的删除入口。
 * 均线保持**多选**：预警模式下整个选择集合成一个「均线带」——引擎每轮取现价上方
 * 最近均线挂上破、下方最近挂下破，穿越后自动换锚（见 AlertEngine.syncBandRules）；
 * 指标线模式下同样合成一个集合，只是不挂规则：图上只画现价上/下最近的两条灰色
 * 锚点水平线，穿越后同样换锚（见 AlertEngine.syncBandDisplay）。
 */
@Composable
fun IndicatorLineDialog(
    symbolId: SymbolId,
    initial: IndicatorLine?,
    onDismiss: () -> Unit,
    onSave: (IndicatorLine) -> Unit,
) {
    val colors = MarketTheme.colors
    val initialMembers = initial?.members.orEmpty()
    // 多选状态以逗号连接的字符串保存，旋转屏幕后仍能恢复。
    // 必须 distinct：均线带每个 K 线周期都有多个成员，逐成员拼接会把同一周期重复 N 遍，
    // 编辑保存时重复项会乘进 members，一条线凭空膨胀出成倍的重复成员
    var intervalKeys by rememberSaveable {
        mutableStateOf(
            if (initialMembers.isNotEmpty()) {
                initialMembers.map { it.interval.storageKey }.distinct().joinToString(",")
            } else {
                (initial?.interval ?: DEFAULT_LINE_INTERVAL).storageKey
            },
        )
    }
    var maPeriodKeys by rememberSaveable {
        mutableStateOf(
            if (initialMembers.isNotEmpty()) {
                initialMembers.map { it.maPeriod.toString() }.distinct().joinToString(",")
            } else {
                (initial?.maPeriod?.takeIf { it > 0 } ?: 10).toString()
            },
        )
    }
    var mode by rememberSaveable { mutableStateOf(initial?.alertMode ?: LineAlertMode.OFF) }
    // 仅编辑已有线时提供停用开关；新建的线默认启用
    var enabled by rememberSaveable { mutableStateOf(initial?.enabled ?: true) }

    val intervals = intervalKeys.split(',')
        .mapNotNull { key -> CandleInterval.fromStorageKey(key) }
        .sortedBy { CandleInterval.quickPickPresets.indexOf(it) }
    val maPeriods = maPeriodKeys.split(',')
        .mapNotNull { it.toIntOrNull() }
        .sortedBy { MA_CHOICES.indexOf(it) }

    fun toggleInterval(preset: CandleInterval) {
        val next = intervals.toMutableList()
        if (!next.remove(preset)) next.add(preset)
        if (next.isEmpty()) return
        intervalKeys = next.joinToString(",") { it.storageKey }
    }

    fun toggleMaPeriod(period: Int) {
        val next = maPeriods.toMutableList()
        if (!next.remove(period)) next.add(period)
        if (next.isEmpty()) return
        maPeriodKeys = next.joinToString(",")
    }

    val alerting = mode != LineAlertMode.OFF
    val line = IndicatorLine(
        id = initial?.id ?: 0L,
        market = symbolId.market,
        symbol = symbolId.symbol,
        kind = IndicatorKind.MA_BAND,
        interval = intervals.first(),
        alertMode = if (alerting) LineAlertMode.EVERY_CROSS else LineAlertMode.OFF,
        members = intervals.flatMap { iv -> maPeriods.map { LineMember(iv, it) } },
        enabled = enabled,
        createdAt = initial?.createdAt ?: System.currentTimeMillis(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = if (initial == null) "新建指标划线" else "编辑指标划线",
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = "K 线周期（可多选）",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    CandleInterval.quickPickPresets.forEach { preset ->
                        FilterChip(
                            text = preset.label,
                            selected = preset in intervals,
                            onClick = { toggleInterval(preset) },
                            compact = true,
                        )
                    }
                }

                Text(
                    text = "指标周期（可多选）",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    MA_CHOICES.forEach { period ->
                        FilterChip(
                            text = "MA$period",
                            selected = period in maPeriods,
                            onClick = { toggleMaPeriod(period) },
                            compact = true,
                        )
                    }
                }

                Text(text = "这条线怎么用", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                SegmentedControl(
                    options = listOf(LineAlertMode.OFF, LineAlertMode.EVERY_CROSS),
                    selected = if (mode == LineAlertMode.ONCE) LineAlertMode.EVERY_CROSS else mode,
                    labelOf = {
                        when (it) {
                            LineAlertMode.OFF -> "指标线"
                            LineAlertMode.ONCE -> "单次预警"
                            LineAlertMode.EVERY_CROSS -> "上下破预警"
                        }
                    },
                    onSelect = { mode = it },
                )
                Text(
                    text = if (!alerting) {
                        "把所有选中周期的均线合成一个集合：现价上方最近一条画灰色上破参考线、" +
                            "下方最近一条画灰色下破参考线，穿越后自动换锚下一条并持续跟随，不响。"
                    } else {
                        "把所有选中周期的均线值合成一个集合：现价上方最近一条均线划上破线、" +
                            "下方最近一条划下破线，穿越后自动换锚下一条并持续跟随。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )

                if (initial != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用这条划线",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.ink,
                            )
                            Text(
                                text = "关闭后图与预警都忽略这条线，配置保留，随时可再开",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            // 与预警行同款：M3 开关默认尺寸在紧凑行里过大，整体缩到八成
                            modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(line) }) {
                Text(text = "保存", color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消", color = colors.muted) }
        },
    )
}

/** 新建时的默认 K 线周期：1h —— 与旧版条目默认一致。 */
private val DEFAULT_LINE_INTERVAL: CandleInterval = CandleInterval.of(OfficialInterval.H1)
