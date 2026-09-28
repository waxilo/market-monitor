package com.waxilo.marketmonitor.domain.indicator

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 技术指标纯函数（PRD FR-2.2：本地计算，不依赖任何指标接口）。
 *
 * 约定：输入为不含 NaN 的价格序列；输出与输入等长，窗口不足处为 NaN，绘图时跳过。
 * NaN 而非 0 表示「未定义」，否则图表左端会出现贴零假线。
 */
object Indicators {

    fun sma(input: DoubleArray, period: Int): DoubleArray {
        require(period > 0) { "period must be positive" }
        val out = DoubleArray(input.size) { Double.NaN }
        if (input.size < period) return out
        var sum = 0.0
        for (i in input.indices) {
            sum += input[i]
            if (i >= period) sum -= input[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** 以第 period 根的 SMA 作为种子，之后按 alpha 递推。 */
    fun ema(input: DoubleArray, period: Int): DoubleArray {
        require(period > 0) { "period must be positive" }
        val out = DoubleArray(input.size) { Double.NaN }
        if (input.size < period) return out
        var seed = 0.0
        for (i in 0 until period) seed += input[i]
        var prev = seed / period
        out[period - 1] = prev
        val alpha = 2.0 / (period + 1)
        for (i in period until input.size) {
            prev = alpha * input[i] + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    data class Macd(
        val dif: DoubleArray,
        val signal: DoubleArray,
        val histogram: DoubleArray,
    )

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd {
        val emaFast = ema(close, fast)
        val emaSlow = ema(close, slow)
        val dif = DoubleArray(close.size) { i ->
            if (emaFast[i].isNaN() || emaSlow[i].isNaN()) Double.NaN else emaFast[i] - emaSlow[i]
        }
        val signal = DoubleArray(close.size) { Double.NaN }
        val histogram = DoubleArray(close.size) { Double.NaN }

        val firstValid = dif.indexOfFirst { !it.isNaN() }
        if (firstValid >= 0) {
            // 对 DIF 的有效段再做 EMA，避免 NaN 污染递推。
            val valid = dif.copyOfRange(firstValid, dif.size)
            val smoothed = ema(valid, signalPeriod)
            for (i in smoothed.indices) {
                if (smoothed[i].isNaN()) continue
                signal[firstValid + i] = smoothed[i]
                histogram[firstValid + i] = dif[firstValid + i] - smoothed[i]
            }
        }
        return Macd(dif, signal, histogram)
    }

    /** Wilder 平滑（等价于 alpha = 1/period）。 */
    fun rsi(close: DoubleArray, period: Int = 14): DoubleArray {
        require(period > 0) { "period must be positive" }
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size <= period) return out

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val change = close[i] - close[i - 1]
            if (change >= 0) gainSum += change else lossSum -= change
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        out[period] = rsiOf(avgGain, avgLoss)

        for (i in (period + 1) until close.size) {
            val change = close[i] - close[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiOf(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiOf(avgGain: Double, avgLoss: Double): Double = when {
        avgLoss == 0.0 && avgGain == 0.0 -> 50.0
        avgLoss == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    data class Bollinger(
        val middle: DoubleArray,
        val upper: DoubleArray,
        val lower: DoubleArray,
    )

    fun boll(close: DoubleArray, period: Int = 20, multiplier: Double = 2.0): Bollinger {
        val middle = sma(close, period)
        val upper = DoubleArray(close.size) { Double.NaN }
        val lower = DoubleArray(close.size) { Double.NaN }
        if (close.size < period) return Bollinger(middle, upper, lower)

        for (i in (period - 1) until close.size) {
            val mean = middle[i]
            var variance = 0.0
            for (j in (i - period + 1)..i) {
                val diff = close[j] - mean
                variance += diff * diff
            }
            val deviation = sqrt(variance / period)
            upper[i] = mean + multiplier * deviation
            lower[i] = mean - multiplier * deviation
        }
        return Bollinger(middle, upper, lower)
    }

    data class Kdj(val k: DoubleArray, val d: DoubleArray, val j: DoubleArray)

    /** 经典 KDJ：RSV 以 2/3 前值 + 1/3 当期平滑，初值 50。 */
    fun kdj(
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        period: Int = 9,
        kSmooth: Int = 3,
        dSmooth: Int = 3,
    ): Kdj {
        val size = minOf(high.size, low.size, close.size)
        val k = DoubleArray(size) { Double.NaN }
        val d = DoubleArray(size) { Double.NaN }
        val j = DoubleArray(size) { Double.NaN }
        if (size < period || kSmooth <= 0 || dSmooth <= 0) return Kdj(k, d, j)

        var prevK = 50.0
        var prevD = 50.0
        for (i in (period - 1) until size) {
            var highest = high[i]
            var lowest = low[i]
            for (jj in (i - period + 1)..i) {
                highest = max(highest, high[jj])
                lowest = min(lowest, low[jj])
            }
            val rsv = if (highest == lowest) 50.0 else (close[i] - lowest) / (highest - lowest) * 100.0
            val curK = ((kSmooth - 1) * prevK + rsv) / kSmooth
            val curD = ((dSmooth - 1) * prevD + curK) / dSmooth
            prevK = curK
            prevD = curD
            k[i] = curK
            d[i] = curD
            j[i] = 3 * curK - 2 * curD
        }
        return Kdj(k, d, j)
    }

    /** 主图均线族：返回 period → 序列，供图例逐项绘制。 */
    fun movingAverages(close: DoubleArray, periods: List<Int>): Map<Int, DoubleArray> =
        periods.filter { it > 0 }.associateWith { sma(close, it) }

    /** BOLL 告警线的默认参数，与图上叠加指标保持同一套（N=20, K=2）。 */
    const val BOLL_PERIOD = 20
    const val BOLL_MULTIPLIER = 2.0

    /** 末根收盘价上的 SMA：直接对最后 period 根求均值，省掉整条序列的分配。 */
    fun latestMa(closes: DoubleArray, period: Int): Double? {
        if (period <= 0 || closes.size < period) return null
        var sum = 0.0
        for (i in (closes.size - period) until closes.size) sum += closes[i]
        return (sum / period).takeIf { it.isFinite() }
    }
}

/**
 * 均线带聚合：把多条均线的最新值当一个集合，取现价两侧最近的做上破/下破锚点。
 *
 * 等于现价的值两侧都不算（穿越判定是严格 ABOVE/BELOW，贴着线没有边沿可言）。
 * 候选带成员标识（key → 值），选中哪个成员要回传给引擎做冷却记账。
 */
object MaBand {

    /** 现价上方最近（上破锚点）与下方最近（下破锚点）的成员；不存在的一侧为 null。 */
    fun pick(
        candidates: List<Pair<String, Double>>,
        price: Double,
    ): Pair<Pair<String, Double>?, Pair<String, Double>?> {
        val upper = candidates.filter { it.second > price }.minByOrNull { it.second }
        val lower = candidates.filter { it.second < price }.maxByOrNull { it.second }
        return upper to lower
    }
}
