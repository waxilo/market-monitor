package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按下时「抓哪根水平类的线」的判定。
 *
 * 这是整个「调整已有划线」入口的关键：判错就会在旧线旁边多出一条新线，
 * 而用户以为自己在改原来那条。像素换算本身不在这里覆盖，只喂现成的 y 落点。
 *
 * 2026-10-14 起抓取范围里多了**视觉水平线**（原先只有告警线）：解锁态下图上这两类线
 * 是同一个水平通道，抓到手之后靠 [LineRef] 分流到「改规则」还是「改 chart_line」——
 * 所以断言的是 ref 本身，不只是 id。
 */
class HorizontalLineGrabTest {

    private val alerts = listOf(
        HorizontalAnchor(ref = LineRef.Alert(1L), yPx = 120f),
        HorizontalAnchor(ref = LineRef.Alert(2L), yPx = 300f),
        HorizontalAnchor(ref = LineRef.Alert(3L), yPx = 520f),
    )

    @Test
    fun `落在容差内抓到最近的那根`() {
        assertEquals(LineRef.Alert(2L), alerts.closestHorizontal(305f, slopPx = 24f))
    }

    @Test
    fun `两根都够得着时按距离而不是列表顺序`() {
        val close = listOf(
            HorizontalAnchor(ref = LineRef.Alert(10L), yPx = 120f),
            HorizontalAnchor(ref = LineRef.Alert(20L), yPx = 138f),
        )
        // 130 离后一根更近（8 < 10），排在后面的反而该被抓到
        assertEquals(LineRef.Alert(20L), close.closestHorizontal(130f, slopPx = 24f))
    }

    @Test
    fun `告警线与水平线同场时按距离取，不看是哪个域`() {
        val mixed = listOf(
            HorizontalAnchor(ref = LineRef.Alert(7L), yPx = 200f),
            HorizontalAnchor(ref = LineRef.Guide(42L), yPx = 214f),
        )
        // 208 离水平线更近（6 < 8）—— 抓到的是画线，不是告警线
        assertEquals(LineRef.Guide(42L), mixed.closestHorizontal(208f, slopPx = 24f))
        // 反过来：195 离告警线更近（5 < 19）
        assertEquals(LineRef.Alert(7L), mixed.closestHorizontal(195f, slopPx = 24f))
    }

    @Test
    fun `nearestHorizontal 连距离一起给——解锁态要拿它与直线比远近`() {
        val picked = alerts.nearestHorizontal(305f, slopPx = 24f)
        assertEquals(LineRef.Alert(2L), picked?.first)
        assertEquals(5f, picked?.second ?: -1f, 1e-6f)
    }

    @Test
    fun `超出容差就是新建一根而不是硬抓最近`() {
        // 那根 1.5dp 的虚线本来就按不准，容差之内该抓；差得太远还硬抓，
        // 用户想「在这个价位新建一条」却把别的线改掉了
        assertNull(alerts.closestHorizontal(250f, slopPx = 24f))
    }

    @Test
    fun `没有线时永远返回新建`() {
        assertNull(emptyList<HorizontalAnchor>().closestHorizontal(300f, slopPx = 24f))
        assertNull(alerts.nearestHorizontal(250f, slopPx = 24f))
    }
}
