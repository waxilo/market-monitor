package com.waxilo.marketmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁态下「按下点到了什么」→ 这一场单指的用途（2026-10-24「先单击选中再拖」）。
 *
 * 这是拖动旧线的唯一闸门：判宽的代价是「一次平移顺手把路过的线拖走」，
 * 判严的代价是「怎么按都拖不动」。断言的是三态本身，而不是「有没有异常」。
 */
class LineTouchTest {

    private val alert = LineRef.Alert(7L)
    private val guide = LineRef.Guide(42L)

    @Test
    fun `没选中任何线时命中一条——先选中，不拖`() {
        assertEquals(LineTouch.Select, lineTouch(alert, null))
        assertEquals(LineTouch.Select, lineTouch(guide, null))
    }

    @Test
    fun `命中的就是已选中的那条——当场接管拖动`() {
        assertEquals(LineTouch.Drag, lineTouch(alert, alert))
        assertEquals(LineTouch.Drag, lineTouch(guide, guide))
    }

    @Test
    fun `选中一条、命中另一条——换成命中那条（先选中，不拖）`() {
        assertEquals(LineTouch.Select, lineTouch(LineRef.Alert(8L), alert))
        // 跨域也一样：选中告警线时按到水平线，不该把告警线拖走
        assertEquals(LineTouch.Select, lineTouch(guide, alert))
        assertEquals(LineTouch.Select, lineTouch(alert, guide))
    }

    @Test
    fun `按在空白处——不选中也不拖（松手时由调用方取消选中）`() {
        assertEquals(LineTouch.None, lineTouch(null, null))
        // 有选中但按在空白：同样是空白，不能因为「手里有选中」就把最近的线拖走
        assertEquals(LineTouch.None, lineTouch(null, alert))
    }

    @Test
    fun `草稿不可选中——告警线 ruleId 为 null`() {
        assertEquals(LineTouch.None, lineTouch(LineRef.Alert(null), null))
        // 即使选中态里莫名留了一条草稿，也不能当成「已选中」直接拖
        assertEquals(LineTouch.None, lineTouch(LineRef.Alert(null), LineRef.Alert(null)))
    }

    @Test
    fun `草稿不可选中——画线 id 为 0`() {
        assertEquals(LineTouch.None, lineTouch(LineRef.Guide(0L), null))
        assertEquals(LineTouch.None, lineTouch(LineRef.Guide(0L), LineRef.Guide(0L)))
    }

    @Test
    fun `选中态被清掉之后，同一条线要重新点一次才拖得动`() {
        // 上锁、切工具、换标的都会清空选中态（见 KlineChart 的 LaunchedEffect）。
        // 这里断言的就是那个后果：清空后按下去只能重新选中，绝不会直接拖。
        assertEquals(LineTouch.Drag, lineTouch(alert, alert))
        assertEquals(LineTouch.Select, lineTouch(alert, null))
    }

    @Test
    fun `selectable 只认真实落库的 id`() {
        assertTrue(alert.selectable())
        assertTrue(guide.selectable())
        assertFalse(LineRef.Alert(null).selectable())
        assertFalse(LineRef.Guide(0L).selectable())
    }
}
