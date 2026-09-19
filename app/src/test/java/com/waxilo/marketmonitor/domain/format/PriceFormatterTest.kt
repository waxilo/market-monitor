package com.waxilo.marketmonitor.domain.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PriceFormatterTest {

    @Test
    fun `默认两位小数，tickSize 更粗时也保持两位`() {
        assertEquals(2, PriceFormatter.decimalsFor(null))
        assertEquals(2, PriceFormatter.decimalsFor(BigDecimal("0.01")))
        assertEquals(2, PriceFormatter.decimalsFor(BigDecimal("0.1")))
        assertEquals(2, PriceFormatter.decimalsFor(BigDecimal("1")))
        assertEquals("1234.57", PriceFormatter.format(BigDecimal("1234.5678"), BigDecimal("0.01")))
    }

    @Test
    fun `低价币按 tickSize 放开位数，避免显示 0 与虚假零涨跌`() {
        assertEquals(3, PriceFormatter.decimalsFor(BigDecimal("0.001")))
        assertEquals(5, PriceFormatter.decimalsFor(BigDecimal("0.00001")))
        assertEquals(8, PriceFormatter.decimalsFor(BigDecimal("0.00000001")))
        assertEquals(8, PriceFormatter.decimalsFor(BigDecimal("0.000000010")))
        assertEquals("0.00004321", PriceFormatter.format(BigDecimal("0.00004321"), BigDecimal("0.00000001")))
        assertEquals("0.00004", PriceFormatter.format(BigDecimal("0.00004321"), BigDecimal("0.00001")))
    }

    @Test
    fun `极端细的 tickSize 被上限截断`() {
        // 12 位已远超可读需要，再放开只会让列表列宽失控
        assertEquals(12, PriceFormatter.decimalsFor(BigDecimal("1E-15")))
        assertEquals(12, PriceFormatter.decimalsFor(BigDecimal("1E-20")))
    }

    @Test
    fun `缺失数据显示占位符而非零`() {
        assertEquals("--", PriceFormatter.format(null, BigDecimal("0.01")))
        assertEquals("--", PriceFormatter.formatChange(null))
        assertEquals("--", PriceFormatter.formatCompact(null))
    }

    @Test
    fun `涨跌幅带符号且固定两位`() {
        assertEquals("+2.31%", PriceFormatter.formatChange(2.314))
        assertEquals("-1.50%", PriceFormatter.formatChange(-1.5))
        assertEquals("0.00%", PriceFormatter.formatChange(0.0))
        assertEquals("+0.07%", PriceFormatter.formatChange(0.066))
        assertEquals("--", PriceFormatter.formatChange(Double.NaN))
    }

    @Test
    fun `成交额用缩写控制列宽`() {
        assertEquals("999.00", PriceFormatter.formatCompact(BigDecimal("999")))
        assertEquals("1.23K", PriceFormatter.formatCompact(BigDecimal("1234")))
        assertEquals("1.23M", PriceFormatter.formatCompact(BigDecimal("1234567")))
        assertEquals("-2.50B", PriceFormatter.formatCompact(BigDecimal("-2500000000")))
        assertEquals("5.00T", PriceFormatter.formatCompact(BigDecimal("5000000000000")))
        assertEquals("0.00", PriceFormatter.formatCompact(BigDecimal("0")))
    }

    @Test
    fun `数量去掉无意义尾零`() {
        assertEquals("1.5", PriceFormatter.formatQuantity(BigDecimal("1.500")))
        assertEquals("0", PriceFormatter.formatQuantity(BigDecimal("0.000")))
        assertEquals("0.0001", PriceFormatter.formatQuantity(BigDecimal("0.0001")))
    }

    @Test
    fun `非法价格字符串安全降级`() {
        assertNull(PriceFormatter.toBigDecimalOrEmpty("abc"))
        assertNull(PriceFormatter.toBigDecimalOrEmpty(""))
        assertEquals(BigDecimal("1.5"), PriceFormatter.toBigDecimalOrEmpty("1.5"))
    }
}
