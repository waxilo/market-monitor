package com.waxilo.marketmonitor.domain.model

/**
 * 持仓量的一个时间点：毫秒时间戳 + **美元名义值**。
 *
 * 各家原生口径不同（张 / 基础币 / 美元），适配器在解析时统一折成美元；
 * 唯一例外是 Bybit——接口只给基础币数量，[OpenInterestSeries.baseCoin] 置起，
 * 对齐到 K 线时再按该根收盘价折美元，与其余三家同口径。
 */
data class OpenInterestPoint(
    val time: Long,
    val value: Double,
)

/**
 * 一次持仓量读取的结果。[points] 按时间升序。
 *
 * 空 [points] 与「没取到」在上层是同一件事：副图整块不出现，不画一块空板。
 */
data class OpenInterestSeries(
    val points: List<OpenInterestPoint>,
    /** true = [points] 是基础币口径，对齐时按 K 线收盘价折美元（Bybit）。 */
    val baseCoin: Boolean = false,
)
