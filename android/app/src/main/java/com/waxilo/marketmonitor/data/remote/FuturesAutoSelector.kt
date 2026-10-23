package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 合约数据源的自动兜底（与桌面端 `lib/autoSource.ts` 同一套规则）。
 *
 * **默认不接管**：整套逻辑由 [enabled] 这个用户开关把关，设置页「自动选择接口」出厂关闭
 * （2026-10-23）。关着的时候 [report] 直接返回 —— 一次探测都不发，[currentUrl] 就是唯一真相。
 * 换源要清合约缓存、重拉 K 线与交易对，而各盘口 OHLC 本就有差异，这种替用户做主的动作
 * 必须先得到用户同意（与桌面端 `mm.autoSource` 同口径）。
 *
 * 开启后，触发条件（任一）：一次合约请求失败、或成功但往返超过 500ms。
 *
 * 两条路径的取舍不同：
 * - **失败**：并行探测全部候选（含当前源），第一个成功返回的立即采用 —— 不等其余候选。
 * - **慢响应**：探测照旧全并行，但必须**等当前源的结果**再判。当前源可达时，只有候选
 *   明显更快（见 [shouldSwitchOnSlow]）才切；否则保持不动。探测是小请求、整包 K 线是大
 *   请求：后者超过 500ms 很常见，若照「第一个返回即最快」去切，会在几个盘口之间来回跳，
 *   图表每次换源都要清空重拉，看起来就是蜡烛与影线在漂。
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
    /**
     * 用户开关：false 时 [report] 不产生任何动作。
     * 做成**读时求值**的回调（而不是构造参数里的布尔）—— 用户随时可能在设置页打开它，
     * 而这个对象是单例、构造早于任何设置变更。
     */
    private val enabled: () -> Boolean = { true },
    private val candidates: List<FuturesEndpoint> = FuturesEndpoints.ALL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val racing = AtomicBoolean(false)
    private val lastRaceAt = AtomicLong(NO_RACE)

    /** 数据层在每次合约请求结束后调用；`failed` 只应包含「源不可用」，业务错误不要报。 */
    fun report(durationMs: Long, failed: Boolean) {
        if (!enabled()) return
        if (!failed && durationMs <= SLOW_RESPONSE_MS) return
        val now = nowMs()
        val cooldown = if (failed) FAILURE_COOLDOWN_MS else SLOW_COOLDOWN_MS
        val previous = lastRaceAt.get()
        if (previous != NO_RACE && now - previous < cooldown) return
        if (!racing.compareAndSet(false, true)) return
        lastRaceAt.set(now)
        scope.launch {
            try {
                raceFastest(failed)
            } finally {
                racing.set(false)
            }
        }
    }

    private suspend fun raceFastest(failed: Boolean) {
        val origin = currentUrl()
        val winner = if (failed) firstReachable() else bestReplacement(origin)
        if (winner == null) return
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

    /**
     * 慢响应的换源判定：等全部探测落地后，拿当前源与最快的候选比。
     * 当前源探测失败（或不在候选清单里）→ 采用最快的可用候选；可达 → 只有明显更快才切。
     */
    private suspend fun bestReplacement(origin: String): String? = coroutineScope {
        val results = candidates.map { endpoint ->
            async {
                val latency = try {
                    probe(endpoint.baseUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                endpoint.baseUrl to latency
            }
        }.awaitAll()

        val current = results.firstOrNull { it.first == origin }?.second
        val fastest = results
            .filter { it.first != origin && it.second != null }
            .minByOrNull { it.second ?: Long.MAX_VALUE }
            ?: return@coroutineScope null
        val fastestMs = fastest.second ?: return@coroutineScope null
        when {
            current == null -> fastest.first
            shouldSwitchOnSlow(current, fastestMs) -> fastest.first
            else -> null
        }
    }

    companion object {
        /** 成功响应超过这个时长就值得重新选一次源。 */
        const val SLOW_RESPONSE_MS = 500L
        const val FAILURE_COOLDOWN_MS = 10_000L
        const val SLOW_COOLDOWN_MS = 30_000L

        private const val NO_RACE = Long.MIN_VALUE

        /**
         * 慢路径的换源门槛：候选必须比当前源**明显**更快才值得切。
         * 1.5 倍与多 300ms 取更严的一条：低延迟看绝对差，高延迟看相对差。
         */
        fun shouldSwitchOnSlow(currentMs: Long, alternativeMs: Long): Boolean =
            currentMs > maxOf(alternativeMs * 3 / 2, alternativeMs + 300)
    }
}
