package com.waxilo.marketmonitor.ui.detail

import com.waxilo.marketmonitor.domain.indicator.DEFAULT_EMA_CATALOG
import com.waxilo.marketmonitor.domain.indicator.DEFAULT_MA_CATALOG
import com.waxilo.marketmonitor.domain.indicator.MaFamily
import com.waxilo.marketmonitor.domain.indicator.defaultCatalog
import com.waxilo.marketmonitor.ui.chart.MA_COLOR_SLOT_COUNT
import com.waxilo.marketmonitor.ui.chart.EMA_LINE_ROLES
import com.waxilo.marketmonitor.ui.chart.MA_LINE_ROLES
import com.waxilo.marketmonitor.ui.chart.maRoleForSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均线目录的纯函数：目录归一化、配色归一化，以及 MA / EMA 两族的分族口径。
 *
 * 背景是这一轮的真实需求：「指标条上只留 MA / EMA 两枚 chip，点一下整族显隐，
 * EMA 也要能自定义档位」。两层模型（目录 + 选中子集）因此收敛成一层（目录即配置），
 * 该收的这三件事都是纯逻辑、能用断言钉死：
 *
 * 1. [normalizeMaCatalog]：目录是用户手输 + 持久化来的，越界/重复/超限都得挡掉；
 * 2. [normalizeMaColors]：一色一线，且增删别的均线不改已有线的颜色；
 * 3. 两族的**目录与色位互不干扰** —— MA 用满八条也不该占走 EMA 的颜色；
 * 4. 逐条小眼睛（[normalizeMuted] / [drawnPeriods] / [toggleMuted] / [setAllMuted]）：
 *    **只藏不删**、`muted ⊆ catalog`，图上画的 = 目录 − 闭眼。
 */
class MaCatalogTest {

    @Test
    fun `两族各有各的出厂目录`() {
        assertEquals(listOf(5, 10, 20, 30, 60), DEFAULT_MA_CATALOG)
        assertEquals(listOf(12, 26, 50), DEFAULT_EMA_CATALOG)
        assertEquals(DEFAULT_MA_CATALOG, defaultCatalog(MaFamily.MA))
        assertEquals(DEFAULT_EMA_CATALOG, defaultCatalog(MaFamily.EMA))
    }

