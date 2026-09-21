package com.waxilo.marketmonitor.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.theme.MetricValueStyle
import com.waxilo.marketmonitor.ui.theme.Motion
import com.waxilo.marketmonitor.ui.theme.PriceTextStyle
import com.waxilo.marketmonitor.ui.theme.Radius
import com.waxilo.marketmonitor.ui.theme.SectionOverlineStyle
import com.waxilo.marketmonitor.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs

/* ══════════════════════════════════════════════════════════════════════
 *  涨跌文本
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * 涨跌幅文本：带符号 + 等宽数字，色盲用户也能靠符号区分（PRD 可访问性）。
 * 符号直接由 [PriceFormatter.formatChange] 产出，这里只负责着色与排版。
 */
@Composable
fun ChangeText(
    changePercent: Double?,
    modifier: Modifier = Modifier,
    showArrow: Boolean = false,
    fontSize: TextUnit = 14.sp,
) {
    val color = MarketTheme.colors.forChange(changePercent)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (showArrow && changePercent != null && changePercent != 0.0) {
            Icon(
                imageVector = if (changePercent > 0) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
        }
        Text(
            text = PriceFormatter.formatChange(changePercent),
            color = color,
            style = PriceTextStyle.copy(fontSize = fontSize),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/**
 * 涨跌「胶囊」：把涨跌幅包成一个小圆角块，用于列表与卡片头部。
 * 相比纯文字，块状底色让扫读一列数字时涨跌边界更清楚。
 */
@Composable
fun ChangePill(
    changePercent: Double?,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    val color = colors.forChange(changePercent)
    Box(
        modifier = modifier
            .clip(Radius.xsShape)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.Xs, vertical = 3.dp),
    ) {
        Text(
            text = PriceFormatter.formatChange(changePercent),
            style = PriceTextStyle.copy(fontSize = 13.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════
 *  分隔线 / 区块标题
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * 唯一的横线组件。
 * 极简风用 1px 细线代替卡片阴影：列表行之间、区块之间都是同一根线，
 * 靠 [inset] 控制是否留出左内边距（列表内缩进、区块间通栏）。
 */
@Composable
fun Rule(
    modifier: Modifier = Modifier,
    inset: Dp = Spacing.Gutter,
    strong: Boolean = false,
) {
    val colors = MarketTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(if (strong) colors.hairlineStrong else colors.hairline),
    )
}

/**
 * 区块小标题：等宽 + 大写 + 拉开字距。
 * 这是编辑部风的签名元素——所有区块都由它开场，右侧可选一行极弱的计数/说明。
 */
@Composable
fun SectionOverline(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = MarketTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text.uppercase(),
            style = SectionOverlineStyle,
            color = colors.muted,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = SectionOverlineStyle,
                color = colors.muted.copy(alpha = 0.7f),
            )
        }
    }
}

/** 旧名保留：设置页等处的分组标题沿用。 */
@Composable
fun SectionHeader(text: String, trailing: String? = null, modifier: Modifier = Modifier) =
    SectionOverline(text = text, trailing = trailing, modifier = modifier)

/* ══════════════════════════════════════════════════════════════════════
 *  分段选择器（取代旧的 SegmentPicker + Chip 两套）
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * 分段选择：互斥选项（市场/页签/副图/周期）。
 *
 * 视觉立场——**不使用填充色块**。旧版用 primary 实心滑块，在极简风里太重；
 * 这里改成「选中项加下划线 + 文字转墨黑」，未选中为灰字。轨道只有一根细底线，
 * 与 [Rule] 同一套语言，所以它放在任何位置都不显突兀。
 *
 * 下划线由 animateDpAsState 平滑移动，宽度按等分槽位计算。
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 等分槽位（页签）还是包裹内容（周期选择）。 */
    fillWidth: Boolean = true,
) {
    val colors = MarketTheme.colors
    val shape = Radius.xsShape
    BoxWithConstraints(modifier = modifier) {
        val n = options.size.coerceAtLeast(1)
        val slot = if (fillWidth && maxWidth < Dp.Infinity) maxWidth / n else null
        val index = options.indexOf(selected).coerceAtLeast(0)
        val indicatorWidth by animateDpAsState(
            targetValue = slot ?: labelWidth(options, index, labelOf),
            animationSpec = tween(Motion.BaseMs, easing = Motion.Standard),
            label = "segmentIndicatorWidth",
        )
        val indicatorStart by animateDpAsState(
            targetValue = if (slot != null) slot * index else labelStart(options, index, labelOf),
            animationSpec = tween(Motion.BaseMs, easing = Motion.Standard),
            label = "segmentIndicatorStart",
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Lg)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) colors.ink else colors.muted,
                        animationSpec = tween(Motion.FastMs),
                        label = "segmentText",
                    )
                    Box(
                        modifier = Modifier
                            .then(if (slot != null) Modifier.weight(1f) else Modifier)
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(shape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(option) },
                        contentAlignment = if (slot != null) Alignment.Center else Alignment.CenterStart,
                    ) {
                        Text(
                            text = labelOf(option),
                            style = MaterialTheme.typography.labelLarge,
                            color = textColor,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                if (slot == null) Spacer(Modifier.weight(1f))
            }
            // 轨道 + 移动下划线
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.hairline)) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorStart)
                        .width(indicatorWidth)
                        .height(2.dp)
                        .background(colors.ink),
                )
            }
        }
    }
}

