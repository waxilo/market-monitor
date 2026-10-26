package com.waxilo.marketmonitor.domain.indicator

import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.MarketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * EMA 压力支撑分析的纯逻辑（与桌面端 `lib/emaAnalysis.ts` 同一套口径）。
 *
 * 断言一律落在**值**上：`window` 的根数、`split` 出来的顺序、`merge` 沿用的那几条、
 * `tick` 判成 Add/Align/Remove 的哪一支。只断言「没抛异常」等于没断言。
 */
class EmaAnalysisTest {

    private val m15 = CandleInterval.of(OfficialInterval.M15)
    private val h1 = CandleInterval.of(OfficialInterval.H1)
    private val h4 = CandleInterval.of(OfficialInterval.H4)
    private val d1 = CandleInterval.of(OfficialInterval.D1)

    private fun level(iv: CandleInterval, period: Int, value: Double) = EmaLevel(iv, period, value)

    private fun rule(
        id: Long,
        name: String,
        price: String?,
        enabled: Boolean = true,
    ) = AlertRule(
        id = id,
        market = MarketType.FUTURES,
        symbol = "BTCUSDT",
        name = name,
        condition = AlertCondition.REACH,
        threshold = price?.let { BigDecimal(it) },
        enabled = enabled,
    )

    /* ── 取数根数 ───────────────────────────────────────────── */

    @Test
    fun `window 短档位取下限 500`() {
        assertEquals(500, EmaAnalysis.window(emptyList()))
        // 50 × 4 = 200，低于下限
        assertEquals(500, EmaAnalysis.window(listOf(12, 26, 50)))
    }

    @Test
    fun `window 按最长档位 4 倍预热，并夹在上下限之间`() {
        assertEquals(600, EmaAnalysis.window(listOf(150)))
        // EMA200 要 800 根：旧口径 300 根时种子残留 37%
        assertEquals(800, EmaAnalysis.window(listOf(20, 200)))
        assertEquals(1000, EmaAnalysis.window(listOf(50, 500)))
        assertEquals(1000, EmaAnalysis.window(listOf(Int.MAX_VALUE)))
        // 非法期数不参与：负数 / 0 不该把窗口拉到下限以下
        assertEquals(500, EmaAnalysis.window(listOf(0, -5)))
    }

    /* ── 末值 ──────────────────────────────────────────────── */

    @Test
    fun `lastEma 与 Indicators ema 的末值一致`() {
        val closes = DoubleArray(400) { 100.0 + it * 0.5 }
        val direct = Indicators.ema(closes, 50).last()
        assertEquals(direct, EmaAnalysis.lastEma(closes, 50), 0.0)
    }

    @Test
    fun `根数不足 period 给 NaN`() {
        val closes = DoubleArray(10) { 100.0 }
        assertTrue(EmaAnalysis.lastEma(closes, 50).isNaN())
        assertTrue(EmaAnalysis.lastEma(DoubleArray(0), 12).isNaN())
    }

    /* ── 分档 ──────────────────────────────────────────────── */

    @Test
    fun `split 按现价分成上下两档并算出偏离`() {
        val buckets = EmaAnalysis.split(
            listOf(level(h1, 50, 110.0), level(m15, 12, 90.0)),
            100.0,
        )
        assertEquals(1, buckets.resistance.size)
        assertEquals(1, buckets.support.size)
        assertEquals(110.0, buckets.resistance[0].value, 0.0)
        assertEquals(10.0, buckets.resistance[0].gapPercent, 1e-9)
        assertEquals(-10.0, buckets.support[0].gapPercent, 1e-9)
    }

    @Test
    fun `恰好等于现价的水位两边都不进`() {
        val buckets = EmaAnalysis.split(listOf(level(h1, 50, 100.0)), 100.0)
        assertTrue(buckets.resistance.isEmpty())
        assertTrue(buckets.support.isEmpty())
    }