    @Test
    fun `读数带与图上用的标签带族前缀`() {
        assertEquals("MA5", MaFamily.MA.lineLabel(5))
        assertEquals("EMA12", MaFamily.EMA.lineLabel(12))
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
    fun `归一化不把空目录回填成出厂目录`() {
        // 清空是用户能表达的真实偏好（chip 点亮也不画线），不该在重启后自己长回来
        assertTrue(normalizeMaCatalog(emptyList()).isEmpty())
    }

    @Test
    fun `恢复默认后出厂档位都在目录里`() {
        // 「恢复默认」按钮把草稿设成出厂目录，过一遍归一化不该被削掉任何一档
        assertEquals(DEFAULT_MA_CATALOG, normalizeMaCatalog(DEFAULT_MA_CATALOG))
        assertEquals(DEFAULT_EMA_CATALOG, normalizeMaCatalog(DEFAULT_EMA_CATALOG))
    }

    // ---- 分族：色位各占各的 ----

    @Test
    fun `两族的色位表互不相同, 且不撞色`() {
        val ma = MA_LINE_ROLES.toSet()
        val ema = EMA_LINE_ROLES.toSet()
        assertTrue("MA / EMA 不应共用色位", ma.intersect(ema).isEmpty())
        // BOLL 的三位（QUINARY / SENARY / OCTONARY）也不该被任一族占用
        assertTrue(MA_LINE_ROLES.none { it.ordinal in 5..7 })
        assertTrue(EMA_LINE_ROLES.none { it.ordinal in 5..7 })
        assertNotEquals(maRoleForSlot(MaFamily.MA, 0), maRoleForSlot(MaFamily.EMA, 0))
    }

    @Test
    fun `色槽越界钳进 0 到 7`() {
        // 槽位只应从 normalizeMaColors 出来（必然 0~7）；越界说明有 bug，取边界值兜底而不是崩
        assertEquals(MA_LINE_ROLES[7], maRoleForSlot(MaFamily.MA, MA_COLOR_SLOT_COUNT))
        assertEquals(MA_LINE_ROLES[0], maRoleForSlot(MaFamily.MA, -1))
        assertEquals(EMA_LINE_ROLES[7], maRoleForSlot(MaFamily.EMA, 99))
        assertEquals(EMA_LINE_ROLES[0], maRoleForSlot(MaFamily.EMA, -3))
        // 两族的第八条都合法（各自八槽，互不占位）
        assertEquals(MA_LINE_ROLES[7], maRoleForSlot(MaFamily.MA, 7))
        assertEquals(EMA_LINE_ROLES[7], maRoleForSlot(MaFamily.EMA, 7))
    }

    // ---- 线色：期数 → 色槽 ----

    @Test
    fun `老存档没有颜色时按目录升序补齐`() {
        // 与旧版观感一致：MA5/10/20/30/60 → 0/1/2/3/4
        assertEquals(
            mapOf(5 to 0, 10 to 1, 20 to 2, 30 to 3, 60 to 4),
            normalizeMaColors(DEFAULT_MA_CATALOG, emptyMap()),
        )
    }

    @Test
    fun `新增均线拿第一个空槽且不动已有颜色`() {
        val before = normalizeMaColors(listOf(5, 10), emptyMap())
        val after = normalizeMaColors(listOf(5, 10, 7), before)
        assertEquals(mapOf(5 to 0, 10 to 1, 7 to 2), after)
    }

    @Test
    fun `删除均线后其余颜色不重排`() {
        val before = normalizeMaColors(listOf(5, 10, 20), emptyMap())
        // 删掉 MA10：MA5 仍是 0，MA20 仍是 2（不会因为空出 1 号就往前挤）
        assertEquals(mapOf(5 to 0, 20 to 2), normalizeMaColors(listOf(5, 20), before))
    }

    @Test
    fun `撞车的颜色按升序把后来者挪到空槽`() {
        assertEquals(
            mapOf(5 to 2, 10 to 0),
            normalizeMaColors(listOf(5, 10), mapOf(5 to 2, 10 to 2)),
        )
    }

    @Test
    fun `改色时与被占用者互换`() {
        val before = mapOf(5 to 0, 10 to 1)
        // 把 MA5 改成 1 号：MA10 拿到 MA5 原来的 0 号，一色一线
        assertEquals(
            mapOf(5 to 1, 10 to 0),
            assignMaColor(before, listOf(5, 10), 5, 1),
        )
    }

    @Test
    fun `目录外的期数和非法槽位不改颜色`() {
        val before = mapOf(5 to 0)
        assertEquals(before, assignMaColor(before, listOf(5), 7, 1))
        assertEquals(before, assignMaColor(before, listOf(5), 5, MA_COLOR_SLOT_COUNT))
    }

    // ---- 逐条小眼睛：只藏不删，且必须 ⊆ 目录 ----

    @Test
    fun `小眼睛归一丢掉目录里没有的期数`() {
        // 删了又加回来的场景：目录里已经没有 20，muted 里的 20 必须一起消失，
        // 否则用户「新加了一条」，图上却什么都不显示
        assertEquals(listOf(10), normalizeMuted(listOf(5, 10, 20), listOf(10, 77)))
        assertEquals(emptyList<Int>(), normalizeMuted(listOf(5, 10), emptyList()))
        // 顺序跟目录走（不跟 muted 的输入顺序）
        assertEquals(listOf(5, 20), normalizeMuted(listOf(5, 10, 20), listOf(20, 5)))
    }

    @Test
    fun `图上画的等于目录减掉闭眼的`() {
        val catalog = listOf(5, 10, 20)
        assertEquals(catalog, drawnPeriods(catalog, emptyList()))
        assertEquals(listOf(5, 20), drawnPeriods(catalog, listOf(10)))
        // 全闭上 = 一条都不画（但目录仍然完整，随时能点回来）
        assertEquals(emptyList<Int>(), drawnPeriods(catalog, catalog))
    }

    @Test
    fun `翻眼睛是可逆的, 且不会留下目录外的项`() {
        val catalog = listOf(5, 10)
        val hidden = toggleMuted(catalog, emptyList(), 10)
        assertEquals(listOf(10), hidden)
        // 再点一次回到全睁
        assertEquals(emptyList<Int>(), toggleMuted(catalog, hidden, 10))
        // 点目录外的期数：原样返回，不制造垃圾
        assertEquals(hidden, toggleMuted(catalog, hidden, 77))
    }

    @Test
    fun `全部隐藏与全部显示`() {
        val catalog = listOf(5, 10, 20)
        assertEquals(catalog, setAllMuted(catalog, hide = true))
        assertEquals(emptyList<Int>(), setAllMuted(catalog, hide = false))
        // 空目录全隐藏仍是空表（不崩、不留脏值）
        assertEquals(emptyList<Int>(), setAllMuted(emptyList(), hide = true))
    }
}
