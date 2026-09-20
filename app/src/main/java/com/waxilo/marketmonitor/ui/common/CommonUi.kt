package com.waxilo.marketmonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.ui.theme.DownRed
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.UpGreen

/** 涨跌幅文本：带符号 + 等宽数字，色盲用户也能靠符号区分（PRD 可访问性）。 */
@Composable
fun ChangeText(
    changePercent: Double?,
    modifier: Modifier = Modifier,
) {
    val color = when {
        changePercent == null || changePercent.isNaN() -> MaterialTheme.colorScheme.onSurfaceVariant
        changePercent > 0 -> UpGreen
        changePercent < 0 -> DownRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = PriceFormatter.formatChange(changePercent),
        modifier = modifier,
        color = color,
        style = PriceTextStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
        textAlign = TextAlign.End,
    )
}

/**
 * 分段选择（市场/页签/周期等少量互斥选项）。
 * 选中项用「容器色底 + 强调色字」，未选才用普通正文色，对比比纯黄色块更克制。
 */
@Composable
fun <T> SegmentPicker(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(option),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold
                    else androidx.compose.ui.text.font.FontWeight.Medium,
                )
            }
        }
    }
}

/** 空态/错误态提示，保持列表与详情页的留白一致。 */
@Composable
fun HintRow(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onAction != null && actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 「离线数据」标记（PRD 3.2 缓存策略：断网时明确告知用户看到的是缓存）。 */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .background(DownRed.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 详情页/列表页通用的「标签 + 值」一行。 */
@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            ),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 页面顶部标题栏：二级页带返回键 + 标题 + 右侧动作；省略副标题时留空。
 * 各页面复用，统一留白与层级。
 */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
fun ThinDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}