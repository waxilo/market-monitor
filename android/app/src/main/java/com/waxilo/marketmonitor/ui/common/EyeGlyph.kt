package com.waxilo.marketmonitor.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 眼睛的默认边长：指标条浮层按钮与均线面板行内小眼睛共用（面板里传更小的值）。 */
val EYE_GLYPH_SIZE: Dp = 15.dp

/**
 * 显隐的「眼睛」：开 = 眼眶 + 瞳孔，关 = 再加一道斜杠。
 *
 * 自绘而不用 `Icons.Default.Visibility` —— 眼睛那对矢量在 material-icons-extended 里，
 * 为一个图标拖进上千个不划算，本应用只依赖 icons-core。
 *
 * 现在有**两处**用处：图内「划线显隐」的浮层按钮，以及均线配置里每条均线自己的小眼睛。
 * 两处必须长得一模一样，否则同一个「藏起来」会有两种视觉语言。
 *
 * [glyphSize] 不叫 `size`：`Canvas` 的绘制域里 `size` 是画布尺寸（`DrawScope.size`），
 * 同名会让 lambda 里读到的到底是谁变得含糊。
 */
@Composable
fun EyeGlyph(
    open: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = EYE_GLYPH_SIZE,
) {
    Canvas(modifier.size(glyphSize)) {
        val stroke = size.width * 0.08f
        val centerY = size.height / 2f
        val halfWidth = size.width * 0.46f
        val arch = size.height * 0.6f
        // 上下两条对称的二次曲线拼出眼眶：二次曲线只走到控制点的一半高度，
        // 所以 arch 要放大到 0.6 倍画高，眼睛才有 0.3 倍画高的实际半高
        val outline = Path().apply {
            moveTo(size.width / 2f - halfWidth, centerY)
            quadraticTo(size.width / 2f, centerY - arch, size.width / 2f + halfWidth, centerY)
            quadraticTo(size.width / 2f, centerY + arch, size.width / 2f - halfWidth, centerY)
            close()
        }
        drawPath(outline, tint, style = Stroke(width = stroke))
        drawCircle(tint, radius = size.width * 0.14f, center = Offset(size.width / 2f, centerY))
        if (!open) {
            drawLine(
                color = tint,
                start = Offset(size.width * 0.14f, size.height * 0.12f),
                end = Offset(size.width * 0.86f, size.height * 0.88f),
                strokeWidth = stroke,
            )
        }
    }
}
