package com.waxilo.marketmonitor.domain.indicator

import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.kline.CandleInterval

/**
 * EMA 压力支撑分析：「所有已配置的 EMA 档位 × 所有已配置的 K 线周期」的当前 EMA 值
 * 汇总成一张对照最新价的表，按上下分成**上方压力 / 下方支撑**。
 *
 * 与桌面端 `desktop/src/lib/emaAnalysis.ts` 同一套口径（连同两个非显然的取舍）：
 *
 *  1. **取数根数与图上的每段历史对齐** —— 同一档 EMA 在表里的值必须与图上那条线右端的
 *     值一致，否则用户会看到「表里 68,900、图上 68,850」这种没法解释的差。所以下限就是
 *     [WINDOW_MIN]；只有配了比它更长的档位才往上加，上限 [WINDOW_MAX]（各家单次取数的硬顶）。
 *
 *  2. **算不出来的一律不进表**（取数不足 / 返回值坏），由调用方另外报「哪几档缺数据」。
 *     把 NaN 混进排序里会让「最近的一道压力」整个失真 —— 排序里 NaN 的比较永远为假，
 *     它会随机停在任意位置。
 *
 * 落库形态与桌面端不同：桌面端一条「系统划线」是带 `origin` 的独立实体，Android 这边
 * 告警线由 `alert_rule` 派生（一条规则 = 一条线），所以**回显靠规则名**认人，见 [note]。
 */

/** 一条 EMA 水位（还没跟现价比较）。 */
data class EmaLevel(
    /** 该 K 线周期。 */
    val interval: CandleInterval,
    /** EMA 期数。 */
    val period: Int,
    /** 最后一根 K 线上的 EMA 值。 */
    val value: Double,
)

/** 一条水位 + 与现价的偏离（正 = 在现价上方）。 */
data class EmaLevelRow(
    val level: EmaLevel,
    val gapPercent: Double,
) {
    val interval: CandleInterval get() = level.interval
    val period: Int get() = level.period
    val value: Double get() = level.value
}

data class EmaBuckets(
    /** 上方压力：EMA 值 > 现价，**离现价最近的在前**（升序）。 */
    val resistance: List<EmaLevelRow> = emptyList(),
    /** 下方支撑：EMA 值 < 现价，**离现价最近的在前**（降序）。 */
    val support: List<EmaLevelRow> = emptyList(),
)

/** 某个周期整段没取回来（网络 / 数据源不通，值得重试）。 */
data class EmaFailure(val interval: CandleInterval, val reason: String)

/** 某一档算不出来：取回的根数不够（EMA 至少要 period 根）。 */
data class EmaMissing(val interval: CandleInterval, val period: Int, val bars: Int)

/**
 * 一次扫描的完整结果 —— 面板那几块状态就是它（表 / 两类异常 / 兜底价）。
 *
 * 打包成一个对象而不是四个 state：静默刷新要求「旧值一直可用，新值一次换到位」，
 * 分开 set 会渲染出「新表配旧异常」这种中间态。
 */
data class EmaScan(
    val levels: List<EmaLevel> = emptyList(),
    val failures: List<EmaFailure> = emptyList(),
    val missing: List<EmaMissing> = emptyList(),
    /** 取数失败但表里**沿用了上一轮数值**的周期（提示语得说清这些不是最新的）。 */
    val stale: List<CandleInterval> = emptyList(),
    /** 行情没到时的兜底基准：最细周期最后一根的收盘价（取不到给 NaN）。 */
    val fallbackPrice: Double = Double.NaN,
)

/** 图上与某一档 EMA 绑定的那条告警线（面板行据此回显）。 */
data class EmaLineRef(
    /** 那条告警规则 id —— 改它 / 删它都要用。 */
    val ruleId: Long,
    /** 线上的价位 —— **不是**当前 EMA 值：线停在画它的那一刻。 */
    val price: Double,
    /** 这条线的告警开着吗（关掉的线仍画在图上，但不会响）。 */
    val enabled: Boolean,
    /** 规则名（= 自动备注，用户可能改过）。 */
    val name: String,
    /** 线上价位与这一档的当前 EMA 值是否一致（不一致 = 漂了）。 */
    val aligned: Boolean,
)

/** 点了面板里一行之后对告警线该做什么。 */
sealed interface EmaTick {
    /** 这一档还没有线：新建一条告警线。 */
    data object Add : EmaTick

    /** 已有线但漂了（或告警被关掉）：拉到当前值，顺带把告警打开。 */
    data class Align(val ruleId: Long) : EmaTick

    /** 已对齐且开着告警：删掉它（同一个条目再点一下即取消）。 */
    data class Remove(val ruleId: Long) : EmaTick

    /** 价位非法，什么都不做。 */
    data object None : EmaTick
}

object EmaAnalysis {

    /** 取数根数下限：与图上一段历史同口径（见文件头注）。 */
    const val WINDOW_MIN = 300

    /** 取数根数上限：各家单次 K 线请求的硬顶（超过会被方言层夹掉，不如自己先夹）。 */
    const val WINDOW_MAX = 1000