/**
 * 包裹模式下的槽位估算：等宽排版下 1 字符 ≈ 8.2dp @15sp。
 * 这只是给动画用的近似值，不追求像素级精确——下划线宽度差 1-2dp 肉眼不可辨，
 * 但替换成 onTextLayout 实测会在每次组合时多一轮测量，不划算。
 */
private fun <T> labelWidth(options: List<T>, index: Int, labelOf: (T) -> String): Dp =
    (labelOf(options[index.coerceIn(0, options.lastIndex)]).length * 8.2f).dp + Spacing.Xs

private fun <T> labelStart(options: List<T>, index: Int, labelOf: (T) -> String): Dp {
    var offset = 0f
    for (i in 0 until index.coerceIn(0, options.lastIndex)) {
        offset += labelOf(options[i]).length * 8.2f + Spacing.Lg.value
    }
    return offset.dp
}

/**
 * 周期/指标选择用的「方块」标签（Chip）。
 * 与 [SegmentedControl] 的区别：这里选项多且非互斥（MA5/MA10/BOLL 可多选），
 * 所以用描边小方块而不是下划线。
 */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    val bg by animateColorAsState(
        targetValue = if (selected) colors.ink else Color.Transparent,
        animationSpec = tween(Motion.FastMs),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) colors.paper else colors.muted,
        animationSpec = tween(Motion.FastMs),
        label = "chipFg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.ink else colors.hairline,
        animationSpec = tween(Motion.FastMs),
        label = "chipBorder",
    )
    Box(
        modifier = modifier
            .clip(Radius.xsShape)
            .background(bg)
            .border(1.dp, border, Radius.xsShape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = Spacing.Sm, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════
 *  状态与提示
 * ══════════════════════════════════════════════════════════════════════ */

/** 状态胶囊：开启/关闭、投递成功/失败等二元状态的可视化。 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    val color = when (tone) {
        PillTone.Positive -> colors.up
        PillTone.Negative -> colors.down
        PillTone.Neutral -> colors.muted
    }
    Box(
        modifier = modifier
            .clip(Radius.fullShape)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.Xs, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

enum class PillTone { Positive, Negative, Neutral }

/**
 * 空态/错误态。
 * 极简风处理空态的做法是「留白 + 一句话」，不塞插画。
 * 所以这里只有标题、可选的说明与一个文字按钮，垂直留白给足。
 */
@Composable
fun HintRow(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Xxl, horizontal = Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        if (onAction != null && actionLabel != null) {
            Spacer(Modifier.height(Spacing.Lg))
            TextAction(actionLabel, onAction)
        }
    }
}

/** 文字按钮：极简风的按钮就是「一段带下划线的文字」，不用填充矩形。 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val colors = MarketTheme.colors
    Text(
        text = text,
        modifier = modifier
            .clip(Radius.xsShape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
        style = MaterialTheme.typography.labelLarge,
        color = color ?: colors.ink,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * 提示横幅（离线、错误、通知权限）。
 *
 * 旧版统一样式直接用红色底 —— 但「K 线来自缓存」并不是错误，
 * 用错误色会制造不必要的紧张感。所以这里按 [tone] 分流：
 * 只有真正的错误才用红色，离线/缓存走中性灰。
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Neutral,
) {
    val colors = MarketTheme.colors
    val accent = when (tone) {
        BannerTone.Error -> colors.down
        BannerTone.Success -> colors.up
        BannerTone.Neutral -> colors.muted
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.07f))
            // 用 IntrinsicSize.Min 让左侧色条与文字同高：
            // 写死高度的话，两行的提示文字会让色条只覆盖一半，看着像渲染错位
            .height(IntrinsicSize.Min),
    ) {
        // 左侧 3dp 色条代替整块底色：既能标出语义，又不至于把页面切碎
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
        )
    }
}

@Composable
fun AnimatedBanner(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Neutral,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Banner(text = text, tone = tone)
    }
}

enum class BannerTone { Neutral, Error, Success }

/* ══════════════════════════════════════════════════════════════════════
 *  顶部栏
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * 页面顶部栏（编辑部风）。
 *
 * 与旧版的关键差异：
 * 1. 大标题用 displaySmall（30sp Bold）而不是 titleLarge，建立明确的刊头层级；
 * 2. 返回键与动作区尺寸统一为 44dp，避免图标大小不一；
 * 3. 下划线默认不画——由页面自己决定是否用 [Rule]，因为首页头部下面是内容流而非功能区。
 */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MarketTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (onBack != null) Spacing.Xs else Spacing.Gutter,
                    end = Spacing.Xs,
                    top = if (onBack != null) Spacing.Xs else Spacing.Md,
                    bottom = Spacing.Xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.ink,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (large) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
 *  指标卡 / 迷你走势线
 * ══════════════════════════════════════════════════════════════════════ */

/** 单个指标（标签 + 值），详情页统计条与卡片共用。 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    align: Alignment.Horizontal = Alignment.Start,
    valueStyle: TextStyle = MetricValueStyle,
) {
    val colors = MarketTheme.colors
    Column(modifier = modifier, horizontalAlignment = align) {
        Text(
            text = label,
            style = SectionOverlineStyle,
            color = colors.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.Xxs))
        Text(
            text = value,
            style = valueStyle,
            color = valueColor ?: colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 高低区间条：把「当前价在 24h 最低—最高之间所处的位置」画成一根细条。
 *
 * 这是详情页要补的「数据可视化」之一——一屏数字很难让人对「现在离高点还有多远」
 * 有直觉，一根条就能立刻看出来。纯 Canvas 绘制，无额外依赖。
 */
@Composable
fun RangeBar(
    low: Double,
    high: Double,
    current: Double,
    modifier: Modifier = Modifier,
) {
    val colors = MarketTheme.colors
    if (high <= low || current.isNaN()) {
        Box(modifier.fillMaxWidth().height(2.dp).background(colors.hairline))
        return
    }
    val fraction = ((current - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    val fill = if (current >= (low + high) / 2) colors.up else colors.down
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clearAndSetSemantics { },
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(colors.hairline))
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(fill),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = 2.dp, height = 10.dp)
                    .background(fill),
            )
        }
    }
}

