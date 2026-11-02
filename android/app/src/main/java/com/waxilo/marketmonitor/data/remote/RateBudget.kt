package com.waxilo.marketmonitor.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 接口权重节流（PRD 3.2「限频保护」）。
 * 滑动窗口记账，超预算时让出协程而不是丢请求——请求被静默丢弃会让列表停在旧数据上，更难排查。
 *
 * [windowMs] 默认 60 秒（币安按分钟记账）；Gate 的公开接口按 **10 秒 200 次** 记账，
 * 用 60 秒窗口会把前 10 秒的爆发放过去，然后被服务端 429 打回来 —— 两个窗口口径不能混用。
 */
class RateBudget(
    private val limitPerWindow: Int,
    private val windowMs: Long = WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val spends = ArrayDeque<Pair<Long, Int>>()

    /** 需要等待的毫秒数，0 表示可以立即发出。只读，便于单测。 */
    fun waitMs(cost: Int, now: Long): Long {
        val active = spends.filter { now - it.first < windowMs }
        val total = active.sumOf { it.second } + cost.coerceAtLeast(0)
        if (total <= limitPerWindow) return 0L
        val oldest = active.minOfOrNull { it.first } ?: return 0L
        return (windowMs - (now - oldest)).coerceAtLeast(1L)
    }

    /** 记账并等待到窗口有余量；持锁串行，避免并发请求看到同一份余量后一起超支。 */
    suspend fun await(cost: Int): Unit = mutex.withLock {
        while (true) {
            val wait = waitMs(cost, nowMs())
            if (wait <= 0L) break
            delay(wait)
        }
        spends.addLast(nowMs() to cost.coerceAtLeast(0))
        while (spends.isNotEmpty() && nowMs() - spends.first().first >= windowMs) spends.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 60_000L
    }
}
