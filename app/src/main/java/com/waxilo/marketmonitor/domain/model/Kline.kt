package com.waxilo.marketmonitor.domain.model

import java.math.BigDecimal

/**
 * 单根蜡烛。价格与成交量保留币安返回的原始精度（字符串 → BigDecimal），
 * 显示层再按 tickSize 规则格式化；Double 视图仅供图表与指标计算使用。
 */
data class Kline(
    val openTime: Long,
    val closeTime: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val quoteVolume: BigDecimal,
    val trades: Long = 0,
    /** 该蜡烛是否已收盘；未收盘的最后一根会被实时流覆盖。 */
    val closed: Boolean = true,
) {
    val midPrice: BigDecimal
        get() = high.add(low).divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL128)
}

fun Kline.openDouble(): Double = open.toDouble()
fun Kline.highDouble(): Double = high.toDouble()
fun Kline.lowDouble(): Double = low.toDouble()
fun Kline.closeDouble(): Double = close.toDouble()
fun Kline.volumeDouble(): Double = volume.toDouble()
