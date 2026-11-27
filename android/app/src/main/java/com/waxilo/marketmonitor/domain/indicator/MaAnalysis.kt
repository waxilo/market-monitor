package com.waxilo.marketmonitor.domain.indicator

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
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
 * 落库形态与桌面端对齐（2026-11-26 改）：面板画的线落在 `chart_line` 里，带
 * [MaOrigin] 身份（族 / 周期 / 档位），**默认无价签、不告警**；要告警才给这条线挂一条
 * `alert_rule`（见 [ChartLine.alertRuleId]）。早先这条线本身就是一条告警规则
 * （`alert_rule` + 自动名当身份），于是「画一条参考线」必然等于「开一条预警」，
 * 而且用户改个名字就再也认不回来。
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

/**
 * 均线分析代画的线的身份：(族, 周期, 档位)。
 *
 * 桌面端 `PriceLine.origin` 的同构物。它是这条线**唯一**的认领键 ——
 * 备注可以被用户改写（「1时MA20」改成「日线压制」），改完仍认得出归属；
 * 而自动名只剩「出生时写在线上的一句话」这一个用途。
 */
data class MaOrigin(
    val family: MaFamily,
    val interval: CandleInterval,
    val period: Int,
) {
    /** 同一档判据（周期按 [CandleInterval.storageKey] 比，避免两个等值实例不 equals）。 */
    fun matches(family: MaFamily, interval: CandleInterval, period: Int): Boolean =
        this.family == family && this.interval.storageKey == interval.storageKey && this.period == period
}

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

/** 图上与某一档均线绑定的那条线（面板行据此回显）。 */
data class MaLineRef(
    /** 画线表里的主键 —— 删它 / 改它都要用。 */
    val lineId: Long,
    /**
     * 线上的价位 = 它**现在**在图上钉在哪儿。
     *
     * 它与这一档的当前均线值不等，只说明「刚漂过」：用户刚把线拖走，或者这一轮没取到数。
     * 正常情况下每轮刷新都会把线拉回当前值（见 [MaAnalysis.alignments]）。
     */
    val price: Double,
    /** 线上挂着的告警规则（null = 不告警）。 */
    val alertRuleId: Long?,
    /** 线上那句话（出生时是自动名，用户可能改过）。 */
    val note: String?,
    /** 线上价位与这一档的当前均线值是否一致（不一致 = 漂了，下一轮刷新会拉回）。 */
    val aligned: Boolean,
) {
    /** 这条线挂着告警规则吗（等价于 [alertRuleId] 非空）。 */
    val alert: Boolean get() = alertRuleId != null
}

/**
 * 一条**要挪的系统划线**：把 [lineId] 的价位改成 [price]；若它挂着告警 [alertRuleId]，
 * 那条规则的阈值要一起改，否则线与告警会指向两个价。
 *
 * 只描述「该改成什么」，不碰数据库 —— 落库要 suspend，纯函数层留在这里是为了能单测。
 */
