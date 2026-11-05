package com.waxilo.marketmonitor.domain.indicator

import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import java.math.BigDecimal

/**
 * 均线压力支撑分析（MA 简单 / EMA 指数两族共用）：「所有已配置档位 × 所有已配置 K 线周期」
 * 的当前值汇总成一张对照最新价的表，按上下分成**上方压力 / 下方支撑**。
 *
 * 与桌面端 `desktop/src/lib/maAnalysis.ts` 同一套口径（连同两个非显然的取舍）：
 *
 *  1. **取数根数由 [MaAnalysis.window] 一处决定**（最长档位 × 4，夹在 500~1000），图表首屏
 *     也按它取 —— 同一档均线在表里的值必须与图上那条线右端的值一致，否则用户会看到
 *     「表里 68,900、图上 68,850」这种没法解释的差；而根数太少时长档 EMA 会被 SMA 种子拖偏
 *     （周线 EMA200 取 300 根时种子残留 37%，见 [MaAnalysis.WARMUP_FACTOR]）。
 *     SMA 没有预热问题，多取几十根只是让面板与图表看到同一段历史。
 *
 *  2. **算不出来的一律不进表**（取数不足 / 返回值坏），由调用方另外报「哪几档缺数据」。
 *     把 NaN 混进排序里会让「最近的一道压力」整个失真 —— 排序里 NaN 的比较永远为假，
 *     它会随机停在任意位置。
 *
 * 落库形态与桌面端不同：桌面端一条「系统划线」是带 `origin` 的独立实体，Android 这边
 * 告警线由 `alert_rule` 派生（一条规则 = 一条线），所以**回显靠规则名**认人，见 [MaAnalysis.note]；
 * **跟随也靠名字**（[MaAnalysis.alignments] 按名字把线拉到新值，每轮刷新一次）。
 * 名字里带族名（`MA` / `EMA`），两族的线因此互不串认。
 */

/** 一条均线水位（还没跟现价比较）。 */
data class MaLevel(
    /** 该 K 线周期。 */
    val interval: CandleInterval,
    /** 均线期数。 */
    val period: Int,
    /** 最后一根 K 线上的均线值。 */
    val value: Double,
)

/** 一条水位 + 与现价的偏离（正 = 在现价上方）。 */
data class MaLevelRow(
    val level: MaLevel,
    val gapPercent: Double,
) {
    val interval: CandleInterval get() = level.interval
    val period: Int get() = level.period
    val value: Double get() = level.value
}

data class MaBuckets(
    /** 上方压力：均线值 > 现价，**离现价最近的在前**（升序）。 */
    val resistance: List<MaLevelRow> = emptyList(),
    /** 下方支撑：均线值 < 现价，**离现价最近的在前**（降序）。 */
    val support: List<MaLevelRow> = emptyList(),
)

/** 某个周期整段没取回来（网络 / 数据源不通，值得重试）。 */
data class MaFailure(val interval: CandleInterval, val reason: String)

/** 某一档算不出来：取回的根数不够（均线至少要 period 根）。 */
data class MaMissing(val interval: CandleInterval, val period: Int, val bars: Int)

/**
 * 一次扫描的完整结果 —— 面板那几块状态就是它（表 / 两类异常 / 兜底价）。
 *
 * 打包成一个对象而不是四个 state：静默刷新要求「旧值一直可用，新值一次换到位」，
 * 分开 set 会渲染出「新表配旧异常」这种中间态。
 */
data class MaScan(
    val levels: List<MaLevel> = emptyList(),
    val failures: List<MaFailure> = emptyList(),
    val missing: List<MaMissing> = emptyList(),
    /** 取数失败但表里**沿用了上一轮数值**的周期（提示语得说清这些不是最新的）。 */
    val stale: List<CandleInterval> = emptyList(),
    /** 行情没到时的兜底基准：最细周期最后一根的收盘价（取不到给 NaN）。 */
    val fallbackPrice: Double = Double.NaN,
)

