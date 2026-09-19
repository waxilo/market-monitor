package com.waxilo.marketmonitor.domain.alert

import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class AlertTextTest {

    @Test
    fun `金额按自身精度显示，低价币不会变成零`() {
        assertEquals("--", AlertText.priceText(null))
        assertEquals("65000.10", AlertText.priceText(BigDecimal("65000.10")))
        assertEquals("0.00456789", AlertText.priceText(BigDecimal("0.00456789")))
        // 超出最大位数时截到 12 位，而不是回落到默认的 2 位（那样又会显示 0.00）
        assertEquals("0.000001234568", AlertText.priceText(BigDecimal("0.00000123456789")))
    }

    @Test
    fun `整数阈值补齐两位小数`() {
        assertEquals("100000.00", AlertText.priceText(BigDecimal("100000")))
        assertEquals("100000.00", AlertText.priceText(BigDecimal("1E+5")))
    }

    @Test
    fun `条件文案覆盖五类条件`() {
        val rule = AlertRule(
            market = MarketType.FUTURES,
            symbol = "ETHUSDT",
            name = "ETH",
            condition = AlertCondition.OUT_OF_RANGE,
            rangeLower = BigDecimal("2900"),
            rangeUpper = BigDecimal("3100.5"),
            repeatMode = AlertRepeatMode.REPEAT,
            cooldownMinutes = 15,
        )
        assertEquals("超出 2900.00 ~ 3100.50", AlertText.conditionLabel(rule))
        assertEquals("每 15 分钟重复", AlertText.repeatLabel(rule))
    }

    @Test
    fun `摘要与通知栏共用同一口径`() {
        val message = AlertMessage(
            ruleId = 1,
            market = MarketType.SPOT,
            symbol = "BTCUSDT",
            alertName = "破十万",
            direction = AlertDirection.ABOVE,
            price = BigDecimal("100000.5"),
            threshold = BigDecimal("100000"),
            changePercent = 1.23,
            triggeredAt = 0L,
        )
        assertEquals(
            "BTCUSDT上破 100000.00：现价 100000.50，24h +1.23%",
            AlertText.summary(message),
        )
    }

    @Test
    fun `缺少涨跌幅时摘要不追加百分比，时间戳为 UTC`() {
        val message = AlertMessage(
            ruleId = 1,
            market = MarketType.SPOT,
            symbol = "BNBUSDT",
            alertName = "BNB",
            direction = AlertDirection.BELOW,
            price = BigDecimal("600.1"),
            threshold = BigDecimal("601"),
            changePercent = null,
            triggeredAt = 0L,
        )
        assertEquals("BNBUSDT下破 601.00：现价 600.10", AlertText.summary(message))
        assertEquals("1970-01-01T00:00:00Z", AlertText.isoUtc(0L))
    }
}