data class MaAlign(
    val lineId: Long,
    /** 线上挂的告警规则（null = 不告警）。 */
    val alertRuleId: Long?,
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

/** 点了面板里一行之后对线该做什么。 */
sealed interface MaTick {
    /** 这一档还没有线：新建一条（**不告警、不挂价签**）。 */
    data object Add : MaTick

    /**
     * 已经有线：删掉它（同一个条目再点一下即取消）。
     *
     * 只带 [lineId] 不够：线上可能挂着一条告警规则，删线必须连它一起收掉，
     * 否则预警列表里会留下一条没有线的规则。一并带出来，调用方不必再查一遍。
     */
    data class Remove(val lineId: Long, val alertRuleId: Long?) : MaTick

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
     * 在画线表里找这一档均线对应的线，找不到给 null。
     *
     * **只认身份**（[ChartLine.origin]）：族 + 周期（按 [CandleInterval.storageKey] 比）+ 档位。
     * 备注不参与认人 —— 用户把「1时MA20」改成「日线压制」之后，这条线仍然是 MA20 那一条，
     * 面板照旧回显、刷新照旧拉齐（早先靠名字认人，一改名就丢绑定）。
     *
     * 手画的线（`origin == null`）即使价位正好落在均线上也不认领：那是用户自己的判断，
     * 面板不该把它报成「已画」。同身份撞上多条（旧逻辑叠过）时只认 **id 最小**的那条：
     * 回显回答的是「这一档画过没有」，不是「画了几条」。
     */
    fun lineOf(lines: List<ChartLine>, family: MaFamily, level: MaLevel): MaLineRef? {
        val line = lines.asSequence()
            .filter { it.origin?.matches(family, level.interval, level.period) == true }
            .minByOrNull { it.id }
            ?: return null
        val price = line.price?.toDouble() ?: return null
        if (!price.isFinite() || price <= 0) return null
        return MaLineRef(
            lineId = line.id,
            price = price,
            alertRuleId = line.alertRuleId,
            note = line.note,
            aligned = price == level.value,
        )
    }

    /**
     * 面板里点一行 = **一次切换**：
     *
     *  - 这一档还没有线 → 画一条（默认无价签、不告警，见 [newLine]）；
     *  - 已经画过 → 删掉（连它挂的告警规则一起，见 [MaTick.Remove]）。
     *
     * **没有「对齐到当前值」这一档**（2026-11-26 用户要求）：对齐是自动的，每轮取数回来
     * 都会把线拉到新值（见 [alignments]），不该让用户为了「拉回」再点一次；
     * 而那种一下会把用户特意关掉的告警重新打开 —— 正是要修的那个 bug。
     */
    fun tick(lines: List<ChartLine>, family: MaFamily, level: MaLevel): MaTick {
        if (!level.value.isFinite() || level.value <= 0) return MaTick.None
        val hit = lineOf(lines, family, level) ?: return MaTick.Add
        return MaTick.Remove(hit.lineId, hit.alertRuleId)
    }

    /**
     * 面板落一条新线：水平线、带身份、**默认不挂价签、不告警**。
     *
     * 备注写上自动名（`1时MA20`）只是「出生时的一句话」：图上那条线得让人认得出是哪一档，
     * 而身份另有字段兜着，用户改掉备注也不影响归属。
     */
    fun newLine(
        market: MarketType,
        symbol: String,
        family: MaFamily,
        level: MaLevel,
        createdAt: Long,
    ): ChartLine = ChartLine(
        market = market,
        symbol = symbol,
        kind = ChartLineKind.HORIZONTAL,
        price = BigDecimal.valueOf(level.value),
        note = note(family, level.interval, level.period),
        origin = MaOrigin(family, level.interval, level.period),
        showBadge = false,
        alertRuleId = null,
        createdAt = createdAt,
    )

    /**
     * 一轮扫描结束后**该落到线上的改动**：把图上这一族的系统划线拉到各自的新值。
     *
     * 与桌面端 `alignLevelLines` 同一条口径 —— 「线跟着均线走」这句话的唯一执行点。
     * 均线每分钟都在动，线是拿来看的一道墙，墙必须长在均线 **现在**的位置上；
     * 停在画它的那一刻只会让面板说「已画」、图上那条线却离得老远。
     *
     * 三条边界：
     *
     *  1. **只动本族身份线**：手画的线（`origin == null`）价位是用户自己的判断，
     *     另一族面板画的线也不归这一族管 —— 两边各挪各的，不会互相抢。
     *  2. **只改价位**（以及它挂着的告警规则的阈值）。价签开关、备注、告警在不在，
     *     全是用户设过的，这一下只是「同一条线挪到新价位」，不是重新生成。
     *  3. **不动 `AlertState`**：自动对齐每 60 秒就可能执行一次，每次都清 `fired`
     *     会让「只响一次」的规则永远重新武装；而 REACH 的边沿判定读的是价格本身
     *     （`lastPrice`），阈值被挪走不会误判，不清更安全。
     *
     * 判据是**精确相等**（`compareTo` 而不是 `equals`：库里存 `68000.0`、算出来 `68000`
     * 是同一个价）：面板与这里用的是同一个 `last` 算出来的同一个 double，不设容差 ——
     * 设了就会出现「差一点点也算对齐」，于是面板说「已画」、图上的线却明显偏着。
     */
    fun alignments(lines: List<ChartLine>, family: MaFamily, levels: List<MaLevel>): List<MaAlign> {
        if (lines.isEmpty() || levels.isEmpty()) return emptyList()
        val targets = HashMap<String, MaLevel>(levels.size * 2)
        for (level in levels) {
            if (!level.value.isFinite() || level.value <= 0) continue
            targets[key(level.interval, level.period)] = level
        }
        if (targets.isEmpty()) return emptyList()

        val out = ArrayList<MaAlign>(lines.size)
        for (line in lines) {
            val origin = line.origin ?: continue
            if (origin.family != family) continue
            val level = targets[key(origin.interval, origin.period)] ?: continue
            val price = line.price ?: continue
            if (BigDecimal.valueOf(level.value).compareTo(price) == 0) continue
            out += MaAlign(line.id, line.alertRuleId, level)
        }
        return out
    }

    /**
     * 图上有、但**当前配置里已经没有对应行**的**本族**系统划线（改过目录 / 周期条）。
     *
     * 行内回显按 (周期, 档位) 配对，这类线天然配不上任何一行 —— 不点一句出来，用户就只能
     * 在图上看见一条带 `1时MA20` 备注的线、却在面板里怎么也找不到它。
     * 判据用**配置**而不是扫描结果：某一档这次没取到数也只管显示，不代表它被删了。
     * 只看本族身份 —— 另一族的线由另一张面板管。
     */
    fun orphanLines(
        lines: List<ChartLine>,
        family: MaFamily,
        intervals: List<CandleInterval>,
        periods: List<Int>,
    ): List<ChartLine> {
        if (intervals.isEmpty() || periods.isEmpty()) return emptyList()
        val configured = HashSet<String>(intervals.size * periods.size)
        for (iv in intervals) for (p in periods) configured.add(key(iv, p))
        return lines.filter { line ->
            val origin = line.origin ?: return@filter false
            origin.family == family && key(origin.interval, origin.period) !in configured
        }
    }

    /**
     * 从一条线的名字反解身份（`1时EMA50` / 旧版 `1hMA20`）。**只为升级迁移用** —— 迁移之后
     * 身份是字段，不再靠名字；解不出来的名字（用户改过的）留给原规则，不动。
     */
    fun parseAutoNote(name: String?): MaOrigin? {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = AUTO_LINE.find(text) ?: return null
        val interval = intervalOfLabel(match.groupValues[1]) ?: return null
        val family = if (match.groupValues[2] == MaFamily.EMA.name) MaFamily.EMA else MaFamily.MA
        val period = match.groupValues[3].toIntOrNull() ?: return null
        if (period <= 0) return null
        return MaOrigin(family, interval, period)
    }

    /**
     * 周期短名 → 周期：`15分` / `1时` / `1天` / `1周` / `1月`，以及旧版英文码
     * （`15m` / `1h` / `1d` / `1w` / `1mo` / `1M`）。认不出给 null。
     *
     * 与 [CandleInterval.label] / [CandleInterval.legacyLabel] 互为逆运算 ——
     * 迁移时要把老自动名还原成一档，靠的就是这两张表对得上。
     */
    fun intervalOfLabel(text: String): CandleInterval? {
        val match = LABEL_FORM.find(text.trim()) ?: return null
        val count = match.groupValues[1].toLongOrNull() ?: return null
        if (count <= 0) return null
        val minutes = when (match.groupValues[2]) {
            "分", "m" -> count
            "时", "h" -> count * 60
            "天", "d" -> count * 1_440
            "周", "w" -> count * 10_080
            "月", "mo", "M" -> count * 43_200
            else -> return null
        }
        if (minutes !in CandleInterval.MINUTES_RANGE) return null
        // 官方周期要落回同一支枚举（`of`），否则它带着 official = null 与配置里的实例不等值
        val official = OfficialInterval.entries.firstOrNull { it.minutes == minutes } ?: return CandleInterval.custom(minutes)
        return CandleInterval.of(official)
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

    /**
     * 把一条自动名**拆成三段**（周期短名 / 族 / 期数），[parseAutoNote] 用。
     *
     * 周期那一段用非贪婪：`1moMA20` 要先试 `1`（后面接不上族名）再退到 `1mo`，
     * 贪婪的话 `1moMA` 会被整段吞进周期里。
     */
    private val AUTO_LINE = Regex("^([0-9]+[\\p{IsHan}a-zA-Z]+?)(EMA|MA)([0-9]+)$")

    /** 周期短名的形状：`<数字><单位>`，单位见 [intervalOfLabel]。 */
    private val LABEL_FORM = Regex("^([0-9]+)(\\p{IsHan}+|[a-zA-Z]+)$")

    private fun autoPattern(family: MaFamily): Regex = when (family) {
        MaFamily.MA -> AUTO_MA
        MaFamily.EMA -> AUTO_EMA
    }

    private fun key(interval: CandleInterval, period: Int): String = "${interval.storageKey}|$period"
}
