package com.waxilo.marketmonitor.domain.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/**
 * 价格与数量展示规则（PRD FR-1.1）。
 * 默认 2 位小数；tickSize 细于 0.01 时放开到 tickSize 的有效位数，
 * 否则低价币会显示成 `0.00`、涨跌幅恒为 0%。
 */
object PriceFormatter {

    const val DEFAULT_DECIMALS = 2
    const val MAX_DECIMALS = 12

    fun decimalsFor(tickSize: BigDecimal?): Int {
        if (tickSize == null) return DEFAULT_DECIMALS
        val scale = tickSize.stripTrailingZeros().scale().coerceAtLeast(0)
        return if (scale <= DEFAULT_DECIMALS) DEFAULT_DECIMALS else minOf(scale, MAX_DECIMALS)
    }

    fun format(price: BigDecimal?, tickSize: BigDecimal?): String {
        if (price == null) return NO_DATA
        return round(price, decimalsFor(tickSize)).toPlainString()
    }

    /** 涨跌幅：固定 2 位并带符号，符号使色盲用户也能区分涨跌（PRD 可访问性要求）。 */
    fun formatChange(changePercent: Double?): String {
        if (changePercent == null || changePercent.isNaN()) return NO_DATA
        val rounded = round(BigDecimal(changePercent.toString()), DEFAULT_DECIMALS)
        val sign = when {
            rounded.signum() > 0 -> "+"
            rounded.signum() < 0 -> ""
            else -> ""
        }
        return "$sign${rounded.toPlainString()}%"
    }

    /** 成交额等大数值用 K/M/B/T 缩写，避免列表列宽随数据跳动。 */
    fun formatCompact(value: BigDecimal?): String {
        if (value == null) return NO_DATA
        val magnitude = value.abs()
        val unit = when {
            magnitude >= TRILLION -> TRILLION to "T"
            magnitude >= BILLION -> BILLION to "B"
            magnitude >= MILLION -> MILLION to "M"
            magnitude >= THOUSAND -> THOUSAND to "K"
            else -> return round(value, DEFAULT_DECIMALS).toPlainString()
        }
        return value.divide(unit.first, DEFAULT_DECIMALS, RoundingMode.HALF_UP).toPlainString() + unit.second
    }

    /** 数量：去掉无意义的尾零，保留原始精度。 */
    fun formatQuantity(value: BigDecimal?): String {
        if (value == null) return NO_DATA
        return value.stripTrailingZeros().toPlainString()
    }

    fun toBigDecimalOrEmpty(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null
        return raw.toBigDecimalOrNull()
    }

    fun Double.asPercentText(): String = formatChange(this)

    fun round(value: BigDecimal, decimals: Int): BigDecimal =
        value.setScale(decimals, RoundingMode.HALF_UP)

    fun localeNumber(value: Double, decimals: Int = DEFAULT_DECIMALS): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private const val NO_DATA = "--"

    private val THOUSAND = BigDecimal("1E3")
    private val MILLION = BigDecimal("1E6")
    private val BILLION = BigDecimal("1E9")
    private val TRILLION = BigDecimal("1E12")
}
