package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 合约数据源的自动兜底（与桌面端 `lib/autoSource.ts` 同一套规则）。
 *
 * 触发条件（任一）：一次合约请求失败、或成功但往返超过 500ms。
 * 触发后**并行探测全部候选（含当前源）**，第一个成功返回的即视为最快源并立即切换 ——
 * 不等其余候选。当前源若先返回，则保持不动（它本来就是这一轮最快的）。
 *
 * 冷却按触发原因分开：失败 10s、响应慢 30s。前者保证新源也挂掉时能较快再换一次，
 * 后者避免「全网都慢」时不停换源。同一时刻只跑一轮探测。
 *
 * 探测请求自身**不得**调用 [report]，否则一次换源会把自己再触发一遍。
 */
class FuturesAutoSelector(
    private val scope: CoroutineScope,
    private val currentUrl: () -> String,
    private val probe: suspend (String) -> Long,
    private val select: suspend (String) -> Unit,
    private val candidates: List<FuturesEndpoint> = FuturesEndpoints.ALL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val racing = AtomicBoolean(false)
    private val lastRaceAt = AtomicLong(NO_RACE)

    /** 数据层在每次合约请求结束后调用；`failed` 只应包含「源不可用」，业务错误不要报。 */
    fun report(durationMs: Long, failed: Boolean) {
        if (!failed && durationMs <= SLOW_RESPONSE_MS) return
        val now = nowMs()
        val cooldown = if (failed) FAILURE_COOLDOWN_MS else SLOW_COOLDOWN_MS
        val previous = lastRaceAt.get()
        if (previous != NO_RACE && now - previous < cooldown) return
        if (!racing.compareAndSet(false, true)) return
        lastRaceAt.set(now)
        scope.launch {
            try {
                raceFastest()
            } finally {
                racing.set(false)
            }
        }
    }

    private suspend fun raceFastest() {
        val origin = currentUrl()
        val winner = firstReachable() ?: return
        // 探测期间用户可能手动换过源：以手动选择为准，丢弃这轮结果
        if (currentUrl() == origin) select(winner)
    }

    /** 全部候选同时开测；第一个成功即返回，其余探测立即取消。 */
    private suspend fun firstReachable(): String? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<String?>()
        val remaining = AtomicInteger(candidates.size)
        val jobs = candidates.map { endpoint ->
            launch {
                val reachable = try {
                    probe(endpoint.baseUrl)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (reachable) {
                    winner.complete(endpoint.baseUrl)
                } else if (remaining.decrementAndGet() == 0) {
                    winner.complete(null)
                }
            }
        }
        val result = winner.await()
        jobs.forEach { it.cancel() }
        result
    }

    companion object {
        /** 成功响应超过这个时长就值得重新选一次源。 */
        const val SLOW_RESPONSE_MS = 500L
        const val FAILURE_COOLDOWN_MS = 10_000L
        const val SLOW_COOLDOWN_MS = 30_000L

        private const val NO_RACE = Long.MIN_VALUE
    }
}
