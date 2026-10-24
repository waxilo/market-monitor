package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视窗的边界判据（PRD FR-2.2「拖到最左端加载更早的历史」）。
 *
 * 这里只钉一件事，但它是翻页能不能触发的前提：**「窗口贴到最老一根」这件事
 * 必须由一个浮点容差友好的判据回答**。
 *
 * 背景：触发点原先写的是 `startIndex(barCount) == 0`。而 `startIndex` 是
 * `floor(barCount + rightOffset - visibleBars)` —— 三个浮点量相减再取 floor。
 * `clamp()` 把 `rightOffset` 夹到最小值的那个瞬间，理论上该等于 0，但
 * `visibleBars` 一旦是**非整数值**（双指缩放后必然是小数），中间的浮点舍入
 * 会让结果落在 0 的左侧一丁点，`floor` 于是给出 **-1**，
 * `== 0` 永远不成立 ⇒ 往更早方向翻页**整段失效**。
 *
 * 所以判据必须是 `<= 0`（[ChartViewport.atOldestEdge]），而不是 `== 0`。
 * 本测试用「缩放过的整数序列 + 拖到最左」这组输入把 -1 这一格钉住：
 * 它是这件事的证据，不是边角料。
 */
class ChartViewportTest {

    /** 与详情页首屏同量级：一页 500 根基础周期蜡烛。 */
    private val bars = 500

    @Test
    fun `双指缩放后贴到最左端时 startIndex 是 -1，写 startIndex == 0 会永远触发不了翻页`() {
        // 57.37f 是「捏合过一次」的典型可见根数：只要不是整数，下面的减法就会留残差
        val zoomed = ChartViewport(visibleBars = 57.37f, rightOffset = 0f)
        // 一路往右拖（看更早），越界的意图由 clamp 夹到最左端
        val atLeftEdge = zoomed.pan(deltaBars = 10_000f, barCount = bars)

        // 值本身必须钉住：这就是 `== 0` 失效的那一格
        assertEquals(-1, atLeftEdge.startIndex(bars))
        assertFalse("这正是不该再用的判据", atLeftEdge.startIndex(bars) == 0)
        assertTrue("贴到最左端必须被判成「可以往更早翻」", atLeftEdge.atOldestEdge(bars))
    }

    @Test
    fun `可见根数是整数时 startIndex 恰好是 0——旧判据只是「碰巧」能用`() {
        val plain = ChartViewport(visibleBars = 60f, rightOffset = 0f)
        val atLeftEdge = plain.pan(deltaBars = 10_000f, barCount = bars)

        // 默认可见根数（60f）是整数值，整条算式在浮点里是精确的，于是恰好 0 ——
        // 旧写法在「没捏合过」的图上能用，纯属巧合，换个可见根数就断
        assertEquals(0, atLeftEdge.startIndex(bars))
        assertTrue(atLeftEdge.atOldestEdge(bars))
    }

    @Test
    fun `没拖到最左端时不算贴边`() {
        val mid = ChartViewport(visibleBars = 60f, rightOffset = -100f)
        assertTrue("窗口左端还在第 340 根，不该判贴边", mid.startIndex(bars) > 0)
        assertFalse(mid.atOldestEdge(bars))
    }

    @Test
    fun `序列比窗口还窄时左端落在序列外，也必须算贴边`() {
        // 短序列（如 1M 周期只有 13 根）配 60 根宽的窗口：clamp 的最小偏移是 0，
        // 左端于是停在负数（plotRange 故意不夹）。写 == 0 的话这类图永远翻不动。
        val short = ChartViewport(visibleBars = 60f, rightOffset = 0f)
        assertEquals(-47, short.startIndex(13))
        assertTrue(short.atOldestEdge(13))
    }

    @Test
    fun `空序列不算贴边——没有序列可翻`() {
        val any = ChartViewport(visibleBars = 60f, rightOffset = 0f)
        assertFalse(any.atOldestEdge(0))
    }

    /* ── needsOlder：翻页判据与「有没有在拖动」解耦 ─────────────────────── */

    @Test
    fun `一直缩小把窗口撑得比数据还宽时必须判「要补页」`() {
        // 这才是用户报的场景：手指只捏合、没拖动。初始 60 根，一次放大因子拉到 600 根，
        // 500 根数据被夹在右端、左边空一大片 —— 旧写法只在 onPan 里判，这里没人问。
        val start = ChartViewport(visibleBars = 60f, rightOffset = 3f)
        val zoomed = start.zoom(barFactor = 10f, anchorRatio = 0.5f, barCount = bars)

        assertEquals(600f, zoomed.visibleBars)
        assertTrue("左端已经越过序列起点", zoomed.startIndex(bars) <= 0)
        assertTrue("缩小时必须判「要补页」", zoomed.needsOlder(bars))
    }

    @Test
    fun `数据撑满窗口且贴着最新一根时不补页`() {
        val v = ChartViewport(visibleBars = 60f, rightOffset = 3f)
        assertEquals(443f, v.plotStart(bars))
        assertFalse("视窗左侧还有 443 根余量", v.needsOlder(bars))
    }

    @Test
    fun `左端进入提前量就算要补页——不必等到贴上最老一根`() {
        // 提前量 = 一屏（可见 60 根）。左端剩 50 根时就该提前去要，
        // 此时 startIndex 还是 50，atOldestEdge 仍是 false —— 两个判据在这里必须分得开。
        val near = ChartViewport(visibleBars = 60f, rightOffset = -390f)
        assertEquals(50f, near.plotStart(bars))
        assertFalse("还没贴到最老一根", near.atOldestEdge(bars))
        assertTrue("但已经在提前量里", near.needsOlder(bars))
    }

    @Test
    fun `提前量夹在上下限之间——很窄的窗口也有 24 根、很宽的窗口不超过 120 根`() {
        // 可见 15 根：余量 24 根时就该要，而不是 15 根
        val narrow = ChartViewport(visibleBars = 15f, rightOffset = -(bars - 15 - 20).toFloat())
        assertEquals(20f, narrow.plotStart(bars))
        assertTrue(narrow.needsOlder(bars))
        // 可见 600 根：余量 120 根就够，不必等到剩 600 根
        val wide = ChartViewport(visibleBars = 600f, rightOffset = 0f)
        assertEquals(-100f, wide.plotStart(bars))
        assertTrue(wide.needsOlder(bars))
    }

    @Test
    fun `短序列与空序列`() {
        // 13 根的 1M 图：窗口比数据宽，必须一直可补（补不到会由 hasMore 收口）
        assertTrue(ChartViewport(visibleBars = 60f, rightOffset = 0f).needsOlder(13))
        // 空序列没有「更早」可翻
        assertFalse(ChartViewport(visibleBars = 60f, rightOffset = 0f).needsOlder(0))
    }
}
