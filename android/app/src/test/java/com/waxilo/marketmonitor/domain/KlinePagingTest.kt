package com.waxilo.marketmonitor.domain

import com.waxilo.marketmonitor.domain.kline.KlinePaging
import com.waxilo.marketmonitor.domain.model.Kline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多页拼接：断言落在拿回来的根数、首尾时间与请求次数上。 */
class KlinePagingTest {

    /** 模拟一家只有 [total] 根历史（openTime = 0..total-1）、单页上限 [cap] 的源；`end` 是闭区间。 */
    private class FakeSource(val total: Int, val cap: Int, val failFromPage: Int = Int.MAX_VALUE) {
        var calls = 0
        fun page(limit: Int, end: Long?): List<Kline> {
            if (calls++ >= failFromPage) error("越界")
            val last = minOf(total - 1L, end ?: Long.MAX_VALUE)
            val first = maxOf(0L, last - minOf(limit, cap) + 1)
            return (first..last).map { kline(it, "1", "1", "1", "1") }
        }
    }

    @Test
    fun `上限 300 的源凑满 1000 根，升序且不重不漏`() = runTest {
        val src = FakeSource(total = 5000, cap = 300)
        val out = KlinePaging.pageBackwards(1000, 300, null) { l, e -> src.page(l, e) }
        assertEquals(1000, out.size)
        assertEquals(4000L, out.first().openTime)
        assertEquals(4999L, out.last().openTime)
        assertEquals(1000, out.map { it.openTime }.distinct().size)
        assertEquals(4, src.calls)
    }

    @Test
    fun `历史不够时取到上市第一根就停`() = runTest {
        val src = FakeSource(total = 481, cap = 300)
        val out = KlinePaging.pageBackwards(1000, 300, null) { l, e -> src.page(l, e) }
        assertEquals(481, out.size)
        assertEquals(0L, out.first().openTime)
        // 第 3 页是空页 ⇒ 判到头
        assertEquals(3, src.calls)
    }

    @Test
    fun `服务端无视时间参数时第二页无新数据即停`() = runTest {
        var calls = 0
        val out = KlinePaging.pageBackwards(1000, 300, null) { _, _ ->
            calls++
            (700L..999L).map { kline(it, "1", "1", "1", "1") }
        }
        assertEquals(300, out.size)
        assertEquals(2, calls)
    }

    @Test
    fun `后续页失败时收手并返回已拿到的`() = runTest {
        val src = FakeSource(total = 5000, cap = 300, failFromPage = 2)
        val out = KlinePaging.pageBackwards(1000, 300, null) { l, e -> src.page(l, e) }
        assertEquals(600, out.size)
        assertEquals(4999L, out.last().openTime)
    }

    @Test
    fun `第一页失败照常抛错`() = runTest {
        val src = FakeSource(total = 5000, cap = 300, failFromPage = 0)
        val thrown = runCatching { KlinePaging.pageBackwards(1000, 300, null) { l, e -> src.page(l, e) } }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `页数封顶`() = runTest {
        val src = FakeSource(total = 100_000, cap = 300)
        val out = KlinePaging.pageBackwards(10_000, 300, null, maxPages = 3) { l, e -> src.page(l, e) }
        assertEquals(900, out.size)
        assertEquals(3, src.calls)
    }

    @Test
    fun `带 endTime 时从该时刻往前翻`() = runTest {
        val src = FakeSource(total = 5000, cap = 300)
        val out = KlinePaging.pageBackwards(500, 300, 2999L) { l, e -> src.page(l, e) }
        assertEquals(500, out.size)
        assertEquals(2999L, out.last().openTime)
        assertEquals(2500L, out.first().openTime)
    }
}
