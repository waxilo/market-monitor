package com.waxilo.marketmonitor.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * 合约 REST 的限频冷却（与桌面端 `lib/rateGate.ts` 同口径）。
 *
 * 币安对 418（IP 封禁）的处理是**封禁期内继续发请求就把封禁一路往后推**
 * （2 分钟 → 5 分钟 → 30 分钟 → 2 小时 → 3 天），所以「被封了还按每秒一拍地重试」
 * 正是 418 越封越久的直接原因。这里在发出请求前拦一道：
 * - 429/418 按服务端给的 `Retry-After` 冷却；没给就指数退避（418 从 2 分钟起）；
 * - 冷却按源隔离：封的是当前那一个域名，切到独立盘口后不应继续被卡；
 * - 冷却只延长不缩短：两处取数一前一后各撞一次，以更晚的截止时间为准。
 *
 * [RateBudget] 管的是「别自己把额度打满」，这里管的是「已经被封了就别再打」。
 */
class FuturesRateGate(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val untilByOrigin = ConcurrentHashMap<String, Long>()
    private val strikesByOrigin = ConcurrentHashMap<String, Int>()

    /** 该源还剩多久冷却（0 = 没被限频过 / 已过期）。 */
    fun remainingMs(origin: String): Long =
        maxOf(0L, (untilByOrigin[origin] ?: 0L) - nowMs())

    /** 冷却期未过就抛 [RateLimitedException]，请求不会真正发出。 */
    fun assertAllowed(origin: String) {
        val resumeAt = untilByOrigin[origin] ?: return
        if (resumeAt > nowMs()) throw RateLimitedException(resumeAt)
    }

    /** 记一次限频响应。`retryAfterSecs` 是服务端口径（币安会给），没有才退避。 */
    fun noteLimited(origin: String, httpCode: Int, retryAfterSecs: Int) {
        val now = nowMs()
        val strikes = (strikesByOrigin[origin] ?: 0) + 1
        strikesByOrigin[origin] = strikes
        val fallback = if (httpCode == 418) FALLBACK_418_MS else FALLBACK_429_MS
        // 移位上限防溢出：2^10 已远超 MAX_FALLBACK_MS，再大由 minOf 收口
        val backoff = minOf(fallback shl (strikes - 1).coerceIn(0, 10), MAX_FALLBACK_MS)
        val wait = if (retryAfterSecs > 0) retryAfterSecs * 1_000L else backoff
        untilByOrigin[origin] = maxOf(untilByOrigin[origin] ?: 0L, now + wait)
    }

    /** 一次成功响应：退避计数清零（冷却本身只由时间决定）。 */
    fun noteOk(origin: String) {
        if ((strikesByOrigin[origin] ?: 0) != 0) strikesByOrigin[origin] = 0
    }

    companion object {
        /** 429 没给 Retry-After 时的保底冷却。 */
        const val FALLBACK_429_MS = 5_000L
        /** 418 没给 Retry-After 时的保底冷却 —— 币安最短的 IP 封禁就是 2 分钟。 */
        const val FALLBACK_418_MS = 120_000L
        /** 退避上限。 */
        const val MAX_FALLBACK_MS = 30 * 60_000L
    }
}
