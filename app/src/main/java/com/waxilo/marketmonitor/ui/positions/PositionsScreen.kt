package com.waxilo.marketmonitor.ui.positions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.model.Position
import com.waxilo.marketmonitor.domain.model.PositionSide
import com.waxilo.marketmonitor.domain.model.SpotBalance
import com.waxilo.marketmonitor.ui.common.AnimatedBanner
import com.waxilo.marketmonitor.ui.common.AppBar
import com.waxilo.marketmonitor.ui.common.BannerTone
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.common.MetricCell
import com.waxilo.marketmonitor.ui.common.PillTone
import com.waxilo.marketmonitor.ui.common.Rule
import com.waxilo.marketmonitor.ui.common.StatusPill
import com.waxilo.marketmonitor.ui.common.TextAction
import com.waxilo.marketmonitor.ui.common.appViewModel
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.Spacing
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的仓位（币安永续合约 + 现货）。
 *
 * 合约行版式沿用预警列表重排后的「三段式扁平行」：一行标的名 + 方向杠杆，
 * 下面两行小字把数量/名义/开仓/标记/强平一次看全，不给字段藏进二级界面。
 * 现货行只报持有量与按公共行情的 USDT 估值——账户接口给的就是余额，没有盈亏可言。
 *
 * API 凭据就在本页配置（不藏进设置页）：它是这个功能独有的钥匙，
 * 和仓位数据同生共死，放在消费它的地方才找得到。
 */
@Composable
fun PositionsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: PositionsViewModel = appViewModel { PositionsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MarketTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        AppBar(
            title = "我的仓位",
            subtitle = when {
                !state.configured -> "需先配置币安 API 凭据"
                else -> "币安 · 合约+现货 · 每 8 秒自动刷新" +
                    (state.updatedAt?.let { " · ${TIME_FORMAT.format(Date(it))}" } ?: "")
            },
            onBack = onBack,
            actions = {
                if (state.configured) {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "立即刷新")
                    }
                    IconButton(onClick = viewModel::toggleEditing) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = if (state.editing) "收起配置" else "凭据配置",
                            tint = if (state.editing) colors.accent else colors.ink,
                        )
                    }
                }
            },
        )

        if (!state.configured) {
            CredentialForm(state, viewModel)
            return@Column
        }

        // 已配置时表单默认收起，点顶栏齿轮展开改密钥或清除。
        AnimatedVisibility(
            visible = state.editing,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Rule()
                CredentialForm(state, viewModel)
                Rule()
            }
        }

        AnimatedBanner(
            visible = state.error != null,
            text = state.error.orEmpty(),
            tone = BannerTone.Error,
        )

        PositionSummary(state)
        Rule()

        if (state.positions.isEmpty() && state.spotBalances.isEmpty()) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = Spacing.Xxl).size(22.dp),
                    strokeWidth = 2.dp,
                    color = colors.muted,
                )
            } else {
                HintRow(
                    title = "当前没有仓位",
                    subtitle = "账户里没有持仓中的合约，也没有现货资产；开出仓位或买入后会显示在这里",
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.Xl)) {
                if (state.positions.isNotEmpty()) {
                    item { SectionHeader("永续合约 · ${state.positions.size} 个") }
                    items(state.positions, key = { it.id.storageKey }) { position ->
                        PositionRow(position)
                        Rule()
                    }
                }
                if (state.spotBalances.isNotEmpty()) {
                    item { SectionHeader("现货持仓 · ${state.spotBalances.size} 种") }
                    items(state.spotBalances, key = { "spot:${it.asset}" }) { balance ->
                        SpotRow(balance, state.spotValues[balance.asset])
                        Rule()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MarketTheme.colors.muted,
        modifier = Modifier.padding(
            start = Spacing.Gutter,
            end = Spacing.Gutter,
            top = Spacing.Md,
            bottom = Spacing.Xs,
        ),
    )
}

/* ── 凭据表单 ───────────────────────────────────────────────────────── */

@Composable
private fun CredentialForm(state: PositionsUiState, viewModel: PositionsViewModel) {
    val colors = MarketTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "在币安官网/App「API 管理」创建密钥后填入（建议只开读取权限，关闭提现）。" +
                "凭据用系统 Keystore 加密、只存本机；签名请求只会发往 api.binance.com / fapi.binance.com 官方域名，" +
                "不会经过任何镜像或中转。\n" +
                "注意：币安 API 大陆网络无法直连，使用时需让手机走代理/VPN（App 跟随系统代理）。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(
                start = Spacing.Gutter,
                end = Spacing.Gutter,
                top = Spacing.Md,
                bottom = Spacing.Sm,
            ),
        )
        CredentialField(
            label = "API Key",
            value = state.keyDraft,
            placeholder = if (state.configured) "已配置 · 留空保持不变" else "必填",
            onValue = viewModel::setKeyDraft,
        )
        CredentialField(
            label = "API Secret",
            value = state.secretDraft,
            placeholder = if (state.configured) "已配置 · 留空保持不变" else "必填",
            onValue = viewModel::setSecretDraft,
            password = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = if (state.configured) "已配置" else "未配置",
                tone = if (state.configured) PillTone.Positive else PillTone.Neutral,
            )
            Spacer(modifier = Modifier.width(Spacing.Md))
            TextAction("保存", viewModel::saveCredentials)
            if (state.configured) {
                TextAction(
                    text = "清除",
                    onClick = viewModel::clearCredentials,
                    color = colors.down,
                )
            }
        }
    }
}

