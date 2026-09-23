package com.waxilo.marketmonitor.data.remote

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 币安系接口的请求签名（Aster 同构）：HMAC SHA256(queryString, secret) 的十六进制小写。
 * 纯 JVM 实现，无 Android 依赖，可直接跑单元测试。
 */
object BinanceSigner {

    fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 拼出参与签名的 query string：顺序即签名顺序，服务端按原文校验。
     * URLEncoder 把空格编成 `+`，而币安按 `%20` 解码 query —— 两者不一致时
     * 签名串与实际请求就对不上了，所以统一替换回 `%20`。
     */
    fun queryString(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

    fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
