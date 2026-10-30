package com.waxilo.marketmonitor.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FuturesRateGateTest {

    private var now = 1_000_000L
    private val gate = FuturesRateGate(nowMs = { now })
    private val binance = "https://fapi.binance.com"
    private val aster = "https://fapi.asterdex.com"

    @Test
    fun `418 按服务端给的 Retry-After 冷却`() {
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 100)
        assertEquals(100_000L, gate.remainingMs(binance))
    }

    @Test
    fun `冷却按源隔离，切到独立盘口不受影响`() {
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 100)
        assertEquals(0L, gate.remainingMs(aster))
        // 冷却外的那一家照常放行
        gate.assertAllowed(aster)
    }

    @Test
    fun `冷却期拦截并带上恢复时刻`() {
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 60)
        try {
            gate.assertAllowed(binance)
            fail("冷却期应当拦截")
        } catch (e: RateLimitedException) {
            assertEquals(now + 60_000L, e.resumeAt)
        }
    }

    @Test
    fun `冷却只延长不缩短`() {
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 100)
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 10)
        assertEquals(100_000L, gate.remainingMs(binance))
    }

    @Test
    fun `没有 Retry-After 的 429 按指数退避`() {
        gate.noteLimited(aster, httpCode = 429, retryAfterSecs = 0)
        assertEquals(FuturesRateGate.FALLBACK_429_MS, gate.remainingMs(aster))
        gate.noteLimited(aster, httpCode = 429, retryAfterSecs = 0)
        assertEquals(FuturesRateGate.FALLBACK_429_MS * 2, gate.remainingMs(aster))
    }

    @Test
    fun `418 没给 Retry-After 时保底两分钟`() {
        gate.noteLimited(binance, httpCode = 418, retryAfterSecs = 0)
        assertEquals(FuturesRateGate.FALLBACK_418_MS, gate.remainingMs(binance))
    }

    @Test
    fun `成功一次后退避重新起算，冷却到期后放行`() {
        gate.noteLimited(aster, httpCode = 429, retryAfterSecs = 0)
        gate.noteLimited(aster, httpCode = 429, retryAfterSecs = 0)
        gate.noteOk(aster)
        now += 60_000L
        assertTrue(gate.remainingMs(aster) == 0L)
        gate.noteLimited(aster, httpCode = 429, retryAfterSecs = 0)
        assertEquals(FuturesRateGate.FALLBACK_429_MS, gate.remainingMs(aster))
    }
}