    @Test
    fun `压力按价位升序、支撑按价位降序，同价先细周期再短档位`() {
        val buckets = EmaAnalysis.split(
            listOf(
                level(h4, 26, 110.0),
                level(h1, 50, 100.0),
                level(h1, 12, 100.0),
                level(m15, 12, 90.0),
                level(h1, 26, 88.0),
            ),
            95.0,
        )
        // 压力：越近越前（100 < 110）
        assertEquals(listOf(100.0, 100.0, 110.0), buckets.resistance.map { it.value })
        // 同价 100：h1 的两档里 period 12 在前
        assertEquals(listOf(12, 50), buckets.resistance.take(2).map { it.period })
        // 支撑：越近越前（90 > 88）
        assertEquals(listOf(90.0, 88.0), buckets.support.map { it.value })
    }

    @Test
    fun `非法现价与非法水位一律不进表`() {
        val levels = listOf(level(h1, 50, 100.0), level(h1, 12, Double.NaN), level(h4, 26, -1.0))
        assertTrue(EmaAnalysis.split(levels, 0.0).resistance.isEmpty())
        assertTrue(EmaAnalysis.split(levels, Double.NaN).support.isEmpty())
        // 现价有效时，只漏掉那两个坏水位
        assertEquals(1, EmaAnalysis.split(levels, 50.0).resistance.size)
    }

    /* ── 静默刷新 ──────────────────────────────────────────── */

    @Test
    fun `整段取数失败的周期沿用上一轮的数，并记进 stale`() {
        val prev = EmaScan(
            levels = listOf(level(h1, 50, 100.0), level(h4, 26, 110.0)),
            fallbackPrice = 95.0,
        )
        val next = EmaScan(
            levels = listOf(level(h1, 50, 101.0)),
            failures = listOf(EmaFailure(h4, "连接超时")),
        )
        val merged = EmaAnalysis.merge(prev, next)
        assertEquals(setOf(101.0, 110.0), merged.levels.map { it.value }.toSet())
        assertEquals(listOf(h4.storageKey), merged.stale.map { it.storageKey })
        // 兜底价这一轮没拿到（NaN），沿用上一轮的
        assertEquals(95.0, merged.fallbackPrice, 0.0)
    }

    @Test
    fun `根数不够但上一轮算得出的，沿用旧值并把它从 missing 里划掉`() {
        val prev = EmaScan(levels = listOf(level(h1, 200, 100.0)))
        val next = EmaScan(missing = listOf(EmaMissing(h1, 200, 120)))
        val merged = EmaAnalysis.merge(prev, next)
        assertEquals(listOf(100.0), merged.levels.map { it.value })
        assertTrue(merged.missing.isEmpty())
    }

    @Test
    fun `配置里删掉的档位不会被上一轮捡回来`() {
        val prev = EmaScan(levels = listOf(level(h1, 50, 100.0)))
        val next = EmaScan(levels = listOf(level(h4, 12, 110.0)))
        val merged = EmaAnalysis.merge(prev, next)
        assertEquals(listOf(110.0), merged.levels.map { it.value })
    }

    @Test
    fun `首次扫描（没有上一轮）原样返回`() {
        val next = EmaScan(levels = listOf(level(h1, 50, 100.0)), fallbackPrice = 99.0)
        assertEquals(next, EmaAnalysis.merge(null, next))
    }

    /* ── 回显与点击 ─────────────────────────────────────────── */

    @Test
    fun `自动备注用周期条上的字拼`() {
        // 周期条 0.9.51 起是中文短名，自动名跟着变：`1h` → `1时`
        assertEquals("1时EMA50", EmaAnalysis.note(h1, 50))
        assertEquals("15分EMA12", EmaAnalysis.note(m15, 12))
        assertEquals("1天EMA200", EmaAnalysis.note(d1, 200))
    }

    @Test
    fun `周期短名的单位与桌面端同源`() {
        assertEquals("1分", CandleInterval.of(OfficialInterval.M1).label)
        assertEquals("4时", h4.label)
        assertEquals("1周", CandleInterval.of(OfficialInterval.W1).label)
        assertEquals("1月", CandleInterval.of(OfficialInterval.MONTH_1M).label)
        // 自定义周期：单位由粗到细先命中 —— 20160 是 2 周，不能落成 14 天
        assertEquals("45分", CandleInterval.custom(45)!!.label)
        assertEquals("3时", CandleInterval.custom(180)!!.label)
        assertEquals("2天", CandleInterval.custom(2_880)!!.label)
        assertEquals("2周", CandleInterval.custom(20_160)!!.label)
    }