/** 图上与某一档均线绑定的那条告警线（面板行据此回显）。 */
data class MaLineRef(
    /** 那条告警规则 id —— 改它 / 删它都要用。 */
    val ruleId: Long,
    /**
     * 线上的价位 = 它**现在**在图上钉在哪儿。
     *
     * 它与这一档的当前均线值不等，只说明「刚漂过」：用户刚把线拖走，或者这一轮没取到数。
     * 正常情况下每轮刷新都会把线拉回当前值（见 [MaAnalysis.alignments]）。
     */
    val price: Double,
    /** 这条线的告警开着吗（关掉的线仍画在图上，但不会响）。 */
    val enabled: Boolean,
    /** 规则名（= 自动备注，用户可能改过）。 */
    val name: String,
    /** 线上价位与这一档的当前均线值是否一致（不一致 = 漂了，下一轮刷新会拉回）。 */
    val aligned: Boolean,
)

/**
 * 一条**要挪的系统划线**：把 [ruleId] 的阈值改成 [price]。
 *
 * 只描述「该改成什么」，不碰数据库 —— 落库要 suspend，纯函数层留在这里是为了能单测。
 */
data class MaAlign(
    val ruleId: Long,
    /** 目标档位（周期 + 档位 + 新值）。 */
    val level: MaLevel,
) {
    val price: BigDecimal get() = BigDecimal.valueOf(level.value)
}

/**
 * 一档均线的尾段曲线（面板缩略图用）。
 *
 * [closes] 与 [values] 逐点对齐、长度相同 —— 画在同一根纵轴上才看得出
 * 「价格在均线上方还是下方、均线本身是抬还是压」。
 */
class MaCurve(
    val interval: CandleInterval,
    val period: Int,
    val closes: DoubleArray,
    val values: DoubleArray,
)

/** 点了面板里一行之后对告警线该做什么。 */
sealed interface MaTick {
    /** 这一档还没有线：新建一条告警线。 */
    data object Add : MaTick

    /** 已有线但漂了（或告警被关掉）：拉到当前值，顺带把告警打开。 */
    data class Align(val ruleId: Long) : MaTick

    /** 已对齐且开着告警：删掉它（同一个条目再点一下即取消）。 */
    data class Remove(val ruleId: Long) : MaTick

    /** 价位非法，什么都不做。 */
    data object None : MaTick
}

object MaAnalysis {

    /** 取数根数下限（短档位也至少取这么多，图表首屏的历史长度也由它兜底）。 */
    const val WINDOW_MIN = 500

    /** 取数根数上限：各家单次 K 线请求的硬顶（超过会被方言层夹掉，不如自己先夹）。 */
    const val WINDOW_MAX = 1000

    /**
     * 预热倍数：取「最长档位 × 4」根。
     *
     * EMA 以前 N 根的 SMA 作种子，种子在末值里的残留权重 ≈ `(1 − 2/(N+1))^(根数 − N)`：
     * 4 倍长度时约 0.25%，与「从上市第一根算起」的 TradingView 等平台基本对得上；
     * 旧口径「最长档 + 20、下限 300」下周线 EMA200 的残留高达 37%，值随取数起点漂移几百美元。
     * 历史不足 1000 根的粗周期（币安 BTC 周线只有 ~480 根）因此等于取全量历史。
     */
    const val WARMUP_FACTOR = 4

    /** 一次取多少根：`最长档位 × [WARMUP_FACTOR]`，夹在 [[WINDOW_MIN], [WINDOW_MAX]]。 */
    fun window(periods: Collection<Int>): Int {
        val longest = periods.filter { it > 0 }.maxOrNull() ?: 0
        return (longest.toLong() * WARMUP_FACTOR).coerceIn(WINDOW_MIN.toLong(), WINDOW_MAX.toLong()).toInt()
    }