/**
 * 迷你走势线：把一段收盘价序列归一化成一条折线。
 *
 * 用途是让列表行一眼能看出「最近是往上还是往下」——这是纯数字给不了的信息。
 * 故意极简：只有一条线 + 一个端点，不画坐标轴、不填充渐变，60dp 宽也不糊。
 */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    strokeWidth: Dp = 1.5.dp,
) {
    val colors = MarketTheme.colors
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val lineColor = color ?: if (values.last() >= values.first()) colors.upSoft else colors.downSoft
    // 序列在这里归一化：绘制层不关心真实价格，只关心相对形状
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { abs(it) > 1e-12 } ?: 1.0

    Box(
        modifier = modifier.clearAndSetSemantics { },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val stepX = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = stepX * i
                // 上下各留 2dp，避免极值贴边被裁掉
                val usable = size.height - 4.dp.toPx()
                val y = 2.dp.toPx() + usable * (1f - ((v - min) / span).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
            )
            // 末点高亮：让「当前价」在折线上有个锚
            val lastY = 2.dp.toPx() +
                (size.height - 4.dp.toPx()) * (1f - ((values.last() - min) / span).toFloat())
            drawCircle(
                color = lineColor,
                radius = strokeWidth.toPx() * 1.6f,
                center = Offset(size.width, lastY),
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════
 *  列表行骨架
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * 标准列表行容器：统一最小高度、横向留白与点击反馈。
 *
 * 把「行」抽成组件而不是每页各写一个 Row，是为了让所有列表的节奏一致，
 * 也让「按下时背景变浅」这一交互只实现一次。
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val colors = MarketTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) {
                        scope.launch {
                            pressAlpha.snapTo(1f)
                            pressAlpha.animateTo(0f, tween(Motion.SlowMs))
                        }
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                if (pressAlpha.value > 0f) {
                    drawRect(colors.ink.copy(alpha = 0.04f * pressAlpha.value))
                }
            }
            .defaultMinSize(minHeight = Spacing.RowMinHeight)
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 分组容器：设置页等处的「一组条目」。
 * 极简风不做卡片阴影，只用一条上边线 + 标题把它们从页面里划出来。
 */
@Composable
fun Section(
    title: String? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Spacer(Modifier.height(Spacing.Lg))
            SectionOverline(text = title, trailing = trailing)
            Spacer(Modifier.height(Spacing.Xs))
        }
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
