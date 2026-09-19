package com.waxilo.marketmonitor.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Webhook 投递结果。[WebhookOutcome.Failed.reason] 只放异常类名与状态码——
 * 端点 URL 里带 secret，任何会被写进消息中心与日志的文案都不能含它（PRD 6 安全）。
 */
sealed interface WebhookOutcome {
    data object Delivered : WebhookOutcome

    /** 对端明确拒绝（4xx）：重试没有意义，直接记失败。 */
    data class Rejected(val httpCode: Int) : WebhookOutcome

    data class Failed(val reason: String) : WebhookOutcome
}

/**
 * 预警 Webhook 发送（PRD FR-4.1 / FR-4.2）。
 * 传输类错误按 1s、2s、4s 指数退避重试，最多 [MAX_ATTEMPTS] 次；仍失败由调用方落
 * 「待重试」状态，下一轮检测再补发。
 */
class WebhookSender(private val client: OkHttpClient) {

    /** @return null 表示发送成功；否则为可展示的失败原因。 */
    suspend fun post(url: String, payload: String): String? {
        var attempt = 0
        var reason = "未发送"
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            when (val outcome = postOnce(url, payload)) {
                is WebhookOutcome.Delivered -> return null
                is WebhookOutcome.Rejected -> return "对端返回 HTTP ${outcome.httpCode}"
                is WebhookOutcome.Failed -> {
                    reason = outcome.reason
                    if (attempt < MAX_ATTEMPTS) delay(BACKOFF_BASE_MS * (1L shl (attempt - 1)))
                }
            }
        }
        return reason
    }

    private suspend fun postOnce(url: String, payload: String): WebhookOutcome {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(request).awaitStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WebhookOutcome.Failed("发送失败：${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_BASE_MS = 1_000L
    }
}

/** 只关心状态码，响应体不读：接收端常返回大页面，读body 只会拖长连接占用。 */
private suspend fun Call.awaitStatus(): WebhookOutcome {
    val call = this
    return suspendCancellableCoroutine { continuation ->
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(WebhookOutcome.Failed("网络不可达：${e.javaClass.simpleName}"))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                val outcome = when {
                    code in 200..299 -> WebhookOutcome.Delivered
                    code in 400..499 -> WebhookOutcome.Rejected(code)
                    else -> WebhookOutcome.Failed("对端返回 HTTP $code")
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        })
        // 协程被取消时要真的断开请求，否则连接会一直挂到超时
        continuation.invokeOnCancellation { call.cancel() }
    }
}
