package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.model.FuturesDialect
import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 自动换源的规则：失败或慢响应触发、并行测速、第一个成功即用、冷却与手动优先。
 * 时钟注入 testScheduler，探测用挂起/延迟精确控制先后。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuturesAutoSelectorTest {

    private val a = FuturesEndpoint("A", "https://a.test", "", FuturesDialect.BINANCE)
    private val b = FuturesEndpoint("B", "https://b.test", "", FuturesDialect.OKX)

    private fun TestScope.selector(
        candidates: List<FuturesEndpoint> = listOf(a, b),
        probe: suspend (String) -> Long,
        select: suspend (String) -> Unit = {},
        current: () -> String = { a.baseUrl },
    ) = FuturesAutoSelector(
        scope = backgroundScope,
        currentUrl = current,
        probe = probe,
        select = select,
        candidates = candidates,
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun `500ms 内的成功不触发测速`() = runTest {
        var calls = 0
        val auto = selector(probe = { calls += 1; 5L })

        auto.report(499, failed = false)
        auto.report(500, failed = false)
        runCurrent()

        assertEquals(0, calls)
    }

    @Test
    fun `超过 500ms 的成功会并行测速并选中第一个返回的源`() = runTest {
        val probes = mutableListOf<String>()
        val selected = CompletableDeferred<String>()
        val auto = selector(
            probe = { url -> probes += url; 5L },
            select = { selected.complete(it) },
        )

        auto.report(FuturesAutoSelector.SLOW_RESPONSE_MS + 1, failed = false)
        runCurrent()

        assertEquals(listOf(a.baseUrl, b.baseUrl), probes.sorted())
        assertEquals(a.baseUrl, selected.await())
    }

    @Test
    fun `失败立即触发测速，且以最早完成而不是最低延迟为准`() = runTest {
        var selected: String? = null
        val auto = selector(
            probe = { url ->
                if (url == a.baseUrl) {
                    delay(500)
                    10L // 先返回但「自报延迟」更大，不应影响判定
                } else {
                    delay(20)
                    900L
                }
            },
            select = { selected = it },
        )

        auto.report(120, failed = true)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(b.baseUrl, selected)
    }

    @Test
    fun `第一个成功就直接用，不等其他探测结束`() = runTest {
        val never = CompletableDeferred<Unit>()
        var selected: String? = null
        val auto = selector(
            probe = { url -> if (url == a.baseUrl) { never.await(); 1L } else 1L },
            select = { selected = it },
        )

        auto.report(50, failed = true)
        runCurrent()

        assertEquals(b.baseUrl, selected)
        assertFalse(never.isCompleted)
    }

    @Test
    fun `全部失败时不改变选择`() = runTest {
        var selections = 0
        val auto = selector(
            probe = { throw IOException("boom") },
            select = { selections += 1 },
        )

        auto.report(50, failed = true)
        runCurrent()

        assertEquals(0, selections)
    }

    @Test
    fun `失败后十秒内不重复测速`() = runTest {
        var races = 0
        val auto = selector(probe = { races += 1; 1L })

        auto.report(10, failed = true)
        runCurrent()
        val afterFirst = races

        advanceTimeBy(FuturesAutoSelector.FAILURE_COOLDOWN_MS - 1)
        auto.report(10, failed = true)
        runCurrent()
        assertEquals(afterFirst, races)

        advanceTimeBy(1)
        auto.report(10, failed = true)
        runCurrent()
        assertEquals(afterFirst * 2, races)
    }

    @Test
    fun `慢响应后三十秒内不重复测速`() = runTest {
        var races = 0
        val auto = selector(probe = { races += 1; 1L })

        auto.report(5_000, failed = false)
        runCurrent()
        val afterFirst = races

        advanceTimeBy(FuturesAutoSelector.SLOW_COOLDOWN_MS - 1)
        auto.report(5_000, failed = false)
        runCurrent()
        assertEquals(afterFirst, races)

        advanceTimeBy(1)
        auto.report(5_000, failed = false)
        runCurrent()
        assertEquals(afterFirst * 2, races)
    }

    @Test
    fun `一轮探测未结束时不再起第二轮`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val auto = selector(probe = { calls += 1; gate.await(); 1L })

        auto.report(10, failed = true)
        runCurrent()
        val duringFirst = calls

        auto.report(10, failed = true)
        auto.report(5_000, failed = false)
        runCurrent()
        assertEquals(duringFirst, calls)

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `探测期间用户手动换源则丢弃这轮结果`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var current = a.baseUrl
        var selected: String? = null
        val auto = selector(
            candidates = listOf(a),
            probe = { gate.await(); 1L },
            select = { selected = it },
            current = { current },
        )

        auto.report(10, failed = true)
        runCurrent()
        current = b.baseUrl
        gate.complete(Unit)
        runCurrent()

        assertEquals(null, selected)
    }

    @Test
    fun `探测自身挂起会被取消而不拖住测试`() = runTest {
        var winner: String? = null
        val auto = selector(
            probe = { url ->
                if (url == a.baseUrl) awaitCancellation() else 1L
            },
            select = { winner = it },
        )

        auto.report(10, failed = true)
        runCurrent()

        assertEquals(b.baseUrl, winner)
    }

    @Test
    fun `源级错误才算不可用，业务错误不换源`() {
        assertFalse(isSourceUnavailable(MarketApiException(400, -1121, "Invalid symbol")))
        assertFalse(isSourceUnavailable(MarketApiException(400, 0, "HTTP 400")))
        assertFalse(isSourceUnavailable(IllegalStateException("boom")))

        assertTrue(isSourceUnavailable(MarketApiException(503, 0, "HTTP 503")))
        assertTrue(isSourceUnavailable(MarketApiException(429, -1003, "Too many requests")))
        assertTrue(isSourceUnavailable(MarketApiException(418, 0, "HTTP 418")))
        assertTrue(isSourceUnavailable(MarketApiException(404, 0, "HTTP 404")))
        assertTrue(isSourceUnavailable(SocketTimeoutException("timeout")))
        assertTrue(isSourceUnavailable(UnknownHostException("dns")))
    }
}
