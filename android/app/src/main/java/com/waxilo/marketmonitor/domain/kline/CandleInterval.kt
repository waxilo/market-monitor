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

    /** REST 请求参数。 */
    val apiCode: String get() = baseInterval?.apiCode ?: ""

    /**
     * 展示名：**中文短名**（`15分` / `1时` / `1天` / `1周` / `1月`），与桌面端周期条、
     * 以及「自定义周期」对话框里的单位名是同一套字。
     *
     * 只换单位不换数字：`4h` 写成 `4时` 而不是「4 小时」—— 这一栏在周期条 chip、
     * EMA 面板行尾这种窄地方出现，多一个字就挤掉价位。
     */
    val label: String get() = humanize(minutes)

    /**
     * **旧版**展示名（`1h` / `15m` / `4h`）：改中文之前周期条上就是这串，只留给
     * 「认领老告警线」用（见 `EmaAnalysis.lineOf`）—— 0.9.51 之前自动命名是 `1hEMA50`，
     * 不认它就等于把用户之前画的线全变成孤儿。界面上不再出现它。
     */
    val legacyLabel: String get() = official?.apiCode ?: humanizeLegacy(minutes)

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

        /**
         * 分钟数 → 中文短名。与桌面端 `intervals.ts` 的 `SHORT_UNIT` 同一套单位
         * （分 / 时 / 天 / 周 / 月），顺序必须由粗到细：`10080`（1 周）先被「周」认走，
         * 落不到「7 天」上；`43200` 同理。
         */
        fun humanize(minutes: Long): String = when {
            minutes % 43_200L == 0L -> "${minutes / 43_200L}月"
            minutes % 10_080L == 0L -> "${minutes / 10_080L}周"
            minutes % 1_440L == 0L -> "${minutes / 1_440L}天"
            minutes % 60L == 0L -> "${minutes / 60L}时"
            else -> "${minutes}分"
        }

        /** 改中文之前的英文短名（`1mo` / `2d` / `3h` / `45m`）：只为认领老线，别在界面上用。 */
        fun humanizeLegacy(minutes: Long): String = when {
            minutes % 43_200L == 0L -> "${minutes / 43_200L}mo"
            minutes % 1_440L == 0L -> "${minutes / 1_440L}d"
            minutes % 60L == 0L -> "${minutes / 60L}h"
            else -> "${minutes}m"
        }
    }
}