    @Test
    fun `按名字认出图上那条线，价位对得上才算已对齐`() {
        val rules = listOf(rule(7, "1时EMA50", "100.5"))
        val ref = EmaAnalysis.lineOf(rules, level(h1, 50, 100.5))
        assertNotNull(ref)
        assertEquals(7L, ref!!.ruleId)
        assertEquals(100.5, ref.price, 0.0)
        assertTrue(ref.aligned)
        // 值变了 = 漂了
        assertTrue(!EmaAnalysis.lineOf(rules, level(h1, 50, 101.0))!!.aligned)
    }

    @Test
    fun `升级前画的线用英文旧名也要认`() {
        // 0.9.51 之前自动名是 `1hEMA50`：认不回来就等于把用户的线全变成孤儿
        val rules = listOf(rule(7, "1hEMA50", "100.0"))
        assertEquals(7L, EmaAnalysis.lineOf(rules, level(h1, 50, 100.0))!!.ruleId)
        assertEquals(EmaTick.Remove(7L), EmaAnalysis.tick(rules, level(h1, 50, 100.0)))
        // 旧名不算孤儿：它对应的那一档还在配置里
        assertEquals(emptyList<String>(), EmaAnalysis.orphanLineNames(rules, listOf(h1), listOf(50)))
    }

    @Test
    fun `名字对不上（用户改过名）就认不回来`() {
        val rules = listOf(rule(7, "压力位", "100.5"))
        assertNull(EmaAnalysis.lineOf(rules, level(h1, 50, 100.5)))
    }

    @Test
    fun `同身份撞上多条只认 id 最小的那条`() {
        val rules = listOf(rule(9, "1时EMA50", "100.0"), rule(3, "1时EMA50", "100.0"))
        assertEquals(3L, EmaAnalysis.lineOf(rules, level(h1, 50, 100.0))!!.ruleId)
    }

    @Test
    fun `没有线的档位判成新建`() {
        assertEquals(EmaTick.Add, EmaAnalysis.tick(emptyList(), level(h1, 50, 100.0)))
    }

    @Test
    fun `已对齐且开着告警的档位再点一下就删`() {
        val rules = listOf(rule(7, "1时EMA50", "100.0", enabled = true))
        assertEquals(EmaTick.Remove(7L), EmaAnalysis.tick(rules, level(h1, 50, 100.0)))
    }

    @Test
    fun `漂了的线判成对齐，不会叠第二条`() {
        val rules = listOf(rule(7, "1时EMA50", "100.0"))
        assertEquals(EmaTick.Align(7L), EmaAnalysis.tick(rules, level(h1, 50, 123.0)))
    }

    @Test
    fun `告警被关掉的对齐线不删，先恢复告警`() {
        val rules = listOf(rule(7, "1时EMA50", "100.0", enabled = false))
        assertEquals(EmaTick.Align(7L), EmaAnalysis.tick(rules, level(h1, 50, 100.0)))
    }

    @Test
    fun `水位非法时什么都不做`() {
        assertEquals(EmaTick.None, EmaAnalysis.tick(emptyList(), level(h1, 50, Double.NaN)))
        assertEquals(EmaTick.None, EmaAnalysis.tick(emptyList(), level(h1, 50, 0.0)))
    }

    @Test
    fun `孤儿线只报自动命名、且不在当前配置里的那些`() {
        val rules = listOf(
            rule(1, "1时EMA50", "100.0"),
            rule(2, "4时EMA999", "110.0"),
            rule(3, "BTCUSDT 达到 90000.00", "90000"),
        )
        val orphans = EmaAnalysis.orphanLineNames(rules, listOf(h1), listOf(50))
        assertEquals(listOf("4时EMA999"), orphans)
    }

    /* ── 刷新后自动对齐（alignments） ────────────────────────── */

