package com.waxilo.marketmonitor.domain.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class WebhookTemplateTest {

    private fun event(
        price: BigDecimal? = BigDecimal("100123.45"),
        threshold: BigDecimal? = BigDecimal("100000"),
        change: Double? = 2.31,
        name: String = "BTC 破十万",
    ) = AlertEvent(
        alertName = name,
        marketLabel = "现货",
        symbol = "BTCUSDT",
        directionKey = "above",
        price = price,
        threshold = threshold,
        changePercent = change,
        triggeredAtIso = "2026-09-19T08:00:00Z",
        timestampMs = 1789000000000L,
    )

    @Test
    fun `默认模板渲染出合法 JSON 且数值为裸值`() {
        val result = WebhookTemplate.render(WebhookTemplate.DEFAULT_PAYLOAD, event())
        assertTrue(result is RenderResult.Ok)

        val obj = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("BTCUSDT", obj.getValue("symbol").jsonPrimitive.content)
        assertEquals("above", obj.getValue("direction").jsonPrimitive.content)
        assertEquals("100000", obj.getValue("threshold").jsonPrimitive.content)
        assertEquals("100123.45", obj.getValue("currentPrice").jsonPrimitive.content)
        assertEquals("1789000000000", obj.getValue("timestamp").jsonPrimitive.content)
        // 数值字段不带引号，才能被 Server酱 / Discord 等下游当数字处理
        assertTrue(obj.getValue("threshold").jsonPrimitive.isString.not())
    }

    @Test
    fun `字符串变量做 JSON 转义，避免模板被用户输入破坏`() {
        val result = WebhookTemplate.render(
            WebhookTemplate.DEFAULT_PAYLOAD,
            event(name = "他说\"涨\"了\n第二行"),
        )
        val obj = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("他说\"涨\"了\n第二行", obj.getValue("alertName").jsonPrimitive.content)
    }

    @Test
    fun `缺失的阈值渲染为 null 而不是空串导致 JSON 断裂`() {
        val result = WebhookTemplate.render(WebhookTemplate.DEFAULT_PAYLOAD, event(threshold = null, change = null))
        val obj = Json.parseToJsonElement(result.text).jsonObject
        assertNull(obj.getValue("threshold").jsonPrimitive.contentOrNull)
        assertNull(obj.getValue("changePercent24h").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `未知占位符保留原文并上报`() {
        val result = WebhookTemplate.render("""{"a": "{{nope}}", "b": "{{symbol}}"}""", event())
        assertTrue(result is RenderResult.Missing)
        val missing = result as RenderResult.Missing
        assertEquals(setOf("nope"), missing.unknownKeys)
        assertTrue(missing.text.contains("{{nope}}"))
        assertTrue(missing.text.contains("BTCUSDT"))
    }

    @Test
    fun `变量名允许空格`() {
        val result = WebhookTemplate.render("""{"s":"{{ symbol }}"}""", event())
        assertEquals("""{"s":"BTCUSDT"}""", result.text)
    }

    @Test
    fun `模板校验给出可直接展示的中文错误`() {
        assertNull(WebhookTemplate.validateTemplate(WebhookTemplate.DEFAULT_PAYLOAD, event()))
        assertNotNull(WebhookTemplate.validateTemplate("""{"x": 1,,}""", event()))
        assertTrue(
            WebhookTemplate.validateTemplate("""{"a":"{{unknown}}"}""", event())!!
                .contains("未知变量"),
        )
        assertNotNull(WebhookTemplate.validateTemplate("   ", event()))
    }

    @Test
    fun `仅允许 HTTPS，局域网调试需显式放开`() {
        assertNull(WebhookTemplate.validateUrl("https://hooks.example.com/abc"))
        assertNotNull(WebhookTemplate.validateUrl("http://192.168.1.10:8080/hook"))
        assertNull(WebhookTemplate.validateUrl("http://192.168.1.10:8080/hook", allowInsecure = true))
        assertNotNull(WebhookTemplate.validateUrl("ftp://example.com"))
        assertNotNull(WebhookTemplate.validateUrl(""))
        assertNotNull(WebhookTemplate.validateUrl("https://"))
        assertNotNull(WebhookTemplate.validateUrl("https://有 空格.com"))
    }

    @Test
    fun `端点按绑定规则或全局生效`() {
        val global = WebhookEndpoint(id = 1, name = "全局", url = "https://a")
        assertTrue(global.appliesTo(99))

        val bound = global.copy(id = 2, boundRuleIds = listOf(7L))
        assertTrue(bound.appliesTo(7))
        assertTrue(!bound.appliesTo(8))
        assertTrue(bound.copy(enabled = false).let { !it.appliesTo(7) })
        assertTrue(global.template.isEmpty())
        assertEquals(WebhookTemplate.DEFAULT_PAYLOAD, global.effectiveTemplate)
    }

    @Test
    fun `名称校验`() {
        assertNull(WebhookTemplate.validateName("飞书群"))
        assertNotNull(WebhookTemplate.validateName("  "))
        assertNotNull(WebhookTemplate.validateName("长".repeat(41)))
    }
}
