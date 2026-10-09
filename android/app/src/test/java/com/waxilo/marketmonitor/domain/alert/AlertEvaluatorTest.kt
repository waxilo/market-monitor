package com.waxilo.marketmonitor.domain.alert

import com.waxilo.marketmonitor.domain.model.MarketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

private const val MINUTE = 60_000L

class AlertEvaluatorTest {

    private fun rule(
        condition: AlertCondition,
        threshold: String? = null,
        lower: String? = null,
        upper: String? = null,
        percent: String? = null,
        mode: AlertRepeatMode = AlertRepeatMode.EVERY_CROSS,
        enabled: Boolean = true,
    ) = AlertRule(
        id = 1,
        market = MarketType.SPOT,
        symbol = "BTCUSDT",
        name = "BTC 破十万",
        condition = condition,
        threshold = threshold?.let(::BigDecimal),
        rangeLower = lower?.let(::BigDecimal),
        rangeUpper = upper?.let(::BigDecimal),
        changePercent = percent?.let(::BigDecimal),
        repeatMode = mode,
        enabled = enabled,
    )

    @Test
    fun `首次观测只建立基线不触发`() {
        val rule = rule(AlertCondition.ABOVE, threshold = "100")
        val (decision, state) = AlertEvaluator.evaluate(rule, BigDecimal("105"), null, 0, AlertState())
        assertTrue(decision is AlertDecision.Silent)
        assertTrue("基线已满足", state.wasSatisfied)
        assertNull(state.lastTriggeredAt)
    }

    @Test
    fun `达到条件双向穿越都触发且方向按来源判定`() {
        val rule = rule(AlertCondition.REACH, threshold = "100")
        // 首次观测只建档：没有「上一次」就无从谈穿越
        val (first, s1) = AlertEvaluator.evaluate(rule, BigDecimal("90"), null, 0, AlertState())
        assertTrue(first is AlertDecision.Silent)
        assertEquals(BigDecimal("90"), s1.lastPrice)

        // 从下方涨到目标价 = 上破（正好压在目标价上也算碰到）
        val (up, s2) = AlertEvaluator.evaluate(rule, BigDecimal("100"), null, MINUTE, s1)
        assertEquals(AlertDirection.ABOVE, (up as AlertDecision.Triggered).direction)

        // 回到上方的过程不触发，再跌到目标价 = 下破
        val (quiet, s3) = AlertEvaluator.evaluate(rule, BigDecimal("105"), null, 2 * MINUTE, s2)
        assertTrue(quiet is AlertDecision.Silent)
        val (down, _) = AlertEvaluator.evaluate(rule, BigDecimal("100"), null, 3 * MINUTE, s3)
        assertEquals(AlertDirection.BELOW, (down as AlertDecision.Triggered).direction)
    }

    @Test
    fun `达到条件同侧移动不触发`() {
        val rule = rule(AlertCondition.REACH, threshold = "100")
        var state = AlertEvaluator.evaluate(rule, BigDecimal("90"), null, 0, AlertState()).second

        // 一直在目标价下方爬升但没碰到，不触发
        listOf("95", "99", "99.9").forEachIndexed { i, p ->
            val (decision, next) = AlertEvaluator.evaluate(rule, BigDecimal(p), null, (i + 1) * MINUTE, state)
            assertTrue("[$p] 不该触发", decision is AlertDecision.Silent)
            state = next
        }
        // 持续在上方也不触发（EVERY_CROSS 需要再次穿越）
        val (above, s2) = AlertEvaluator.evaluate(rule, BigDecimal("101"), null, 4 * MINUTE, state)
        assertTrue(above is AlertDecision.Triggered)
        val (staying, _) = AlertEvaluator.evaluate(rule, BigDecimal("103"), null, 5 * MINUTE, s2)
        assertTrue(staying is AlertDecision.Silent)
    }

    @Test
    fun `上破仅在穿越边沿触发`() {
        val rule = rule(AlertCondition.ABOVE, threshold = "100")
        var state = AlertEvaluator.evaluate(rule, BigDecimal("90"), null, 0, AlertState()).second

        val (below, s2) = AlertEvaluator.evaluate(rule, BigDecimal("95"), null, MINUTE, state)
        assertTrue(below is AlertDecision.Silent)

        val (crossed, s3) = AlertEvaluator.evaluate(rule, BigDecimal("101"), null, 2 * MINUTE, s2)
        val triggered = crossed as AlertDecision.Triggered
        assertEquals(AlertDirection.ABOVE, triggered.direction)
        assertEquals(BigDecimal("101"), triggered.price)
        assertEquals(2 * MINUTE, triggered.atMs)

        // 持续在上方不再重复触发（EVERY_CROSS 需要再次穿越）
        val (staying, _) = AlertEvaluator.evaluate(rule, BigDecimal("103"), null, 3 * MINUTE, s3)
        assertTrue(staying is AlertDecision.Silent)
    }

    @Test
    fun `ONCE 模式触发后关闭闸门`() {
        val rule = rule(AlertCondition.BELOW, threshold = "100", mode = AlertRepeatMode.ONCE)
        var state = AlertEvaluator.evaluate(rule, BigDecimal("110"), null, 0, AlertState()).second
        val (first, s1) = AlertEvaluator.evaluate(rule, BigDecimal("99"), null, MINUTE, state)
        assertTrue(first is AlertDecision.Triggered)
        assertTrue("闸门已落", s1.fired)

        state = AlertEvaluator.evaluate(rule, BigDecimal("110"), null, 2 * MINUTE, s1).second
        val (second, _) = AlertEvaluator.evaluate(rule, BigDecimal("98"), null, 3 * MINUTE, state)
        assertTrue("ONCE 不再触发", second is AlertDecision.Silent)
    }

