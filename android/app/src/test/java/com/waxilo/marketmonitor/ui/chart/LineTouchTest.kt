package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁态下「按下点到了什么」→ 这一场单指的用途。
 *
 * 三档（2026-11-25 用户「不管什么线，都要先选中才能编辑」）：
 * 命中**已选中**那条 = [LineTouch.Drag]（走满 slop 才真正接管，见 `pendingLineGrab`）；
 * 命中**没选中**的线 = [LineTouch.Select]（只当点击候选，松手才选中）；
 * 空白 / 草稿 = [LineTouch.None]。
 *
 * 这是拖动旧线的唯一闸门：判宽的代价是「一次平移顺手把路过的线拖走」，
 * 判严的代价是「怎么按都拖不动」。断言的是状态本身，而不是「有没有异常」。
 *
 * ⚠️ 2026-11-26 的「水平线去掉抓点、任意位置可拖」只作用于**已选中**那条：
 * 端点把手已删，但「先选中」这道门槛保留（见第三条）。
 */
class LineTouchTest {

    private val alert = LineRef.Alert(7L)
    private val guide = LineRef.Guide(42L)

    @Test
    fun `命中的正是已选中那条——候选拖动`() {
        assertEquals(LineTouch.Drag, lineTouch(alert, alert))
        assertEquals(LineTouch.Drag, lineTouch(guide, guide))
    }

    @Test
    fun `命中还没选中的线——只当点击候选，这一下继续拖画布`() {
        assertEquals(LineTouch.Select, lineTouch(alert, null))
        assertEquals(LineTouch.Select, lineTouch(guide, null))
        // 选中的是另一条（告警线 vs 画线）时同样只算候选
        assertEquals(LineTouch.Select, lineTouch(alert, guide))
        assertEquals(LineTouch.Select, lineTouch(guide, alert))
    }

    @Test
    fun `按在空白处——谁也不拖（松手时由调用方取消选中）`() {
        assertEquals(LineTouch.None, lineTouch(null, guide))
        assertEquals(LineTouch.None, lineTouch(null, null))
    }

    @Test
    fun `草稿不可选中——告警线 ruleId 为 null`() {
        val draft = LineRef.Alert(null)
        assertEquals(LineTouch.None, lineTouch(draft, draft))
        assertEquals(LineTouch.None, lineTouch(draft, guide))
    }

    @Test
    fun `草稿不可选中——画线 id 为 0`() {
        val draft = LineRef.Guide(0L)
        assertEquals(LineTouch.None, lineTouch(draft, draft))
        assertEquals(LineTouch.None, lineTouch(draft, alert))
    }

    @Test
    fun `selectable 只认真实落库的 id`() {
        assertTrue(alert.selectable())
        assertTrue(guide.selectable())
        assertFalse(LineRef.Alert(null).selectable())
        assertFalse(LineRef.Guide(0L).selectable())
    }
}
