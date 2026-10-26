package com.waxilo.marketmonitor.domain.kline

import com.waxilo.marketmonitor.domain.model.Kline
import kotlinx.coroutines.CancellationException

/**
 * 单次请求取不满时「往前多翻几页」拼成一段（与桌面端 `lib/klinePaging.ts` 同一套口径）。
 *
 * 为什么需要：各家单次 K 线根数上限不同（OKX 300、Hyperliquid 500、币安 / Bybit 1000），
 * 而长档 EMA 要「最长档位 × 4」根预热（见 `EmaAnalysis.WARMUP_FACTOR`）——
 * 上限低的源只取一页，长档 EMA 会被 SMA 种子拖偏。
 *
 * 口径：
 * - 第一页取「`endTime` 之前（缺省 = 最新）的 `pageSize` 根」，之后每页的 `endTime` = 已拿到的最老一根 − 1ms；
 * - 按开盘时间去重（各家 `end` 有闭区间的，边界那根会重复回来），出口升序、至多 `wanted` 根；
 * - **停止条件**：凑够了 / 一页里没有新数据（到了上市第一根，或服务端无视时间参数）/ 页数到顶；
 *   不按「短页」判到头 —— MEXC、Hyperliquid 这种按时间窗倒推的方言，一页差一两根是常态；
 * - 第一页失败照常抛错；**后面的页失败就收手**，用已经拿到的（Bitget 这类历史深度有限的源，
 *   越界会直接报参数错，这时手上的数据仍是可用的最长一段）。协程取消一律原样上抛。
 */
object KlinePaging {

    /** 一次取数最多翻几页：OKX 300 根/页时 1000 根要 4 页；自定义周期按倍率放大后会更多，封顶防拖死。 */
    const val MAX_PAGES = 10

    suspend fun pageBackwards(
        wanted: Int,
        pageSize: Int,
        endTime: Long?,
        maxPages: Int = MAX_PAGES,
        fetchPage: suspend (limit: Int, endTime: Long?) -> List<Kline>,
    ): List<Kline> {
        val byTime = LinkedHashMap<Long, Kline>()
        var end = endTime
        var page = 0
        while (page < maxPages && byTime.size < wanted) {
            val rows = try {
                fetchPage(pageSize, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 0) throw e
                break
            }
            val before = byTime.size
            for (k in rows) byTime.putIfAbsent(k.openTime, k)
            val oldest = rows.minOfOrNull { it.openTime }
            if (byTime.size == before || oldest == null) break
            end = oldest - 1
            page++
        }
        return byTime.values.sortedBy { it.openTime }.takeLast(wanted)
    }
}
