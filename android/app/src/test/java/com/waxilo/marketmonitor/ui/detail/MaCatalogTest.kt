package com.waxilo.marketmonitor.ui.detail

import com.waxilo.marketmonitor.domain.repository.DEFAULT_MA_CATALOG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均线目录的两条纯函数：目录归一化与「选中 ⊆ 目录」。
 *
 * 背景是这一轮的真实需求：「自定义均线只能新增，删不掉原有的 MA5」。改成整份目录可编辑
 * 之后，出厂五档就只是初始值，删除路径与自定义档完全相同 —— 于是有两个地方必须同时收紧，
 * 而且都是纯逻辑、能用断言钉死：
 *
 * 1. [normalizeMaCatalog]：目录是用户手输 + 持久化来的，越界/重复/超限都得挡掉；
 * 2. [maSelectionIn]：删掉的档位必须从**选中集**里一并带走，否则图上留着一条
 *    没有 chip 可撤的死线 —— 这是本次改动最容易漏、也最难在真机上发现的一半。
 */
class MaCatalogTest {

    @Test
    fun `出厂目录就是那五档`() {
        assertEquals(listOf(5, 10, 20, 30, 60), DEFAULT_MA_CATALOG)
    }

    @Test
    fun `归一化丢掉越界值, 去重升序`() {
        assertEquals(
            listOf(7, 20, 99),
            normalizeMaCatalog(listOf(20, 7, 0, 99, 7, -3, 1001, 20)),
        )
    }

    @Test
    fun `归一化截到条数上限`() {
        val tooMany = (1..20).toList()
        val out = normalizeMaCatalog(tooMany)
        assertEquals(MA_LIMIT, out.size)
        // 截断取的是升序后的前 N 条（小数优先），不是「前 N 个输入」
        assertEquals((1..MA_LIMIT).toList(), out)
    }

    @Test
    fun `归一化不把空目录回填成出厂五档`() {
        // 清空是用户能表达的真实偏好（指标条上只剩「＋」），不该在重启后自己长回来
        assertTrue(normalizeMaCatalog(emptyList()).isEmpty())
    }

    @Test
    fun `删掉 MA5 时选中集里的 5 一起走`() {
        // 目录删 MA5：剩下 10/20/30/60，选中集里原来的 5 必须一并消失
        val catalog = normalizeMaCatalog(listOf(10, 20, 30, 60))
        assertEquals(listOf(10, 30), maSelectionIn(catalog, listOf(5, 10, 30)))
    }

    @Test
    fun `目录清空则选中集也清空`() {
        assertEquals(emptyList<Int>(), maSelectionIn(emptyList(), listOf(5, 10, 20)))
    }

    @Test
    fun `选中集的重复与乱序都被抹平`() {
        assertEquals(listOf(5, 20), maSelectionIn(listOf(5, 20, 60), listOf(20, 5, 20)))
    }

    @Test
    fun `恢复默认后出厂五档都在目录里`() {
        // 「恢复默认」按钮把草稿设成出厂目录，过一遍归一化不该被削掉任何一档
        assertEquals(DEFAULT_MA_CATALOG, normalizeMaCatalog(DEFAULT_MA_CATALOG))
    }
}