    @Test
    fun `漂了的自动线在刷新后要挪到新值`() {
        val rules = listOf(rule(7, "1时EMA50", "100.0"))
        val moves = EmaAnalysis.alignments(rules, listOf(level(h1, 50, 123.5)))
        assertEquals(listOf(7L), moves.map { it.ruleId })
        assertEquals(123.5, moves[0].level.value, 0.0)
        // 落库用的 BigDecimal 就是新值（不对齐 tickSize，见 saveEmaRule）
        assertEquals(0, BigDecimal.valueOf(123.5).compareTo(moves[0].price))
    }

    @Test
    fun `已对齐的自动线不进改动列表（小数位数不同也算对齐）`() {
        // 库里存 "100.00"、算出来 100.0：equals 不等、compareTo 相等 —— 这就是同一个价位
        assertTrue(EmaAnalysis.alignments(listOf(rule(7, "1时EMA50", "100.00")), listOf(level(h1, 50, 100.0))).isEmpty())
    }

    @Test
    fun `用户手建的预警线一根都不动`() {
        val rules = listOf(rule(3, "BTC 十万", "90000"), rule(4, "前高", "98000"))
        assertTrue(EmaAnalysis.alignments(rules, listOf(level(h1, 50, 100.0))).isEmpty())
    }

    @Test
    fun `只挪「达到」类的线，其它条件不自动改`() {
        // ABOVE 是水平比较（price >= target），挪阈值可能当场把「满足」翻过来 ⇒ 凭空弹提醒
        val above = rule(7, "1时EMA50", "100.0").copy(condition = AlertCondition.ABOVE)
        assertTrue(EmaAnalysis.alignments(listOf(above), listOf(level(h1, 50, 200.0))).isEmpty())
        // 没有阈值的那种（区间 / 涨跌幅）本来就没有线可挪
        assertTrue(EmaAnalysis.alignments(listOf(rule(8, "1时EMA50", null)), listOf(level(h1, 50, 200.0))).isEmpty())
    }

    @Test
    fun `英文旧名的线照样跟着走`() {
        val legacy = listOf(rule(7, "1hEMA50", "100.0"))
        assertEquals(listOf(7L), EmaAnalysis.alignments(legacy, listOf(level(h1, 50, 110.0))).map { it.ruleId })
    }

    @Test
    fun `配置里已经删掉的那一档原地不动`() {
        val rules = listOf(rule(8, "1时EMA999", "100.0"))
        assertTrue(EmaAnalysis.alignments(rules, listOf(level(h1, 50, 110.0))).isEmpty())
    }

    @Test
    fun `同名多条（旧逻辑叠出来的）一起挪`() {
        val rules = listOf(rule(9, "1时EMA50", "100.0"), rule(3, "1时EMA50", "101.0"))
        assertEquals(listOf(9L, 3L), EmaAnalysis.alignments(rules, listOf(level(h1, 50, 120.0))).map { it.ruleId })
    }

    @Test
    fun `水位非法时不产生任何改动`() {
        val rules = listOf(rule(7, "1时EMA50", "100.0"))
        for (bad in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY)) {
            assertTrue("水位 $bad 不该产生改动", EmaAnalysis.alignments(rules, listOf(level(h1, 50, bad))).isEmpty())
        }
    }

    @Test
    fun `规则表或水位表为空时无事可做`() {
        assertTrue(EmaAnalysis.alignments(emptyList(), listOf(level(h1, 50, 100.0))).isEmpty())
        assertTrue(EmaAnalysis.alignments(listOf(rule(7, "1时EMA50", "100.0")), emptyList()).isEmpty())
    }

    @Test
    fun `对齐之后 lineOf 立刻认得出「已对齐」`() {
        // 两条路（回显与对齐）必须用同一套判据，否则面板会永远显示「待对齐」
        val rules = listOf(rule(7, "1时EMA50", "100.0"))
        val target = level(h1, 50, 108.25)
        val move = EmaAnalysis.alignments(rules, listOf(target)).single()
        val aligned = rules.map { if (it.id == move.ruleId) it.copy(threshold = move.price) else it }
        assertTrue(EmaAnalysis.lineOf(aligned, target)!!.aligned)
    }
}
