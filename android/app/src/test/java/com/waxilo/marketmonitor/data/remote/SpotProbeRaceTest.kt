package com.waxilo.marketmonitor.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * 现货测速的竞速口径（[firstSuccess]）。
 *
 * 钉死的是一个**用户能看见的**性质：这一行什么时候回填，取决于**最快那条**，而不是最慢那条。
 *
 * 背景（2026-11-21 用户反馈）：每次测速都是 Gate 先测完、币安最后测完，但币安显示的延迟并不高。
 * 原因不在网络 —— 币安那条链是 6 个主机，其中 5 个在本机打不通（各跑满 5 秒超时），旧口径
 * `awaitAll()` 等齐再取 min，于是「显示的延迟 = 最快那条的 1.6 秒」而「回填时刻 = 最慢那条的
 * 5.1 秒」。序号即优先级，全挂时的结论只认 0 号（首选域）。
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime：虚拟时钟读数，稳定的判断依据
class SpotProbeRaceTest {

    /** 一条「打不通」的探测：跑满超时才失败，与真机上的黑洞域名同形。 */
    private fun blackHole(timeoutMs: Long = 5_000): suspend () -> Long = {
        delay(timeoutMs)
        throw SocketTimeoutException("探测超时")
    }

    /** 一条「n 毫秒答上」的探测：`measured` 是它自己测到的往返（可能与墙钟略有出入）。 */
    private fun answerAfter(ms: Long, measured: Long = ms): suspend () -> Long = {
        delay(ms)
        measured
    }

    @Test
    fun `币安那条链 一快五超时 整行耗时等于最快那条而不是最慢那条`() = runTest {
        val attempts = listOf<suspend () -> Long>(
            answerAfter(1_621), // 首选域 data-api：实测 1.6 秒才答上
            answerAfter(1_200, 1_180), // 某个镜像反而更快
            blackHole(), blackHole(), blackHole(), blackHole(), blackHole(), // 5 个黑洞，各 5 秒
        )
        val latency = firstSuccess(attempts)
        assertEquals(1_180L, latency)
        // 关键断言：1.2 秒就回填，而不是被 5 秒的黑洞拖到 5.1 秒
        assertEquals(1_200L, currentTime)
    }

    @Test
    fun `先答上的那条的耗时就是返回值 不被竞速改写`() = runTest {
        // 显示的数字必须是「这一条自己测到的往返」，不是「从这里到现在过了多久」
        val latency = firstSuccess(listOf(answerAfter(300, 296), answerAfter(400, 390)))
        assertEquals(296L, latency)
        assertEquals(300L, currentTime)
    }

    @Test
    fun `最快那条失败时继续等下一个成功的`() = runTest {
        val attempts = listOf<suspend () -> Long>(
            {
                delay(50)
                throw IOException("HTTP 403")
            },
            answerAfter(120, 118),
            answerAfter(400, 380),
        )
        assertEquals(118L, firstSuccess(attempts))
        assertEquals(120L, currentTime)
    }

    @Test
    fun `全挂时抛首选域的失败 即使它最后才失败`() = runTest {
        // 单域源（Gate）或整条链全挂：文案要说「首选为什么不通」，不能是随机一个镜像
        val attempts = listOf<suspend () -> Long>(
            blackHole(),
            {
                delay(60)
                throw IOException("域名解析失败")
            },
        )
        val error = assertFailsWith<SocketTimeoutException> { firstSuccess(attempts) }
        assertEquals("探测超时", error.message)
        // 全挂没有捷径：必须等最后一条也有结论（这里是 5 秒）
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `全挂时保留原始异常的类型与文案 不包一层`() = runTest {
        // 调用方靠异常类型/文案折成那一行结论，包一层会把它变成没信息量的通用文案
        val attempts = listOf<suspend () -> Long>({ throw IllegalStateException("网关 502") })
        val error = assertFailsWith<IllegalStateException> { firstSuccess(attempts) }
        assertEquals("网关 502", error.message)
    }

    @Test
    fun `首个成功后其余探测被取消 不会继续占用连接`() = runTest {
        val cancelled = AtomicInteger(0)
        val attempts = listOf<suspend () -> Long>(
            answerAfter(100, 95),
            {
                try {
                    delay(10_000)
                    9_000L
                } catch (e: CancellationException) {
                    cancelled.incrementAndGet()
                    throw e
                }
            },
        )
        assertEquals(95L, firstSuccess(attempts))
        // supervisorScope 退出前会等子协程收尾，所以这里不必再推进时间
        assertEquals(1, cancelled.get())
        assertEquals(100L, currentTime)
    }

    @Test
    fun `空清单是编程错误 立刻抛`() = runTest {
        assertFailsWith<IllegalArgumentException> { firstSuccess<Long>(emptyList()) }
    }
}
