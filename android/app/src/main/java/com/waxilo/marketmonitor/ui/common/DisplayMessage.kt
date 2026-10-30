package com.waxilo.marketmonitor.ui.common

import com.waxilo.marketmonitor.data.remote.MarketApiException
import com.waxilo.marketmonitor.data.remote.RateLimitedException
import java.io.IOException
import kotlin.math.ceil

/**
 * 把异常翻译成可直接展示的中文。网络类错误统一说「网络」而不是堆栈里的类名——
 * 用户能做的只有检查网络或稍后重试。
 */
fun Throwable.displayMessage(): String = when (this) {
    // 冷却拦截：网络其实是通的，是接口在封你 —— 要把「还要等多久」说出来，
    // 否则用户会当成断网去折腾代理
    is RateLimitedException -> {
        val minutes = ceil((resumeAt - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(1)
        "已被币安限频，约 $minutes 分钟后恢复"
    }
    is MarketApiException -> {
        val detail = message?.takeIf { it.isNotBlank() } ?: "行情接口返回错误（$httpCode）"
        when {
            httpCode == 418 || httpCode == 429 || binanceCode == -1003 -> "已被币安限频，请稍后再试"
            else -> detail
        }
    }
    is IOException -> "网络不可用，展示的是本地缓存"
    else -> message ?: "发生未知错误"
}
