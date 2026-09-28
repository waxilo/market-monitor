package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 划线模式按下时「抓哪根线」的判定。
 *
 * 这是整个「调整已有预警」入口的关键：判错就会在旧线旁边多出一条新预警，
 * 而用户以为自己在改原来那条。像素换算本身不在这里覆盖，只喂现成的 y 落点。
 */
class AlertLineGrabTest {

    private val lines = listOf(
        AlertLineAnchor(ruleId = 1L, yPx = 120f),
        AlertLineAnchor(ruleId = 2L, yPx = 300f),
        AlertLineAnchor(ruleId = 3L, yPx = 520f),
    )

    @Test
    fun `落在容差内抓到最近的那根`() {
        assertEquals(2L, lines.closestAlertLine(305f, slopPx = 24f))
    }

    @Test
    fun `两根都够得着时按距离而不是列表顺序`() {
        val close = listOf(
            AlertLineAnchor(ruleId = 10L, yPx = 120f),
            AlertLineAnchor(ruleId = 20L, yPx = 138f),
        )
        // 130 离后一根更近（8 < 10），排在后面的反而该被抓到
        assertEquals(20L, close.closestAlertLine(130f, slopPx = 24f))
    }

    @Test
    fun `超出容差就是新建一根而不是硬抓最近`() {
        // 那根 1.5dp 的虚线本来就按不准，容差之内该抓；差得太远还硬抓，
        // 用户想「在这个价位新建一条」却把别的预警改掉了
        assertNull(lines.closestAlertLine(250f, slopPx = 24f))
    }

    @Test
    fun `没有线时永远返回新建`() {
        assertNull(emptyList<AlertLineAnchor>().closestAlertLine(300f, slopPx = 24f))
    }
}