    /** 一段收盘价在最后一根上的均线值；根数不足 `period`（或参数非法）给 NaN。 */
    fun last(family: MaFamily, closes: DoubleArray, period: Int): Double {
        if (closes.isEmpty() || period <= 0) return Double.NaN
        val values = series(family, closes, period)
        return values.lastOrNull() ?: Double.NaN
    }

    /**
     * 缩略图最多保留的根数（双指缩小的上限）。
     *
     * 与桌面端 `PREVIEW_BARS` 同值：默认只画 120 根，够看出趋势拐点又不至于压成
     * 一条毛毛虫；想看更长历史再往外捏。
     */
    const val PREVIEW_BARS = 240

    /**
     * 算出一档均线的尾段曲线 —— **与 [last] 同一次递推**，所以表里的值与缩略图右端
     * 永远对得上。
     *
     * 只在窗口起点截取（不拿尾段重算）：EMA 是全历史递推量，重算会得到另一条线。
     * 开窗可能落在均线还是 NaN 的预热段上（历史刚好够算这一档时），起点往后挪到
     * 第一个有效值 —— 曲线里混进 NaN 会把整条线画没。
     */
    fun curve(
        family: MaFamily,
        interval: CandleInterval,
        closes: DoubleArray,
        period: Int,
        bars: Int = PREVIEW_BARS,
    ): MaCurve? {
        if (closes.isEmpty() || period <= 0) return null
        val values = series(family, closes, period)
        var end = closes.size
        while (end > 0 && !(values[end - 1].isFinite() && closes[end - 1].isFinite())) end--
        if (end == 0) return null
        var start = maxOf(0, end - maxOf(2, bars))
        while (start < end - 1 && !values[start].isFinite()) start++
        return MaCurve(
            interval = interval,
            period = period,
            closes = closes.copyOfRange(start, end),
            values = values.copyOfRange(start, end),
        )
    }

    /** 一档 (周期, 档位) 的稳定键 —— 缩略图缓存用它当 Map 的 key。 */
    fun curveKey(interval: CandleInterval, period: Int): String = key(interval, period)

    /**
     * 与现价比较并分档。
     *
     * - 恰好等于现价的水位两边都不进（它既不是压力也不是支撑，算进任何一边都会让「最近一道」
     *   多出一条零距离的假信号）；
     * - 同价的水位按「周期更细、档位更短」在前，列表顺序因此是确定的，不随取数完成先后跳动；
     * - `price` 非正/非法时整表为空 —— 宁可空着让调用方提示「还没拿到最新价」，也不拿 0 当基准。
     */
    fun split(levels: List<MaLevel>, price: Double): MaBuckets {
        if (!price.isFinite() || price <= 0) return MaBuckets()
        val rows = levels
            .filter { it.value.isFinite() && it.value > 0 }
            .map { MaLevelRow(it, ((it.value - price) / price) * 100) }

        val tieBreak = compareBy<MaLevelRow>({ it.interval.minutes }, { it.period }, { it.interval.label })

        return MaBuckets(
            resistance = rows.filter { it.value > price }.sortedWith(compareBy<MaLevelRow> { it.value }.then(tieBreak)),
            support = rows.filter { it.value < price }
                .sortedWith(compareByDescending<MaLevelRow> { it.value }.then(tieBreak)),
        )
    }

