package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.dto.SpotAccountDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RateBudgetTest {

    private var now = 0L
    private val budget = RateBudget(limitPerMinute = 100, nowMs = { now })

    @Test
    fun `预算未满时可立即发出`() = runTest {
        budget.await(20)
        assertEquals(0L, budget.waitMs(cost = 80, now = now))
    }

    @Test
    fun `超预算后按最老一笔的滑出时间等待`() = runTest {
        budget.await(100)
        assertEquals(RateBudget.WINDOW_MS, budget.waitMs(cost = 1, now = 0L))
        assertEquals(1L, budget.waitMs(cost = 1, now = RateBudget.WINDOW_MS - 1))
        assertEquals(0L, budget.waitMs(cost = 1, now = RateBudget.WINDOW_MS))
    }

    @Test
    fun `非法成本按零计，超过整个预算的单笔不等待`() {
        // 等也等不出余量，直接发出，由服务端 429 兜底
        assertEquals(0L, budget.waitMs(cost = -10, now = 0L))
        assertEquals(0L, budget.waitMs(cost = 100, now = 0L))
        assertEquals(0L, budget.waitMs(cost = 101, now = 0L))
    }

    @Test
    fun `await 会真的挂起到窗口滑出`() = runTest {
        val clock = RateBudget(limitPerMinute = 100, nowMs = { testScheduler.currentTime })
        clock.await(100)
        clock.await(1)
        assertEquals(RateBudget.WINDOW_MS, testScheduler.currentTime)
    }

    /** 排队多笔时等到最老一笔滑出窗口即放行，放行后窗口内重新有余量。 */
    @Test
    fun `排队多笔时等到最老一笔滑出窗口`() = runTest {
        val clock = RateBudget(limitPerMinute = 100, nowMs = { testScheduler.currentTime })
        clock.await(30)
        delay(1_000)
        clock.await(30)
        delay(1_000)
        clock.await(30)
        clock.await(20)
        assertEquals(RateBudget.WINDOW_MS, testScheduler.currentTime)
        assertEquals(0L, clock.waitMs(cost = 20, now = testScheduler.currentTime))
    }
}

/**
 * 多域名容灾的判定规则。
 *
 * 关键场景是「镜像回的内容不像币安」：裸 404/403 必须继续往下一个域名试，
 * 否则设置里一个填错的镜像就会把内置可直连域名一起拖死（表现即「不连 VPN 没有行情」）。
 */
class RestHostFallbackTest {

    @Test
    fun `镜像的裸 404 要继续回退而不是当参数错误`() {
        val notFound = MarketApiException(404, 0, "HTTP 404 Not Found")
        assertTrue(shouldTryNextHost(notFound))
        assertTrue(shouldTryNextHost(MarketApiException(403, 0, "HTTP 403 Forbidden")))
    }

    @Test
    fun `限频无论哪个域名都可能不同所以也回退`() {
        assertTrue(shouldTryNextHost(MarketApiException(429, 0, "HTTP 429")))
        assertTrue(shouldTryNextHost(MarketApiException(429, -1003, "Too many requests")))
        assertTrue(shouldTryNextHost(MarketApiException(418, 0, "HTTP 418")))
    }

    @Test
    fun `币安自己拒掉的请求换域名也没用`() {
        // -1121 = 标的不存在：每个域名都会给同样的答案，继续试只是白烧权重
        assertTrue(!shouldTryNextHost(MarketApiException(400, -1121, "-1121: Invalid symbol")))
        assertTrue(!shouldTryNextHost(MarketApiException(400, -1102, "-1102: MANDATORY_PARAMETER_MISSING")))
    }

    @Test
    fun `现货首位是可直连的行情专用域`() {
        val hosts = DefaultRestHosts().hostsFor(MarketType.SPOT)
        assertEquals("https://data-api.binance.vision", hosts.first())
        assertTrue("https://api.binance.com" in hosts)
    }

    @Test
    fun `合约只用用户选定的单一接口不再逐域回退`() {
        // 默认：Aster 官方域，且链上只有这一个
        assertEquals(
            listOf("https://fapi.asterdex.com"),
            DefaultRestHosts().hostsFor(MarketType.FUTURES),
        )
        // 用户选了币安合约域：只发往它，不追加别的域做回退
        assertEquals(
            listOf("https://fapi.binance.com"),
            DefaultRestHosts(futuresHost = { "https://fapi.binance.com" }).hostsFor(MarketType.FUTURES),
        )
    }

    @Test
    fun `旧的脏值与空值都回落到默认接口`() {
        // 老版本手填的镜像域不在候选清单里：规整回默认，而不是把未知域当合法接口用
        assertEquals(
            listOf("https://fapi.asterdex.com"),
            DefaultRestHosts(futuresHost = { "https://mirror.example.com/" }).hostsFor(MarketType.FUTURES),
        )
        assertEquals(
            listOf("https://fapi.asterdex.com"),
            DefaultRestHosts(futuresHost = { "" }).hostsFor(MarketType.FUTURES),
        )
    }
}

/**
 * 现货账户 DTO 解析（/api/v3/account）。
 * 币安会返回账户里全部资产的零余额行，不滤掉的话 UI 会被几百行空资产淹没。
 */
class SpotAccountDtoTest {

    @Test
    fun `零余额与坏数字的资产行被过滤，金额走 BigDecimal 不丢精度`() {
        val element = MarketJson.DEFAULT.parseToJsonElement(
            """
            {"balances":[
              {"asset":"BTC","free":"0.5","locked":"0.1"},
              {"asset":"USDT","free":"0","locked":"0"},
              {"asset":"","free":"9"},
              {"asset":"ETH","free":"x"}
            ]}
            """.trimIndent(),
        )
        val dto = MarketJson.DEFAULT.decodeFromJsonElement(SpotAccountDto.serializer(), element)
        val rows = dto.balances.mapNotNull { it.toDomain() }
        assertEquals(1, rows.size)
        assertEquals("BTC", rows[0].asset)
        assertEquals(0, BigDecimal("0.6").compareTo(rows[0].quantity))
        assertEquals(0, BigDecimal("0.5").compareTo(rows[0].available))
    }
}
