package com.waxilo.marketmonitor.domain.alert

import java.math.BigDecimal

/**
 * 预警判定纯函数（PRD FR-3.2）。
 *
 * 语义约定：
 * - 只在「不满足 → 满足」的边沿触发；规则创建时价格已在阈值另一侧不成立，
 *   因此首次观测只用于建立基线，不触发，避免新增规则即刷屏。
 * - 冷却期内一律静默；REPEAT 模式在条件持续满足时于冷却到期后再次触发。
 * - changePercent 为空（拿不到 24h 基准价）时，涨跌幅类条件静默跳过而不是误判为 0。
 */
object AlertEvaluator {

    fun cooldownMs(rule: AlertRule): Long =
        rule.cooldownMinutes.coerceAtLeast(0) * 60_000L

    fun evaluate(
        rule: AlertRule,
        price: BigDecimal?,
        changePercent: Double?,
        nowMs: Long,
        state: AlertState,
    ): Pair<AlertDecision, AlertState> {
        val satisfied = satisfiedOf(rule, price, changePercent)
        if (!rule.enabled || satisfied == null || price == null) {
            return AlertDecision.Silent to state
        }
        if (state.lastPrice == null) {
            // lastPrice 为空即"从未观测过"，本轮只记基线，避免新建规则当场刷屏
            return AlertDecision.Silent to state.copy(wasSatisfied = satisfied, lastPrice = price)
        }

        val inCooldown = state.lastTriggeredAt?.let { nowMs - it < cooldownMs(rule) } == true
        val edge = satisfied && !state.wasSatisfied
        val repeatDue = satisfied && state.wasSatisfied &&
            rule.repeatMode == AlertRepeatMode.REPEAT && !inCooldown
        val blockedOnce = state.fired && rule.repeatMode == AlertRepeatMode.ONCE

        val shouldFire = satisfied && !inCooldown && !blockedOnce && (edge || repeatDue)
        if (!shouldFire) {
            return AlertDecision.Silent to state.copy(wasSatisfied = satisfied, lastPrice = price)
        }

        val decision = AlertDecision.Triggered(
            rule = rule,
            direction = directionOf(rule, price),
            price = price,
            changePercent = changePercent,
            atMs = nowMs,
        )
        val newState = state.copy(
            wasSatisfied = true,
            lastPrice = price,
            lastTriggeredAt = nowMs,
            fired = rule.repeatMode == AlertRepeatMode.ONCE,
        )
        return decision to newState
    }

    /** null 表示数据不足，无法判定。 */
    private fun satisfiedOf(
        rule: AlertRule,
        price: BigDecimal?,
        changePercent: Double?,
    ): Boolean? = when (rule.condition) {
        AlertCondition.ABOVE -> {
            val target = rule.threshold
            if (target == null || price == null) null else price >= target
        }

        AlertCondition.BELOW -> {
            val target = rule.threshold
            if (target == null || price == null) null else price <= target
        }

        AlertCondition.OUT_OF_RANGE -> {
            val lower = rule.rangeLower
            val upper = rule.rangeUpper
            if (price == null || (lower == null && upper == null)) null
            else (lower != null && price < lower) || (upper != null && price > upper)
        }

        AlertCondition.RISE_BY -> {
            val target = rule.changePercent?.toDouble()
            if (target == null || changePercent == null) null else changePercent >= target
        }

        AlertCondition.FALL_BY -> {
            val target = rule.changePercent?.toDouble()?.let { kotlin.math.abs(it) }
            if (target == null || changePercent == null) null else changePercent <= -target
        }
    }

    private fun directionOf(rule: AlertRule, price: BigDecimal): AlertDirection = when (rule.condition) {
        AlertCondition.ABOVE -> AlertDirection.ABOVE
        AlertCondition.BELOW -> AlertDirection.BELOW
        AlertCondition.RISE_BY -> AlertDirection.UP
        AlertCondition.FALL_BY -> AlertDirection.DOWN
        AlertCondition.OUT_OF_RANGE -> {
            val lower = rule.rangeLower
            val upper = rule.rangeUpper
            when {
                lower != null && price < lower -> AlertDirection.BELOW
                upper != null && price > upper -> AlertDirection.ABOVE
                else -> AlertDirection.ABOVE
            }
        }
    }
}

/** 规则完整性与精度校验（PRD FR-3.1 目标价按 tickSize 校验）。 */
object AlertRuleValidator {

    /** 返回 null 表示规则合法；否则为可直接展示的中文错误文案。 */
    fun validate(rule: AlertRule, priceTickSize: BigDecimal?): String? {
        if (rule.symbol.isBlank()) return "请选择交易对"
        if (rule.name.isBlank()) return "请填写预警名称"
        if (rule.cooldownMinutes < 0) return "冷却时间不能为负"

        val tickProblem = rule.threshold?.let { tickProblem(it, priceTickSize) }
            ?: rule.rangeLower?.let { tickProblem(it, priceTickSize) }
            ?: rule.rangeUpper?.let { tickProblem(it, priceTickSize) }
        if (tickProblem != null) return tickProblem

        return when (rule.condition) {
            AlertCondition.ABOVE, AlertCondition.BELOW ->
                if (rule.threshold == null || rule.threshold.signum() <= 0) "请填写有效的目标价格" else null

            AlertCondition.OUT_OF_RANGE -> {
                val lower = rule.rangeLower
                val upper = rule.rangeUpper
                when {
                    lower == null && upper == null -> "请至少填写区间上界或下界"
                    lower != null && upper != null && lower >= upper -> "区间下界必须小于上界"
                    else -> null
                }
            }

            AlertCondition.RISE_BY, AlertCondition.FALL_BY -> {
                val percent = rule.changePercent
                when {
                    percent == null || percent.signum() <= 0 -> "请填写大于 0 的涨跌幅百分比"
                    percent > BigDecimal("10000") -> "涨跌幅设置过大，请确认"
                    else -> null
                }
            }
        }
    }

    private fun tickProblem(price: BigDecimal, tickSize: BigDecimal?): String? {
        if (tickSize == null || tickSize.signum() == 0) return null
        val remainder = price.remainder(tickSize)
        return if (remainder.signum() == 0) null else "目标价需为 tickSize（$tickSize）的整数倍"
    }
}