    /**
     * 把这一轮的结果并到上一轮上 —— **静默刷新**的核心：宁可留着旧值，也不把表清空。
     *
     * 两条合并规则，各自对应一种「这次没拿到」：
     *
     *  1. 整段取数失败（[MaScan.failures]）：该周期上一轮算出来的各档**原样留下**，并把周期
     *     记进 [MaScan.stale]，提示语据此说明「这几列是上次的值」。清掉它们等于让一道压力线
     *     在断网时凭空消失，比显示略旧的值危险得多。
     *  2. 根数不够（[MaScan.missing]）而上一轮算得出：同样沿用旧值，并从 `missing` 里划掉 ——
     *     这种情况多半是这一轮恰好少回了几根（换源、翻页边界），不代表这一档真的没了。
     *
     * 只认当前配置范围内的周期：配置里删掉的周期/档位不会从上一轮被捡回来（否则会出现
     * 「已经删了 MA20，表里还挂着一个 MA20」）。兜底价拿不到时同样沿用上一轮的。
     */
    fun merge(prev: MaScan?, next: MaScan): MaScan {
        if (prev == null) return next
        val failed = next.failures.map { it.interval.storageKey }.toSet()
        val missing = next.missing.map { key(it.interval, it.period) }.toSet()
        val prevKeys = prev.levels.map { key(it.interval, it.period) }.toSet()

        val kept = mutableListOf<MaLevel>()
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

        return MaScan(
            levels = next.levels + kept,
            failures = next.failures,
            missing = next.missing.filter { key(it.interval, it.period) !in prevKeys },
            stale = stale,
            fallbackPrice = fallback,
        )
    }

    /**
     * 从面板画一条线时自动写上的规则名，如 `1时MA20`、`4时EMA12`、`1天EMA200`。
     *
     * 周期那一半**用周期条上那枚方块的字**（[CandleInterval.label]）：用户是照着面板点的，
     * 图上 / 预警列表里写同样的字才对得上号；换成「1 小时」这类全称就得在两处维护同一套
     * 单位表，早晚会有一处写漏。
     *
     * 这个名字同时是**回显的识别键**（见 [lineOf]）：自动名是 (族, 周期, 档位) 的双射，
     * 按它认不会串档（也不会把 MA 的线认成 EMA 的——名字里都带族名）。代价是用户把规则名
     * 改掉之后面板就认不回来了 —— 这与「名字就是这条线的出处」的口径一致
     * （桌面端对没有 `origin` 的老线也是这么处理的）。
     */
    fun note(family: MaFamily, interval: CandleInterval, period: Int): String =
        "${interval.label}${family.name}$period"

    /**
     * 这串名字是不是面板自动起的（任一族的 [note] / [legacyNote] 形态，`8时EMA50` / `1hMA20`）。
     *
     * 图上挂牌用：自动名直接充当备注（不写库），用户的预警在被问到「这根线是哪一档」
     * 时就不用去预警页翻。用户自己起的名字（多半带空格或不含均线档位）不会误伤。
     * 不分族 —— 挂牌只关心「这名字是不是自动生成的」。
     */
    fun isAutoNote(name: String?): Boolean =
        name != null && (AUTO_EMA.matches(name) || AUTO_MA.matches(name))

    /**
     * 这串名字是不是**这一族**面板的自动名（清空划线 / 孤儿提示按族收线）。
     *
     * MA 的判据要排除前面紧挨着 `E` 的那种（`1时EMA50` 里也含 `MA50`），
     * 否则 MA 面板的「清空划线」会把 EMA 的线一起收掉。
     */
    fun isFamilyAutoNote(name: String?, family: MaFamily): Boolean =
        name != null && autoPattern(family).matches(name)

    /**
     * **旧版**自动名（`1hEMA50`，周期用英文码）。
     *
     * 0.9.51 把周期条改成中文短名（`1h → 1时`）之前，面板画出来的线都叫这串。
     * 认领时要把它们一起认下：不认的话，用户升级后打开面板会看到自己画的线既没有
     * 「已画」标记、又被当成孤儿线提示一遍（而提示里让他「改回配置」根本改不回来）。
     */
    fun legacyNote(family: MaFamily, interval: CandleInterval, period: Int): String =
        "${interval.legacyLabel}${family.name}$period"

