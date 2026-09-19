package com.waxilo.marketmonitor.domain.webhook

import java.math.BigDecimal

/** 一个 Webhook 端点配置（PRD FR-4.1）。URL 含 secret，落库前需加密。 */
data class WebhookEndpoint(
    val id: Long = 0L,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** 自定义 JSON 模板；为空表示使用 [WebhookTemplate.DEFAULT_PAYLOAD]。 */
    val template: String = "",
    /** 绑定的预警规则 id；为空表示全局（所有规则触发都推送）。 */
    val boundRuleIds: List<Long> = emptyList(),
    val createdAt: Long = 0L,
) {
    fun appliesTo(ruleId: Long): Boolean = enabled && (boundRuleIds.isEmpty() || ruleId in boundRuleIds)

    val effectiveTemplate: String get() = template.ifBlank { WebhookTemplate.DEFAULT_PAYLOAD }
}

/** 一次触发事件，作为模板变量来源。所有金额已按显示精度格式化。 */
data class AlertEvent(
    val alertName: String,
    val marketLabel: String,
    val symbol: String,
    /** above / below / up / down，与 [com.waxilo.marketmonitor.domain.alert.AlertDirection.key] 一致。 */
    val directionKey: String,
    val price: BigDecimal?,
    val threshold: BigDecimal?,
    val changePercent: Double?,
    /** ISO-8601 UTC，如 2026-09-19T08:00:00Z。 */
    val triggeredAtIso: String,
    val timestampMs: Long,
)

sealed interface RenderResult {
    val text: String

    data class Ok(override val text: String) : RenderResult
    /** 模板里有未知占位符：发送方可据此提示用户，但仍按原文发送以免静默丢字段。 */
    data class Missing(override val text: String, val unknownKeys: Set<String>) : RenderResult
}

/**
 * Webhook 模板渲染（PRD FR-4.1）：`{{var}}` 占位符替换 + JSON 转义 + 合法性校验。
 * 纯函数，便于在不发请求的前提下验证用户模板。
 */
object WebhookTemplate {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}""")

    val DEFAULT_PAYLOAD = """
{
  "event": "price_alert",
  "symbol": "{{symbol}}",
  "market": "{{market}}",
  "direction": "{{direction}}",
  "threshold": {{thresholdRaw}},
  "currentPrice": {{priceRaw}},
  "changePercent24h": {{changePercentRaw}},
  "alertName": "{{alertName}}",
  "triggeredAt": "{{triggeredAt}}",
  "timestamp": {{timestampRaw}}
}
    """.trimIndent()

    /** 可用变量：带 Raw 后缀的输出 JSON 裸值（数字或 null），用于未加引号的字段。 */
    fun variables(event: AlertEvent): Map<String, String> = linkedMapOf(
        "event" to "price_alert",
        "symbol" to event.symbol,
        "market" to event.marketLabel,
        "direction" to event.directionKey,
        "threshold" to (event.threshold?.toPlainString() ?: ""),
        "thresholdRaw" to (event.threshold?.toPlainString() ?: "null"),
        "price" to (event.price?.toPlainString() ?: ""),
        "priceRaw" to (event.price?.toPlainString() ?: "null"),
        "changePercent" to (event.changePercent?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""),
        "changePercentRaw" to (event.changePercent?.toString() ?: "null"),
        "alertName" to event.alertName,
        "triggeredAt" to event.triggeredAtIso,
        "timestamp" to event.timestampMs.toString(),
        "timestampRaw" to event.timestampMs.toString(),
    )

    fun render(template: String, event: AlertEvent): RenderResult =
        render(template, variables(event))

    fun render(template: String, vars: Map<String, String>): RenderResult {
        val unknown = LinkedHashSet<String>()
        val text = PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            val value = vars[key]
            if (value == null) {
                unknown.add(key)
                match.value
            } else {
                jsonEscape(value)
            }
        }
        return if (unknown.isEmpty()) RenderResult.Ok(text) else RenderResult.Missing(text, unknown)
    }

    /** 模板里出现但变量表没有的占位符名（用于配置页提示）。 */
    fun unknownPlaceholders(template: String, vars: Map<String, String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (match in PLACEHOLDER.findAll(template)) {
            val key = match.groupValues[1]
            if (!vars.containsKey(key)) out.add(key)
        }
        return out
    }

    /** 返回 null 表示模板可用；否则为可直接展示的中文错误。 */
    fun validateTemplate(template: String, event: AlertEvent): String? {
        if (template.isBlank()) return "模板不能为空"
        val unknown = unknownPlaceholders(template, variables(event))
        if (unknown.isNotEmpty()) return "未知变量：${unknown.joinToString(", ")}"
        return try {
            val rendered = render(template, event)
            kotlinx.serialization.json.Json.parseToJsonElement(rendered.text)
            null
        } catch (e: Exception) {
            "模板渲染后不是合法 JSON：${e.message ?: "解析失败"}"
        }
    }

    /** 只转义字符串值：占位符在模板中的引号由用户负责。 */
    fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * URL 校验：默认只允许 HTTPS；allowInsecure 对应设置里的「允许局域网 http 调试」。
     * 返回 null 表示合法。
     */
    fun validateUrl(raw: String, allowInsecure: Boolean = false): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "请填写 Webhook 地址"
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return "地址格式不合法"
        }
        val scheme = uri.scheme?.lowercase() ?: return "缺少协议（https://）"
        if (scheme != "https" && !(allowInsecure && scheme == "http")) {
            return if (scheme == "http") "仅支持 HTTPS，如需局域网调试请在设置里放开 http" else "不支持的协议：$scheme"
        }
        if (uri.host.isNullOrBlank()) return "缺少主机名"
        return null
    }

    fun validateName(name: String): String? = when {
        name.isBlank() -> "请填写端点名称"
        name.length > 40 -> "名称过长（最多 40 字）"
        else -> null
    }
}
