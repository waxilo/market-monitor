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
    fun `window 默认与图上一段历史同长`() {
        assertEquals(300, EmaAnalysis.window(emptyList()))
        assertEquals(300, EmaAnalysis.window(listOf(12, 26, 50)))
        // EMA200 只要 220 根就够，但下限 300 更高 —— 取 300
        assertEquals(300, EmaAnalysis.window(listOf(200)))
    }

    @Test
    fun `window 长档位往上加 20 根余量，并夹在上下限之间`() {
        assertEquals(520, EmaAnalysis.window(listOf(50, 500)))
        assertEquals(1000, EmaAnalysis.window(listOf(2000)))
        // 非法期数不参与：负数 / 0 不该把窗口拉到下限以下
        assertEquals(300, EmaAnalysis.window(listOf(0, -5)))
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
}
