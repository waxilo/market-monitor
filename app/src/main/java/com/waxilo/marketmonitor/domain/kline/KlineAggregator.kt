package com.waxilo.marketmonitor.domain.kline

import com.waxilo.marketmonitor.domain.model.Kline
import java.math.BigDecimal

/**
 * 把基础周期的蜡烛合并为目标周期蜡烛（PRD FR-2.3）。
 * 设计取舍：每次实时 tick 对已加载窗口整体重聚合，而非增量维护最后一根——
 * 千根量级的聚合开销可忽略，却避免了「跨桶边界」与「乱序 tick」两类难查的错误。
 */
object KlineAggregator {

    private fun bucketMs(minutes: Long): Long = minutes * 60_000L

    fun bucketOf(openTime: Long, minutes: Long): Long = Math.floorDiv(openTime, bucketMs(minutes))

    /**
     * 聚合到 [target] 周期。数据已是目标周期时结果等价于原序列（每根独占一桶）。
     * 输入需按 openTime 升序；非升序时先排序，以免同一桶被拆成多根。
     */
    fun aggregate(source: List<Kline>, target: CandleInterval): List<Kline> =
        aggregate(source, target.minutes, target.aggregateRatio)

    fun aggregate(source: List<Kline>, targetMinutes: Long, ratio: Int): List<Kline> {
        if (source.isEmpty()) return emptyList()
        val sorted = if (isAscending(source)) source else source.sortedBy { it.openTime }
        val groupSize = maxOf(1, ratio)

        val result = ArrayList<Kline>(sorted.size)
        var bucket = bucketOf(sorted.first().openTime, targetMinutes)
        var group = ArrayList<Kline>(groupSize)

        for (kline in sorted) {
            val current = bucketOf(kline.openTime, targetMinutes)
            if (current != bucket) {
                result += merge(group, groupSize)
                group = ArrayList<Kline>(groupSize)
                bucket = current
            }
            group.add(kline)
        }
        if (group.isNotEmpty()) result += merge(group, groupSize)
        return result
    }

    /** 同一根蜡烛被多次推送（WS 未收盘更新）时保留最新一次。 */
    fun dedupeByOpenTime(klines: List<Kline>): List<Kline> {
        if (klines.size < 2) return klines
        val out = ArrayList<Kline>(klines.size)
        for (kline in klines) {
            val last = out.lastOrNull()
            if (last != null && last.openTime == kline.openTime) {
                out[out.size - 1] = kline
            } else {
                out.add(kline)
            }
        }
        return out
    }

    private fun isAscending(klines: List<Kline>): Boolean {
        for (i in 1 until klines.size) {
            if (klines[i].openTime < klines[i - 1].openTime) return false
        }
        return true
    }

    private fun merge(group: List<Kline>, groupSize: Int): Kline {
        val first = group.first()
        val last = group.last()
        var high = first.high
        var low = first.low
        var volume = BigDecimal.ZERO
        var quoteVolume = BigDecimal.ZERO
        var trades = 0L
        for (k in group) {
            if (k.high > high) high = k.high
            if (k.low < low) low = k.low
            volume = volume.add(k.volume)
            quoteVolume = quoteVolume.add(k.quoteVolume)
            trades += k.trades
        }
        // 桶内根数齐了才认为收盘；单根即一桶时沿用原始收盘标记。
        val closed = if (groupSize == 1) last.closed else group.size >= groupSize
        return Kline(
            openTime = first.openTime,
            closeTime = last.closeTime,
            open = first.open,
            high = high,
            low = low,
            close = last.close,
            volume = volume,
            quoteVolume = quoteVolume,
            trades = trades,
            closed = closed,
        )
    }
}