    /**
     * 一次取多少根：至少 [WINDOW_MIN]，最长的那一档再留 20 根余量。
     * 300 根时 EMA50 已经跑到 6 倍长度，收敛得很干净；EMA200 这类长档才会往上加。
     */
    fun window(periods: Collection<Int>): Int {
        val longest = periods.filter { it > 0 }.maxOrNull() ?: 0
        return (longest + 20).coerceIn(WINDOW_MIN, WINDOW_MAX)
    }

    /** 一段收盘价在最后一根上的 EMA 值；根数不足 `period`（或参数非法）给 NaN。 */
    fun lastEma(closes: DoubleArray, period: Int): Double {
        if (closes.isEmpty() || period <= 0) return Double.NaN
        val values = Indicators.ema(closes, period)
        return values.lastOrNull() ?: Double.NaN
    }

    /**
     * 与现价比较并分档。
     *
     * - 恰好等于现价的水位两边都不进（它既不是压力也不是支撑，算进任何一边都会让「最近一道」
     *   多出一条零距离的假信号）；
     * - 同价的水位按「周期更细、档位更短」在前，列表顺序因此是确定的，不随取数完成先后跳动；
     * - `price` 非正/非法时整表为空 —— 宁可空着让调用方提示「还没拿到最新价」，也不拿 0 当基准。
     */
    fun split(levels: List<EmaLevel>, price: Double): EmaBuckets {
        if (!price.isFinite() || price <= 0) return EmaBuckets()
        val rows = levels
            .filter { it.value.isFinite() && it.value > 0 }
            .map { EmaLevelRow(it, ((it.value - price) / price) * 100) }

        val tieBreak = compareBy<EmaLevelRow>({ it.interval.minutes }, { it.period }, { it.interval.label })

        return EmaBuckets(
            resistance = rows.filter { it.value > price }.sortedWith(compareBy<EmaLevelRow> { it.value }.then(tieBreak)),
            support = rows.filter { it.value < price }
                .sortedWith(compareByDescending<EmaLevelRow> { it.value }.then(tieBreak)),
        )
    }

    /**
     * 把这一轮的结果并到上一轮上 —— **静默刷新**的核心：宁可留着旧值，也不把表清空。
     *
     * 两条合并规则，各自对应一种「这次没拿到」：
     *
     *  1. 整段取数失败（[EmaScan.failures]）：该周期上一轮算出来的各档**原样留下**，并把周期
     *     记进 [EmaScan.stale]，提示语据此说明「这几列是上次的值」。清掉它们等于让一道压力线
     *     在断网时凭空消失，比显示略旧的值危险得多。
     *  2. 根数不够（[EmaScan.missing]）而上一轮算得出：同样沿用旧值，并从 `missing` 里划掉 ——
     *     这种情况多半是这一轮恰好少回了几根（换源、翻页边界），不代表这一档真的没了。
     *
     * 只认当前配置范围内的周期：配置里删掉的周期/档位不会从上一轮被捡回来（否则会出现
     * 「已经删了 EMA50，表里还挂着一个 EMA50」）。兜底价拿不到时同样沿用上一轮的。
     */
    fun merge(prev: EmaScan?, next: EmaScan): EmaScan {
        if (prev == null) return next
        val failed = next.failures.map { it.interval.storageKey }.toSet()
        val missing = next.missing.map { key(it.interval, it.period) }.toSet()
        val prevKeys = prev.levels.map { key(it.interval, it.period) }.toSet()

        val kept = mutableListOf<EmaLevel>()
        val stale = mutableListOf<CandleInterval>()
        for (level in prev.levels) {
            if (level.interval.storageKey in failed) {
                kept += level
                if (stale.none { it.storageKey == level.interval.storageKey }) stale += level.interval
                continue
            }
            if (key(level.interval, level.period) in missing) kept += level
        }

        val fallback = if (next.fallbackPrice.isFinite() && next.fallbackPrice > 0) {
            next.fallbackPrice
        } else {
            prev.fallbackPrice
        }

        return EmaScan(
            levels = next.levels + kept,
            failures = next.failures,
            missing = next.missing.filter { key(it.interval, it.period) !in prevKeys },
            stale = stale,
            fallbackPrice = fallback,
        )
    }

    /**
     * 从面板画一条线时自动写上的规则名，如 `1时EMA50`、`4时EMA12`、`1天EMA200`。
     *
     * 周期那一半**用周期条上那枚方块的字**（[CandleInterval.label]）：用户是照着面板点的，
     * 图上 / 预警列表里写同样的字才对得上号；换成「1 小时」这类全称就得在两处维护同一套
     * 单位表，早晚会有一处写漏。
     *
     * 这个名字同时是**回显的识别键**（见 [lineOf]）：自动名是 (周期, 档位) 的双射，
     * 按它认不会串档。代价是用户把规则名改掉之后面板就认不回来了 —— 这与「名字就是
     * 这条线的出处」的口径一致（桌面端对没有 `origin` 的老线也是这么处理的）。
     */
    fun note(interval: CandleInterval, period: Int): String = "${interval.label}EMA$period"

