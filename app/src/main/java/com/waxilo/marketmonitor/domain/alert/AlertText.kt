package com.waxilo.marketmonitor.domain.alert

import com.waxilo.marketmonitor.domain.format.PriceFormatter
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预警文案（通知栏与消息中心共用一套）。
 *
 * 放在 domain 是因为两处展示必须同口径：通知里写「上破」、消息中心写「突破」会让用户
 * 以为是两种不同事件。金额按数值自身精度显示，低价币不会显示成 0.00（PRD 全 App 统一精度）。
 */
object AlertText {

    fun directionLabel(direction: AlertDirection): String = when (direction) {
        AlertDirection.ABOVE -> "上破"
        AlertDirection.BELOW -> "下破"
        AlertDirection.UP -> "涨幅达到"
        AlertDirection.DOWN -> "跌幅达到"
    }

    /** 规则的条件描述，用于列表副标题。 */
    fun conditionLabel(rule: AlertRule): String = when (rule.condition) {
        AlertCondition.ABOVE -> "上破 ${priceText(rule.threshold)}"
        AlertCondition.BELOW -> "下破 ${priceText(rule.threshold)}"
        AlertCondition.OUT_OF_RANGE -> "超出 ${priceText(rule.rangeLower)} ~ ${priceText(rule.rangeUpper)}"
        AlertCondition.RISE_BY -> "24h 涨幅 ≥ ${percentText(rule.changePercent)}"
        AlertCondition.FALL_BY -> "24h 跌幅 ≥ ${percentText(rule.changePercent)}"
    }

    fun repeatLabel(rule: AlertRule): String = when (rule.repeatMode) {
        AlertRepeatMode.ONCE -> "单次"
        AlertRepeatMode.EVERY_CROSS -> "每次穿越"
        AlertRepeatMode.REPEAT -> "每 ${rule.cooldownMinutes} 分钟重复"
    }

    fun summary(message: AlertMessage): String {
        val threshold = message.threshold?.let { " ${priceText(it)}" }.orEmpty()
        val change = message.changePercent?.let { "，24h ${PriceFormatter.formatChange(it)}" }.orEmpty()
        return "${message.symbol}${directionLabel(message.direction)}$threshold：现价 ${priceText(message.price)}$change"
    }

    fun timeOf(epochMs: Long): String = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(epochMs)) }

    /** Webhook 报文用的 ISO-8601 UTC，接收端不必再猜时区。 */
    fun isoUtc(epochMs: Long): String = synchronized(ISO_FORMAT) { ISO_FORMAT.format(Date(epochMs)) }

    fun priceText(price: BigDecimal?): String {
        if (price == null) return PriceFormatter.NO_DATA
        val scale = maxOf(price.stripTrailingZeros().scale(), PriceFormatter.DEFAULT_DECIMALS)
            .coerceAtMost(PriceFormatter.MAX_DECIMALS)
        return PriceFormatter.format(price, BigDecimal.ONE.movePointLeft(scale))
    }

    private fun percentText(percent: BigDecimal?): String =
        if (percent == null) PriceFormatter.NO_DATA else "${percent.toPlainString()}%"

    // 通知栏在后台线程取文案、页面在主线程取，SimpleDateFormat 非线程安全，因此调用处加锁
    private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private val ISO_FORMAT: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
}