    @Test
    fun `涨跌幅条件使用 24h 基准且下跌方向取负`() {
        val rise = rule(AlertCondition.RISE_BY, percent = "5")
        var state = AlertEvaluator.evaluate(rise, BigDecimal("100"), 1.0, 0, AlertState()).second
        val (fired, s1) = AlertEvaluator.evaluate(rise, BigDecimal("106"), 6.0, MINUTE, state)
        val triggered = fired as AlertDecision.Triggered
        assertEquals(AlertDirection.UP, triggered.direction)
        assertEquals(6.0, triggered.changePercent!!, 1e-9)

        val fall = rule(AlertCondition.FALL_BY, percent = "5")
        state = AlertEvaluator.evaluate(fall, BigDecimal("100"), 0.0, 0, AlertState()).second
        val (down, _) = AlertEvaluator.evaluate(fall, BigDecimal("94"), -6.0, MINUTE, state)
        assertTrue(down is AlertDecision.Triggered)
        assertEquals(AlertDirection.DOWN, (down as AlertDecision.Triggered).direction)
    }

    @Test
    fun `区间外按偏离方向给出方向标记`() {
        val rule = rule(AlertCondition.OUT_OF_RANGE, lower = "90", upper = "110")
        var state = AlertEvaluator.evaluate(rule, BigDecimal("100"), null, 0, AlertState()).second
        val (low, _) = AlertEvaluator.evaluate(rule, BigDecimal("89"), null, MINUTE, state)
        assertEquals(AlertDirection.BELOW, (low as AlertDecision.Triggered).direction)

        state = AlertEvaluator.evaluate(rule, BigDecimal("100"), null, 2 * MINUTE, state.copy(wasSatisfied = false)).second
        val (high, _) = AlertEvaluator.evaluate(rule, BigDecimal("111"), null, 3 * MINUTE, state)
        assertEquals(AlertDirection.ABOVE, (high as AlertDecision.Triggered).direction)
    }

    @Test
    fun `禁用与数据不足时不改变状态`() {
        val disabled = rule(AlertCondition.ABOVE, threshold = "100", enabled = false)
        val (d1, s1) = AlertEvaluator.evaluate(disabled, BigDecimal("200"), null, 0, AlertState())
        assertTrue(d1 is AlertDecision.Silent)
        assertEquals(AlertState(), s1)

        val missingTarget = AlertRule(
            id = 2,
            market = MarketType.FUTURES,
            symbol = "ETHUSDT",
            name = "缺目标价",
            condition = AlertCondition.ABOVE,
        )
        val (d2, _) = AlertEvaluator.evaluate(missingTarget, BigDecimal("200"), null, 0, AlertState())
        assertTrue(d2 is AlertDecision.Silent)

        val (d3, _) = AlertEvaluator.evaluate(
            rule(AlertCondition.RISE_BY, percent = "5"),
            BigDecimal("100"),
            null,
            0,
            AlertState(),
        )
        assertTrue("拿不到涨跌幅时不误判", d3 is AlertDecision.Silent)
    }
}

class AlertRuleValidatorTest {

    private val tick = BigDecimal("0.01")

    private fun base(condition: AlertCondition, threshold: String? = "100") = AlertRule(
        id = 1,
        market = MarketType.SPOT,
        symbol = "BTCUSDT",
        name = "测试",
        condition = condition,
        threshold = threshold?.let(::BigDecimal),
    )

    @Test
    fun `合法规则通过校验`() {
        assertNull(AlertRuleValidator.validate(base(AlertCondition.REACH), tick))
        assertNull(AlertRuleValidator.validate(base(AlertCondition.ABOVE), tick))
        assertNull(AlertRuleValidator.validate(base(AlertCondition.BELOW), tick))
    }

    @Test
    fun `目标价必须是 tickSize 整数倍`() {
        val problem = AlertRuleValidator.validate(base(AlertCondition.ABOVE, threshold = "100.005"), tick)
        assertTrue("实际：$problem", problem != null && problem.contains("tickSize"))
    }

    @Test
    fun `缺少目标价时给出可展示文案`() {
        assertTrue(AlertRuleValidator.validate(base(AlertCondition.ABOVE, threshold = null), tick)?.isNotBlank() == true)
        assertTrue(AlertRuleValidator.validate(base(AlertCondition.RISE_BY, threshold = null).copy(changePercent = null), tick)?.isNotBlank() == true)
    }

    @Test
    fun `区间上下界顺序与空值校验`() {
        val noBounds = base(AlertCondition.OUT_OF_RANGE, threshold = null)
        assertTrue(AlertRuleValidator.validate(noBounds, tick)?.isNotBlank() == true)

        val reversed = noBounds.copy(rangeLower = BigDecimal("200"), rangeUpper = BigDecimal("100"))
        assertTrue(AlertRuleValidator.validate(reversed, tick)?.isNotBlank() == true)

        val oneSided = noBounds.copy(rangeUpper = BigDecimal("100"))
        assertNull(AlertRuleValidator.validate(oneSided, tick))
    }

    @Test
    fun `无 tickSize 信息时不做精度校验`() {
        assertNull(AlertRuleValidator.validate(base(AlertCondition.ABOVE, threshold = "100.005"), null))
    }
}