/** 无边框输入行：标签在上、值在下，一条发丝线表示可编辑——与设置页同一取向。 */
@Composable
private fun CredentialField(
    label: String,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    password: Boolean = false,
) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        Spacer(Modifier.height(Spacing.Xxs))
        Box {
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
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                ),
            )
        }
        Spacer(Modifier.height(Spacing.Xxs))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/* ── 仓位列表 ───────────────────────────────────────────────────────── */

@Composable
private fun PositionSummary(state: PositionsUiState) {
    val colors = MarketTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        MetricCell(
            label = "合约未实现盈亏",
            value = signed(state.totalPnl) + " USDT",
            valueColor = pnlColor(state.totalPnl),
        )
        MetricCell(label = "合约名义总额", value = PriceFormatter.formatCompact(state.totalNotional))
        MetricCell(label = "现货估值", value = PriceFormatter.formatCompact(state.spotTotal))
    }
}

@Composable
private fun PositionRow(position: Position) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = position.id.symbol.removeSuffix("USDT"),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(Modifier.width(Spacing.Xs))
            StatusPill(
                text = "${position.side.label} · ${position.leverage}x",
                tone = if (position.side == PositionSide.LONG) PillTone.Positive else PillTone.Negative,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = signed(position.unrealizedPnl) + " USDT",
                style = MaterialTheme.typography.titleMedium,
                color = pnlColor(position.unrealizedPnl),
            )
        }
        Spacer(Modifier.size(Spacing.Xxs))
        Text(
            text = "数量 ${plain(position.quantity)} · 名义 ${PriceFormatter.formatCompact(position.notional)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Text(
            text = buildString {
                append("开仓 ${plain(position.entryPrice)} · 标记 ${plain(position.markPrice)}")
                if (position.liquidationPrice.signum() > 0) append(" · 强平 ${plain(position.liquidationPrice)}")
                if (position.marginType.isNotBlank()) append(" · ${if (position.marginType == "ISOLATED") "逐仓" else "全仓"}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun SpotRow(balance: SpotBalance, value: BigDecimal?) {
    val colors = MarketTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = balance.asset,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            if (value != null) {
                Text(
                    text = "≈ ${plain(value)} USDT",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
            }
        }
        Spacer(Modifier.size(Spacing.Xxs))
        Text(
            text = buildString {
                append("持有 ${plain(balance.quantity)}")
                val frozen = balance.quantity.subtract(balance.available)
                if (frozen.signum() > 0) append(" · 冻结 ${plain(frozen)}")
                if (value == null) append(" · 无 USDT 交易对，未估值")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun pnlColor(value: BigDecimal) =
    if (value.signum() < 0) MarketTheme.colors.down else MarketTheme.colors.up

private fun signed(value: BigDecimal): String =
    (if (value.signum() > 0) "+" else "") + plain(value)

private fun plain(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