    /**
     * 在告警规则表里找这一档均线对应的线，找不到给 null。
     *
     * 按**规则名精确等于自动备注**认人：Android 的告警线由 `alert_rule` 派生，规则里
     * 没有 (周期, 档位) 这样的结构化身份字段，名字是唯一能跨重启活下来的线索。
     * 允许两个名字：现在的 [note]（`1时MA20`）与 [legacyNote]（`1hMA20`）——
     * 两种都代表「这一档画过」，新画的线一律用前者。
     *
     * 同身份撞上多条时只认**第一条**（`minByOrNull`）：回显回答的是「这一档画过没有」，
     * 不是「画了几条」。
     */
    fun lineOf(
        rules: List<AlertRule>,
        family: MaFamily,
        level: MaLevel,
        note: String = note(family, level.interval, level.period),
    ): MaLineRef? {
        if (note.isEmpty()) return null
        val names = setOf(note, legacyNote(family, level.interval, level.period))
        val rule = rules.asSequence()
            .filter { it.name in names }
            .mapNotNull { r -> r.threshold?.let { threshold -> r to threshold } }
            .minByOrNull { pair -> pair.first.id }
            ?: return null
        val price = rule.second.toDouble()
        if (!price.isFinite() || price <= 0) return null
        return MaLineRef(
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
     *  - 有、但漂了（或告警被关掉）→ **拉到当前值**（[MaTick.Align]）：这一档的位置变了，
     *    线留在旧价上就成了一条没人认领的横线。
     *
     * **不做桌面端那个「同价收编」**（adopt）：那边是为了避免两条重合的线删起来像没反应，
     * 但要收编就得改一条**用户自己建的规则**的名字；Android 的告警规则是会响、会推送的
     * 真资产，把它悄悄改名成 `1时MA20` 比多一条重合线糟得多。何况均线是带小数的值，
     * 精确撞上手建阈值的概率约等于零 —— 真撞上了也只是多一条线，不会动到用户的规则。
     */
    fun tick(
        rules: List<AlertRule>,
        family: MaFamily,
        level: MaLevel,
        note: String = note(family, level.interval, level.period),
    ): MaTick {
        if (!level.value.isFinite() || level.value <= 0) return MaTick.None
        val hit = lineOf(rules, family, level, note)
        return when {
            hit == null -> MaTick.Add
            hit.aligned && hit.enabled -> MaTick.Remove(hit.ruleId)
            else -> MaTick.Align(hit.ruleId)
        }
    }

    /**
     * 一轮扫描结束后**该落到线上的改动**：把图上这一族的自动线拉到各自的新值。
     *
     * 与桌面端 `alignLevelLines` 同一条口径 —— 「线跟着均线走」这句话的唯一执行点。
     * 均线每分钟都在动，线是拿来看的一道墙，墙必须长在均线 **现在**的位置上；
     * 停在画它的那一刻只会让面板说「已画」、图上那条线却离得老远。
     *
     * 三条边界，每条都对应一类不该被碰的线：
     *
     *  1. **认不出的名字不动**（[AlertRule.name] 不等于这一族任何一档的 [note] / [legacyNote]）。
     *     用户手建的预警线（`BTC 十万`）价位是他自己的判断，刷新凭什么改；另一族面板画的
     *     线也不在名单里，所以 MA 面板刷新不会顺手挪动 EMA 的线。
     *  2. **只改「达到」条件**（[AlertCondition.REACH]）：这一类规则的触发判据是
     *     「两次观测价之间穿过了阈值」，只挪阈值不会凭空造出一次穿越（价格确实从旧阈值
     *     走到新阈值那一侧时算真穿越，那本来就是一次穿越）。而 ABOVE / BELOW 是水平比较
     *     （`price >= target`），阈值一挪「满足」状态可能当场翻转，于是凭空弹一条提醒 ——
     *     所以这类规则的线一律不自动改，用户点了面板那一行才走人工对齐。
     *  3. **只改阈值一个字段**：名字（回显的钥匙）、开关（用户可能特意关掉）、备注、
     *     重复模式一概保留。**也不动 [com.waxilo.marketmonitor.domain.alert.AlertState]**：
     *     清状态会把 `ONCE` 的 fired 闸门重新打开，于是「只响一次」的规则每 60 秒复活一次；
     *     而 REACH 的边沿判定读的是**价格**（`lastPrice`），不是阈值，不清也不会误判。
     *
     * 同一档撞上多条同名规则（旧逻辑叠出来的）时**全部拉过去**：它们本来就是同一条均线的
     * 重复落款，留几条在旧价上反而更难收拾。
     */
    fun alignments(rules: List<AlertRule>, family: MaFamily, levels: List<MaLevel>): List<MaAlign> {
        if (rules.isEmpty() || levels.isEmpty()) return emptyList()
        // 名字是唯一的跨重启线索（Android 的线上没有 (周期, 档位) 这样的结构化身份字段）
        val byName = HashMap<String, MaLevel>(levels.size * 2)
        for (level in levels) {
            if (!level.value.isFinite() || level.value <= 0) continue
            byName[note(family, level.interval, level.period)] = level
            byName[legacyNote(family, level.interval, level.period)] = level
        }
        if (byName.isEmpty()) return emptyList()

        val out = ArrayList<MaAlign>(rules.size)
        for (rule in rules) {
            if (rule.condition != AlertCondition.REACH) continue
            val level = byName[rule.name] ?: continue
            val threshold = rule.threshold ?: continue
            // compareTo 而不是 equals：库里存的可能是 `68000.0`、算出来的是 `68000`，
            // 这两个 BigDecimal 不 equals，但就是同一个价位
            if (BigDecimal.valueOf(level.value).compareTo(threshold) == 0) continue
            out += MaAlign(rule.id, level)
        }
        return out
    }

    /**
     * 图上有、但**当前配置里已经没有对应行**的**本族**系统划线（改过目录 / 周期条）。
     *
     * 行内回显按 (周期, 档位) 配对，这类线天然配不上任何一行 —— 不点一句出来，用户就只能
     * 在图上看见一条带 `1时MA20` 名字的线、却在面板里怎么也找不到它。
     * 判据用**配置**而不是扫描结果：某一档这次没取到数也只管显示，不代表它被删了。
     * 新旧两种命名都算「在配置里」，否则升级后每条老线都会诈一次尸。
     * 只看本族的名字 —— 另一族的线由另一张面板管。
     */
    fun orphanLineNames(
        rules: List<AlertRule>,
        family: MaFamily,
        intervals: List<CandleInterval>,
        periods: List<Int>,
    ): List<String> {
        if (intervals.isEmpty() || periods.isEmpty()) return emptyList()
        val configured = intervals
            .flatMap { iv -> periods.flatMap { p -> listOf(note(family, iv, p), legacyNote(family, iv, p)) } }
            .toSet()
        return rules.map { it.name }
            .filter { isFamilyAutoNote(it, family) && it !in configured }
    }

    /** 均线递推：MA = 简单均线，EMA = 指数均线（与图表同一套公式）。 */
    private fun series(family: MaFamily, closes: DoubleArray, period: Int): DoubleArray =
        when (family) {
            MaFamily.MA -> Indicators.sma(closes, period)
            MaFamily.EMA -> Indicators.ema(closes, period)
        }

    /**
     * 自动生成的名字长什么样：`<周期短名><族名><期数>`，期数为正整数。
     *
     * 周期短名 0.9.51 起是中文（`1时`），之前是英文码（`1h`）—— 两种都要认，
     * 所以字符集是「汉字或拉丁字母」。MA 那条多一个 `(?<!E)`：`1时EMA50` 里也含
     * `MA50`，不加这个断言两族的线会互相串认。
     */
    private val AUTO_EMA = Regex("^[0-9]+[\\p{IsHan}a-zA-Z]+EMA[0-9]+$")
    private val AUTO_MA = Regex("^[0-9]+[\\p{IsHan}a-zA-Z]+(?<!E)MA[0-9]+$")

    private fun autoPattern(family: MaFamily): Regex = when (family) {
        MaFamily.MA -> AUTO_MA
        MaFamily.EMA -> AUTO_EMA
    }

    private fun key(interval: CandleInterval, period: Int): String = "${interval.storageKey}|$period"
}
