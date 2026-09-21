package com.waxilo.marketmonitor.domain.kline

/** 币安官方提供的 K 线周期，minutes 为周期长度。 */
enum class OfficialInterval(val apiCode: String, val minutes: Long) {
    M1("1m", 1),
    M3("3m", 3),
    M5("5m", 5),
    M15("15m", 15),
    M30("30m", 30),
    H1("1h", 60),
    H2("2h", 120),
    H4("4h", 240),
    H6("6h", 360),
    H8("8h", 480),
    H12("12h", 720),
    D1("1d", 1_440),
    D3("3d", 4_320),
    W1("1w", 10_080),
    MONTH_1M("1M", 43_200),
    ;

    companion object {
        fun fromApiCode(code: String): OfficialInterval? = entries.firstOrNull { it.apiCode == code }
    }
}

/**
 * 图表周期。官方周期直接请求；自定义周期由 [baseInterval] 聚合而来，
 * 因为币安接口只提供固定周期。
 */
data class CandleInterval(val minutes: Long, val official: OfficialInterval?) {

    val isOfficial: Boolean get() = official != null

    /**
     * 实际请求使用的官方周期：目标周期的最大整除官方周期，
     * 使聚合倍率最小、单次 1000 根上限内能覆盖最长历史。
     */
    val baseInterval: OfficialInterval? get() = official ?: pickBaseInterval(minutes)

    /** 聚合倍率（每个自定义蜡烛由多少根基础蜡烛合成）。 */
    val aggregateRatio: Int
        get() {
            val base = baseInterval ?: return 1
            return (minutes / base.minutes).toInt()
        }

    /** REST / WS 请求参数。 */
    val apiCode: String get() = baseInterval?.apiCode ?: ""

    /** 展示名：官方用其码，自定义按分钟数人性化（10m / 45m / 3h / 2d）。 */
    val label: String get() = official?.apiCode ?: humanize(minutes)

    /** 存储用的稳定键。 */
    val storageKey: String get() = if (isOfficial) "o:${official?.apiCode}" else "c:$minutes"

    /** 周期时长（毫秒）。用于按时间窗裁剪查询范围，避免扫全表。 */
    val durationMs: Long get() = minutes * 60_000L

    companion object {

        /** 详情页默认展示的官方周期。 */
        val quickPickPresets: List<CandleInterval> = listOf(
            CandleInterval(1, OfficialInterval.M1),
            CandleInterval(5, OfficialInterval.M5),
            CandleInterval(15, OfficialInterval.M15),
            CandleInterval(30, OfficialInterval.M30),
            CandleInterval(60, OfficialInterval.H1),
            CandleInterval(240, OfficialInterval.H4),
            CandleInterval(480, OfficialInterval.H8),
            CandleInterval(1_440, OfficialInterval.D1),
            CandleInterval(10_080, OfficialInterval.W1),
        )

        fun of(official: OfficialInterval): CandleInterval = CandleInterval(official.minutes, official)

        /** 自定义周期下限 1 分钟，上限不超过 1M，且必须能被某个官方周期整除。 */
        val MINUTES_RANGE: LongRange = 1L..43_200L

        fun custom(minutes: Long): CandleInterval? {
            if (minutes !in MINUTES_RANGE) return null
            if (OfficialInterval.entries.any { it.minutes == minutes }) return null
            return if (pickBaseInterval(minutes) == null) null else CandleInterval(minutes, null)
        }

        fun pickBaseInterval(minutes: Long): OfficialInterval? =
            OfficialInterval.entries
                .filter { it.minutes <= minutes && minutes % it.minutes == 0L }
                .maxByOrNull { it.minutes }

        fun fromStorageKey(key: String): CandleInterval? = when {
            key.startsWith("o:") -> OfficialInterval.fromApiCode(key.removePrefix("o:"))?.let(::of)
            key.startsWith("c:") -> key.removePrefix("c:").toLongOrNull()?.let(::custom)
            else -> null
        }

        fun humanize(minutes: Long): String = when {
            minutes % 43_200L == 0L -> "${minutes / 43_200L}mo"
            minutes % 1_440L == 0L -> "${minutes / 1_440L}d"
            minutes % 60L == 0L -> "${minutes / 60L}h"
            else -> "${minutes}m"
        }
    }
}
