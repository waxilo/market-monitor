package com.waxilo.marketmonitor.data.local.room

import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 线型（实线 / 虚线）在**存档 ↔ 领域对象**之间不掉链子。
 *
 * 为什么要单测这条：`dashed` 是后加的列，两条存储路径各走各的映射
 * （`chart_line` 走 [ChartLineEntity]，`alert_rule` 走 [AlertRuleEntity]）。
 * 漏一处的结果是「切了虚线、重启就没了」，而这在界面上完全看不出来 ——
 * 用户只会觉得「这个开关时灵时不灵」。
 *
 * 断言一律落在**值**上：实体里是 0/1，领域对象里是 Boolean，两边都要对得上。
 */
class LineDashedMappingTest {

    private fun line(dashed: Boolean) = ChartLine(
        market = MarketType.SPOT,
        symbol = "BTCUSDT",
        kind = ChartLineKind.HORIZONTAL,
        price = BigDecimal("61000"),
        dashed = dashed,
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `画线默认是实线`() {
        // 默认值必须与桌面端 `PriceLine.dashed` 缺省同口径：不传 = 实线
        val fresh = ChartLine(
            market = MarketType.SPOT,
            symbol = "BTCUSDT",
            kind = ChartLineKind.HORIZONTAL,
            price = BigDecimal("61000"),
            createdAt = 0,
        )
        assertFalse(fresh.dashed)
        assertEquals(0, fresh.toEntity().dashed)
    }

    @Test
    fun `画线的虚线落到实体再读回来还是虚线`() {
        val entity = line(dashed = true).toEntity()
        assertEquals(1, entity.dashed)
        val back = entity.toDomain()
        assertNotNull(back)
        assertTrue(back!!.dashed)
        // 纯样式列不该动到别的字段
        assertEquals(BigDecimal("61000"), back.price)
        assertEquals(ChartLineKind.HORIZONTAL, back.kind)
    }

    @Test
    fun `画线的实线落到实体是 0`() {
        val entity = line(dashed = false).toEntity()
        assertEquals(0, entity.dashed)
        assertFalse(entity.toDomain()!!.dashed)
    }

    @Test
    fun `v13 之前的老行（dashed 列缺失被补 0）读成实线`() {
        // 迁移写的是 NOT NULL DEFAULT 0，等价于这一行
        val old = ChartLineEntity(
            id = 1,
            market = MarketType.SPOT.key,
            symbol = "BTCUSDT",
            kind = ChartLineKind.HORIZONTAL.key,
            price = "61000",
            t1 = null,
            p1 = null,
            t2 = null,
            p2 = null,
            createdAt = 0,
        )
        assertEquals(0, old.dashed)
        assertFalse(old.toDomain()!!.dashed)
    }

    private fun rule(dashed: Boolean) = AlertRule(
        market = MarketType.SPOT,
        symbol = "BTCUSDT",
        name = "61000",
        condition = AlertCondition.REACH,
        threshold = BigDecimal("61000"),
        dashed = dashed,
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `预警线的虚线落到实体再读回来还是虚线`() {
        val entity = rule(dashed = true).toEntity()
        assertEquals(1, entity.dashed)
        assertTrue(entity.toDomain().dashed)
    }

    @Test
    fun `预警线的实线落到实体是 0`() {
        val entity = rule(dashed = false).toEntity()
        assertEquals(0, entity.dashed)
        assertFalse(entity.toDomain().dashed)
    }
}