    /**
     * **旧版**自动名（`1hEMA50`，周期用英文码）。
     *
     * 0.9.51 把周期条改成中文短名（`1h → 1时`）之前，面板画出来的线都叫这串。
     * 认领时要把它们一起认下：不认的话，用户升级后打开面板会看到自己画的线既没有
     * 「已画」标记、又被当成孤儿线提示一遍（而提示里让他「改回配置」根本改不回来）。
     */
    fun legacyNote(interval: CandleInterval, period: Int): String = "${interval.legacyLabel}EMA$period"

    /**
     * 在告警规则表里找这一档 EMA 对应的线，找不到给 null。
     *
     * 按**规则名精确等于自动备注**认人：Android 的告警线由 `alert_rule` 派生，规则里
     * 没有 (周期, 档位) 这样的结构化身份字段，名字是唯一能跨重启活下来的线索。
     * 允许两个名字：现在的 [note]（`1时EMA50`）与 [legacyNote]（`1hEMA50`）——
     * 两种都代表「这一档画过」，新画的线一律用前者。
     *
     * 同身份撞上多条时只认**第一条**（`minByOrNull`）：回显回答的是「这一档画过没有」，
     * 不是「画了几条」。
     */
    fun lineOf(rules: List<AlertRule>, level: EmaLevel, note: String = note(level.interval, level.period)): EmaLineRef? {
        if (note.isEmpty()) return null
        val names = setOf(note, legacyNote(level.interval, level.period))
        val rule = rules.asSequence()
            .filter { it.name in names }
            .mapNotNull { r -> r.threshold?.let { threshold -> r to threshold } }
            .minByOrNull { pair -> pair.first.id }
            ?: return null
        val price = rule.second.toDouble()
        if (!price.isFinite() || price <= 0) return null
        return EmaLineRef(
            ruleId = rule.first.id,
            price = price,
            enabled = rule.first.enabled,
            name = rule.first.name,
            aligned = price == level.value,
        )
    }

    /**
     * 面板里点一行 = 对告警线做**一次**改动，三种结果：
     *
     *  - 这一档还**没有**线 → 新建一条（自动名 + 达到条件 = 穿越即提醒）；
     *  - 有、且价位与当前值一致、还开着告警 → 删掉（「再点一下取消」）；
     *  - 有、但漂了（或告警被关掉）→ **拉到当前值**（[EmaTick.Align]）：这一档的位置变了，
     *    线留在旧价上就成了一条没人认领的横线。
     *
     * **不做桌面端那个「同价收编」**（adopt）：那边是为了避免两条重合的线删起来像没反应，
     * 但要收编就得改一条**用户自己建的规则**的名字；Android 的告警规则是会响、会推送的
     * 真资产，把它悄悄改名成 `1时EMA50` 比多一条重合线糟得多。何况 EMA 是带小数的值，
     * 精确撞上手建阈值的概率约等于零 —— 真撞上了也只是多一条线，不会动到用户的规则。
     */
    fun tick(rules: List<AlertRule>, level: EmaLevel, note: String = note(level.interval, level.period)): EmaTick {
        if (!level.value.isFinite() || level.value <= 0) return EmaTick.None
        val hit = lineOf(rules, level, note)
        return when {
            hit == null -> EmaTick.Add
            hit.aligned && hit.enabled -> EmaTick.Remove(hit.ruleId)
            else -> EmaTick.Align(hit.ruleId)
        }
    }

    /**
     * 图上有、但**当前配置里已经没有对应行**的系统划线（改过 EMA 目录 / 周期条）。
     *
     * 行内回显按 (周期, 档位) 配对，这类线天然配不上任何一行 —— 不点一句出来，用户就只能
     * 在图上看见一条带 `1时EMA50` 名字的线、却在面板里怎么也找不到它。
     * 判据用**配置**而不是扫描结果：某一档这次没取到数也只管显示，不代表它被删了。
     * 新旧两种命名都算「在配置里」，否则升级后每条老线都会诈一次尸。
     */
    fun orphanLineNames(rules: List<AlertRule>, intervals: List<CandleInterval>, periods: List<Int>): List<String> {
        if (intervals.isEmpty() || periods.isEmpty()) return emptyList()
        val configured = intervals
            .flatMap { iv -> periods.flatMap { p -> listOf(note(iv, p), legacyNote(iv, p)) } }
            .toSet()
        return rules.map { it.name }.filter { AUTO_NAME.containsMatchIn(it) && it !in configured }
    }

    /**
     * 自动生成的名字长什么样：`<周期短名>EMA<期数>`，期数为正整数。
     *
     * 周期短名 0.9.51 起是中文（`1时`），之前是英文码（`1h`）—— 两种都要认，
     * 所以字符集是「汉字或拉丁字母」。
     */
    private val AUTO_NAME = Regex("^[0-9]+[\\p{IsHan}a-zA-Z]+EMA[0-9]+$")

    private fun key(interval: CandleInterval, period: Int): String = "${interval.storageKey}|$period"
}
