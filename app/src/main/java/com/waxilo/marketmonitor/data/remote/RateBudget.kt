package com.waxilo.marketmonitor.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 币安权重节流（PRD 3.2「限频保护」）。
 * 以 1 分钟滑动窗口记账，超预算时让出协程而不是丢请求——请求被静默丢弃会让列表停在旧数据上，更难排查。
 */
class RateBudget(private val limitPerMinute: Int, private val nowMs: () -> Long = System::currentTimeMillis) {

    private val mutex = Mutex()
    private val spends = ArrayDeque<Pair<Long, Int>>()

    /** 需要等待的毫秒数，0 表示可以立即发出。只读，便于单测。 */
    fun waitMs(cost: Int, now: Long): Long {
        val active = spends.filter { now - it.first < WINDOW_MS }
        val total = active.sumOf { it.second } + cost.coerceAtLeast(0)
        if (total <= limitPerMinute) return 0L
        val oldest = active.minOfOrNull { it.first } ?: return 0L
        return (WINDOW_MS - (now - oldest)).coerceAtLeast(1L)
    }

    /** 记账并等待到窗口有余量；持锁串行，避免并发请求看到同一份余量后一起超支。 */
    suspend fun await(cost: Int): Unit = mutex.withLock {
        val startedAt = nowMs()
        while (true) {
            val wait = waitMs(cost, nowMs())
            if (wait <= 0L || nowMs() - startedAt > MAX_WAIT_MS) break
            delay(wait)
        }
        spends.addLast(nowMs() to cost.coerceAtLeast(0))
        while (spends.isNotEmpty() && nowMs() - spends.first().first >= WINDOW_MS) spends.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_WAIT_MS = 30_000L
    }
}
