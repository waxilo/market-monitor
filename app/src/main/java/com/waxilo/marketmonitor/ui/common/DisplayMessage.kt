package com.waxilo.marketmonitor.ui.common

import com.waxilo.marketmonitor.data.remote.MarketApiException
import java.io.IOException

/**
 * 把异常翻译成可直接展示的中文。网络类错误统一说「网络」而不是堆栈里的类名——
 * 用户能做的只有检查网络或稍后重试。
 */
fun Throwable.displayMessage(): String = when (this) {
    is MarketApiException -> {
        val detail = message?.takeIf { it.isNotBlank() } ?: "行情接口返回错误（$httpCode）"
        when {
            httpCode == 429 || binanceCode == -1003 -> "已被币安限频，请稍后再试"
            else -> detail
        }
    }
    is IOException -> "网络不可用，展示的是本地缓存"
    else -> message ?: "发生未知错误"
}
