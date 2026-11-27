package com.waxilo.marketmonitor.domain.indicator

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.ChartLine
import com.waxilo.marketmonitor.domain.repository.ChartLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 均线压力支撑分析（MA / EMA 两族）的纯逻辑（与桌面端 `lib/maAnalysis.ts` 同一套口径）。
 *
 * 断言一律落在**值**上：`window` 的根数、`split` 出来的顺序、`merge` 沿用的那几条、
 * `tick` 判成 Add / Remove 的哪一支、`alignments` 要挪哪几条。只断言「没抛异常」等于没断言。
 * 大部分用例走 EMA（历史最久的那一族）；末尾另有几条专测 MA：算法、自动名、两族互不串认。
 *
 * 2026-11-26 起「线」不再是 `alert_rule`，而是带 [MaOrigin] 身份的 `chart_line`，
 * 所以断言对象从「规则名」换成了「身份 + 价位 + 价签 + 挂着的告警」。
 */
class MaAnalysisTest {

    private val m15 = CandleInterval.of(OfficialInterval.M15)
    private val h1 = CandleInterval.of(OfficialInterval.H1)
    private val h4 = CandleInterval.of(OfficialInterval.H4)
    private val d1 = CandleInterval.of(OfficialInterval.D1)

    private fun level(iv: CandleInterval, period: Int, value: Double) = MaLevel(iv, period, value)

    /** 一条均线分析画的线（带身份）。 */
    private fun line(
        id: Long,
        family: MaFamily,
        interval: CandleInterval,
        period: Int,
        price: String?,
        showBadge: Boolean = false,
        alertRuleId: Long? = null,
        note: String? = MaAnalysis.note(family, interval, period),
    ) = ChartLine(
        id = id,
        market = MarketType.FUTURES,
        symbol = "BTCUSDT",
        kind = ChartLineKind.HORIZONTAL,
        price = price?.let { BigDecimal(it) },
        note = note,
        origin = MaOrigin(family, interval, period),
        showBadge = showBadge,
        alertRuleId = alertRuleId,
        createdAt = 0L,
    )

    /** 用户手画的一条水平线（没有身份）。 */
    private fun userLine(id: Long, price: String) = ChartLine(
        id = id,
        market = MarketType.FUTURES,
        symbol = "BTCUSDT",
        kind = ChartLineKind.HORIZONTAL,
        price = BigDecimal(price),
        showBadge = true,
        createdAt = 0L,
    )

    /* ── 取数根数 ───────────────────────────────────────────── */

    @Test
    fun `window 短档位取下限 500`() {
        assertEquals(500, MaAnalysis.window(emptyList()))
        // 50 × 4 = 200，低于下限
        assertEquals(500, MaAnalysis.window(listOf(12, 26, 50)))
    }

    @Test
    fun `window 按最长档位 4 倍预热，并夹在上下限之间`() {
        assertEquals(600, MaAnalysis.window(listOf(150)))
        // EMA200 要 800 根：旧口径 300 根时种子残留 37%
        assertEquals(800, MaAnalysis.window(listOf(20, 200)))
        assertEquals(1000, MaAnalysis.window(listOf(50, 500)))
        assertEquals(1000, MaAnalysis.window(listOf(Int.MAX_VALUE)))
        // 非法期数不参与：负数 / 0 不该把窗口拉到下限以下
        assertEquals(500, MaAnalysis.window(listOf(0, -5)))
    }

    /* ── 末值 ──────────────────────────────────────────────── */

    @Test
    fun `last 与 Indicators ema 的末值一致`() {
        val closes = DoubleArray(400) { 100.0 + it * 0.5 }
        val direct = Indicators.ema(closes, 50).last()
        assertEquals(direct, MaAnalysis.last(MaFamily.EMA, closes, 50), 0.0)
    }

    @Test
    fun `根数不足 period 给 NaN`() {
        val closes = DoubleArray(10) { 100.0 }
        assertTrue(MaAnalysis.last(MaFamily.EMA, closes, 50).isNaN())
        assertTrue(MaAnalysis.last(MaFamily.EMA, DoubleArray(0), 12).isNaN())
    }

    /* ── 分档 ──────────────────────────────────────────────── */

    @Test
    fun `split 按现价分成上下两档并算出偏离`() {
        val buckets = MaAnalysis.split(
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
        val buckets = MaAnalysis.split(listOf(level(h1, 50, 100.0)), 100.0)
        assertTrue(buckets.resistance.isEmpty())
        assertTrue(buckets.support.isEmpty())
    }

    @Test
    fun `压力按价位升序、支撑按价位降序，同价先细周期再短档位`() {
        val buckets = MaAnalysis.split(
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
        assertTrue(MaAnalysis.split(levels, 0.0).resistance.isEmpty())
        assertTrue(MaAnalysis.split(levels, Double.NaN).support.isEmpty())
        // 现价有效时，只漏掉那两个坏水位
        assertEquals(1, MaAnalysis.split(levels, 50.0).resistance.size)
    }

    /* ── 静默刷新 ──────────────────────────────────────────── */

    @Test
    fun `整段取数失败的周期沿用上一轮的数，并记进 stale`() {
        val prev = MaScan(
            levels = listOf(level(h1, 50, 100.0), level(h4, 26, 110.0)),
            fallbackPrice = 95.0,
        )
        val next = MaScan(
            levels = listOf(level(h1, 50, 101.0)),
            failures = listOf(MaFailure(h4, "连接超时")),
        )
        val merged = MaAnalysis.merge(prev, next)
        assertEquals(setOf(101.0, 110.0), merged.levels.map { it.value }.toSet())
        assertEquals(listOf(h4.storageKey), merged.stale.map { it.storageKey })
        // 兜底价这一轮没拿到（NaN），沿用上一轮的
        assertEquals(95.0, merged.fallbackPrice, 0.0)
    }

    @Test
    fun `根数不够但上一轮算得出的，沿用旧值并把它从 missing 里划掉`() {
        val prev = MaScan(levels = listOf(level(h1, 200, 100.0)))
        val next = MaScan(missing = listOf(MaMissing(h1, 200, 120)))
        val merged = MaAnalysis.merge(prev, next)
        assertEquals(listOf(100.0), merged.levels.map { it.value })
        assertTrue(merged.missing.isEmpty())
    }

    @Test
    fun `配置里删掉的档位不会被上一轮捡回来`() {
        val prev = MaScan(levels = listOf(level(h1, 50, 100.0)))
        val next = MaScan(levels = listOf(level(h4, 12, 110.0)))
        val merged = MaAnalysis.merge(prev, next)
        assertEquals(listOf(110.0), merged.levels.map { it.value })
    }

    @Test
    fun `首次扫描（没有上一轮）原样返回`() {
        val next = MaScan(levels = listOf(level(h1, 50, 100.0)), fallbackPrice = 99.0)
        assertEquals(next, MaAnalysis.merge(null, next))
    }

    /* ── 回显与点击 ─────────────────────────────────────────── */

    @Test
    fun `自动备注用周期条上的字拼`() {
        // 周期条 0.9.51 起是中文短名，自动名跟着变：`1h` → `1时`
        assertEquals("1时EMA50", MaAnalysis.note(MaFamily.EMA, h1, 50))
        assertEquals("15分EMA12", MaAnalysis.note(MaFamily.EMA, m15, 12))
        assertEquals("1天EMA200", MaAnalysis.note(MaFamily.EMA, d1, 200))
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
    fun `按身份认出图上那条线，价位对得上才算已对齐`() {
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.5"))
        val ref = MaAnalysis.lineOf(lines, MaFamily.EMA, level(h1, 50, 100.5))
        assertNotNull(ref)
        assertEquals(7L, ref!!.lineId)
        assertEquals(100.5, ref.price, 0.0)
        assertTrue(ref.aligned)
        // 值变了 = 漂了
        assertTrue(!MaAnalysis.lineOf(lines, MaFamily.EMA, level(h1, 50, 101.0))!!.aligned)
    }

    @Test
    fun `改过备注仍然认得出（身份不靠名字）`() {
        // 用户可以把它改成「日线压制」：身份在 origin 里，回显与自动对齐都不该受影响
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.0", note = "日线压制"))
        assertEquals(7L, MaAnalysis.lineOf(lines, MaFamily.EMA, level(h1, 50, 100.0))!!.lineId)
    }

    @Test
    fun `手画的线即使价位正好落在均线上也不认领`() {
        val lines = listOf(userLine(3, "100.0"))
        assertNull(MaAnalysis.lineOf(lines, MaFamily.EMA, level(h1, 50, 100.0)))
        // 再点一下是「新建」，不是「删掉用户那条线」
        assertEquals(MaTick.Add, MaAnalysis.tick(lines, MaFamily.EMA, level(h1, 50, 100.0)))
    }

    @Test
    fun `同身份撞上多条只认 id 最小的那条`() {
        val lines = listOf(
            line(9, MaFamily.EMA, h1, 50, "100.0"),
            line(3, MaFamily.EMA, h1, 50, "100.0"),
        )
        assertEquals(3L, MaAnalysis.lineOf(lines, MaFamily.EMA, level(h1, 50, 100.0))!!.lineId)
    }

    @Test
    fun `新建线默认无价签、不告警，身份与出生备注都写上`() {
        val created = MaAnalysis.newLine(
            market = MarketType.FUTURES,
            symbol = "BTCUSDT",
            family = MaFamily.EMA,
            level = level(h1, 50, 68_900.0),
            createdAt = 1_700_000_000_000L,
        )
        assertEquals(ChartLineKind.HORIZONTAL, created.kind)
        assertEquals(0, created.price!!.compareTo(BigDecimal.valueOf(68_900.0)))
        assertEquals(false, created.showBadge)
        assertNull(created.alertRuleId)
        assertEquals(MaOrigin(MaFamily.EMA, h1, 50), created.origin)
        assertEquals("1时EMA50", created.note)
    }

    @Test
    fun `没有线的档位判成新建`() {
        assertEquals(MaTick.Add, MaAnalysis.tick(emptyList(), MaFamily.EMA, level(h1, 50, 100.0)))
    }

    @Test
    fun `已画过的档位再点一下就删（不看是否对齐、也不看告警开没开）`() {
        val aligned = listOf(line(7, MaFamily.EMA, h1, 50, "100.0"))
        assertEquals(MaTick.Remove(7L, null), MaAnalysis.tick(aligned, MaFamily.EMA, level(h1, 50, 100.0)))
        // 漂了也是删：对齐是自动的（alignments），不该让用户为了「拉回」再点一次
        val drifted = listOf(line(7, MaFamily.EMA, h1, 50, "90.0"))
        assertEquals(MaTick.Remove(7L, null), MaAnalysis.tick(drifted, MaFamily.EMA, level(h1, 50, 123.0)))
        // 挂着告警的线删的时候把规则 id 一起带出来，调用方不必再查
        val alerting = listOf(line(7, MaFamily.EMA, h1, 50, "100.0", alertRuleId = 42L))
        assertEquals(MaTick.Remove(7L, 42L), MaAnalysis.tick(alerting, MaFamily.EMA, level(h1, 50, 100.0)))
    }

    @Test
    fun `水位非法时什么都不做`() {
        assertEquals(MaTick.None, MaAnalysis.tick(emptyList(), MaFamily.EMA, level(h1, 50, Double.NaN)))
        assertEquals(MaTick.None, MaAnalysis.tick(emptyList(), MaFamily.EMA, level(h1, 50, 0.0)))
    }

    @Test
    fun `孤儿线只报本族身份、且不在当前配置里的那些`() {
        val lines = listOf(
            line(1, MaFamily.EMA, h1, 50, "100.0"),
            line(2, MaFamily.EMA, h4, 999, "110.0"),
            userLine(3, "90000"),
        )
        val orphans = MaAnalysis.orphanLines(lines, MaFamily.EMA, listOf(h1), listOf(50))
        assertEquals(listOf(2L), orphans.map { it.id })
    }

    /* ── 刷新后自动对齐（alignments） ────────────────────────── */

    @Test
    fun `漂了的自动线在刷新后要挪到新值`() {
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.0"))
        val moves = MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 123.5)))
        assertEquals(listOf(7L), moves.map { it.lineId })
        assertEquals(123.5, moves[0].level.value, 0.0)
        // 落库用的 BigDecimal 就是新值（不对齐 tickSize，见 newLine）
        assertEquals(0, BigDecimal.valueOf(123.5).compareTo(moves[0].price))
        // 没挂告警的线，改动里不带规则
        assertNull(moves[0].alertRuleId)
    }

    @Test
    fun `挂着告警的线挪的时候把规则 id 一起带出来`() {
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.0", alertRuleId = 42L))
        val move = MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 110.0))).single()
        assertEquals(42L, move.alertRuleId)
    }

    @Test
    fun `已对齐的自动线不进改动列表（小数位数不同也算对齐）`() {
        // 库里存 "100.00"、算出来 100.0：equals 不等、compareTo 相等 —— 这就是同一个价位
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.00"))
        assertTrue(MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 100.0))).isEmpty())
    }

    @Test
    fun `手画的线一根都不动`() {
        val lines = listOf(userLine(3, "90000"), userLine(4, "98000"))
        assertTrue(MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 100.0))).isEmpty())
    }

    @Test
    fun `配置里已经删掉的那一档原地不动`() {
        val lines = listOf(line(8, MaFamily.EMA, h1, 999, "100.0"))
        assertTrue(MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 110.0))).isEmpty())
    }

    @Test
    fun `同一档撞上多条一起挪`() {
        val lines = listOf(
            line(9, MaFamily.EMA, h1, 50, "100.0"),
            line(3, MaFamily.EMA, h1, 50, "101.0"),
        )
        assertEquals(
            listOf(9L, 3L),
            MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, 120.0))).map { it.lineId },
        )
    }

    @Test
    fun `水位非法时不产生任何改动`() {
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.0"))
        for (bad in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY)) {
            assertTrue(
                "水位 $bad 不该产生改动",
                MaAnalysis.alignments(lines, MaFamily.EMA, listOf(level(h1, 50, bad))).isEmpty(),
            )
        }
    }

    @Test
    fun `画线表或水位表为空时无事可做`() {
        assertTrue(MaAnalysis.alignments(emptyList(), MaFamily.EMA, listOf(level(h1, 50, 100.0))).isEmpty())
        assertTrue(MaAnalysis.alignments(listOf(line(7, MaFamily.EMA, h1, 50, "100.0")), MaFamily.EMA, emptyList()).isEmpty())
    }

    @Test
    fun `对齐之后 lineOf 立刻认得出「已对齐」`() {
        // 两条路（回显与对齐）必须用同一套判据，否则面板会永远显示「漂了」
        val lines = listOf(line(7, MaFamily.EMA, h1, 50, "100.0"))
        val target = level(h1, 50, 108.25)
        val move = MaAnalysis.alignments(lines, MaFamily.EMA, listOf(target)).single()
        val aligned = lines.map { if (it.id == move.lineId) it.copy(price = move.price) else it }
        assertTrue(MaAnalysis.lineOf(aligned, MaFamily.EMA, target)!!.aligned)
    }

    /* ── 升级迁移：从老自动名反解身份 ────────────────────────── */

    @Test
    fun `parseAutoNote 认中文短名与英文旧名`() {
        assertEquals(MaOrigin(MaFamily.EMA, h1, 50), MaAnalysis.parseAutoNote("1时EMA50"))
        assertEquals(MaOrigin(MaFamily.MA, h1, 20), MaAnalysis.parseAutoNote("1时MA20"))
        // 0.9.51 之前画出来的线叫 `1hEMA50`
        assertEquals(MaOrigin(MaFamily.EMA, h1, 50), MaAnalysis.parseAutoNote("1hEMA50"))
        assertEquals(MaOrigin(MaFamily.MA, m15, 12), MaAnalysis.parseAutoNote("15mMA12"))
        assertEquals(MaOrigin(MaFamily.EMA, d1, 200), MaAnalysis.parseAutoNote("1天EMA200"))
        // `mo` 那段不能被族名吞掉
        assertEquals(MaOrigin(MaFamily.MA, CandleInterval.of(OfficialInterval.MONTH_1M), 20), MaAnalysis.parseAutoNote("1moMA20"))
    }

    @Test
    fun `parseAutoNote 对用户自己的名字给 null（迁移时原样留着）`() {
        assertNull(MaAnalysis.parseAutoNote(null))
        assertNull(MaAnalysis.parseAutoNote(""))
        assertNull(MaAnalysis.parseAutoNote("压力位"))
        assertNull(MaAnalysis.parseAutoNote("BTCUSDT 达到 90000.00"))
        assertNull(MaAnalysis.parseAutoNote("1时MA"))
        assertNull(MaAnalysis.parseAutoNote("1时MA0"))
    }

    @Test
    fun `intervalOfLabel 与周期短名互为逆运算`() {
        for (iv in listOf(m15, h1, h4, d1, CandleInterval.of(OfficialInterval.W1))) {
            assertEquals(iv, MaAnalysis.intervalOfLabel(iv.label))
            assertEquals(iv, MaAnalysis.intervalOfLabel(iv.legacyLabel))
        }
        assertNull(MaAnalysis.intervalOfLabel("0时"))
        assertNull(MaAnalysis.intervalOfLabel("1光年"))
    }

    // ---- 缩略图曲线（MaAnalysis.curve） ----

    private fun wobble(n: Int) = DoubleArray(n) { 100.0 + kotlin.math.sin(it / 7.0) * 5 + it * 0.05 }

    @Test
    fun `曲线末值与 last 一致且跳过预热段`() {
        // 60 根算 EMA50：前 49 根是预热段（NaN），窗口起点必须挪到第一个有效值
        val closes = wobble(60)
        val curve = MaAnalysis.curve(MaFamily.EMA, m15, closes, 50)!!
        assertEquals(11, curve.values.size)
        assertTrue("曲线里不该有 NaN", curve.values.all { it.isFinite() })
        assertEquals("closes / values 等长", curve.closes.size, curve.values.size)
        assertEquals(MaAnalysis.last(MaFamily.EMA, closes, 50), curve.values.last(), 1e-9)
    }

    @Test
    fun `根数不够算这一档时没有曲线`() {
        assertNull(MaAnalysis.curve(MaFamily.EMA, m15, wobble(40), 50))
        assertNull(MaAnalysis.curve(MaFamily.EMA, m15, DoubleArray(0), 20))
        assertNull(MaAnalysis.curve(MaFamily.EMA, m15, wobble(100), 0))
    }

    @Test
    fun `长历史只保留尾段的 PREVIEW_BARS 根`() {
        val closes = wobble(600)
        val curve = MaAnalysis.curve(MaFamily.EMA, m15, closes, 26)!!
        assertEquals(MaAnalysis.PREVIEW_BARS, curve.values.size)
        assertEquals(closes.last(), curve.closes.last(), 1e-9)
        assertEquals(MaAnalysis.last(MaFamily.EMA, closes, 26), curve.values.last(), 1e-9)
    }

    // ---- MA 一族 ----

    @Test
    fun `MA 用简单均线，末值与 Indicators sma 一致`() {
        val closes = wobble(120)
        assertEquals(Indicators.sma(closes, 20).last(), MaAnalysis.last(MaFamily.MA, closes, 20), 0.0)
        // 与 EMA 不是同一个数（否则说明 family 没被用上）
        assertTrue(MaAnalysis.last(MaFamily.MA, closes, 20) != MaAnalysis.last(MaFamily.EMA, closes, 20))
    }

    @Test
    fun `MA 曲线起点就是第一个有效值（SMA 没有预热段）`() {
        val closes = wobble(100)
        val curve = MaAnalysis.curve(MaFamily.MA, m15, closes, 20)!!
        // 默认开窗 120 > 历史 100，整段都要画；第 20 根起才有效，所以正好 81 个点
        assertEquals(81, curve.values.size)
        assertTrue(curve.values.all { it.isFinite() })
        assertEquals(MaAnalysis.last(MaFamily.MA, closes, 20), curve.values.last(), 1e-9)
    }

    @Test
    fun `MA 自动名带族名，与 EMA 同档位互不串认`() {
        assertEquals("1时MA20", MaAnalysis.note(MaFamily.MA, h1, 20))
        assertEquals("1hMA20", MaAnalysis.legacyNote(MaFamily.MA, h1, 20))
        // `1时MA20` 里也含 `MA20`，但族名判据要排除前面紧挨着 E 的 `1时EMA50`
        assertTrue(MaAnalysis.isFamilyAutoNote("1时MA20", MaFamily.MA))
        assertTrue(!MaAnalysis.isFamilyAutoNote("1时EMA50", MaFamily.MA))
        assertTrue(MaAnalysis.isFamilyAutoNote("1时EMA50", MaFamily.EMA))
        assertTrue(!MaAnalysis.isFamilyAutoNote("1时MA20", MaFamily.EMA))
        assertTrue(MaAnalysis.isAutoNote("1时MA20"))
        assertTrue(MaAnalysis.isAutoNote("1时EMA50"))
        assertTrue(!MaAnalysis.isAutoNote("BTC 十万"))
    }

    @Test
    fun `MA 只看自己那一族的线：回显、对齐、孤儿都不串族`() {
        val maLine = line(1, MaFamily.MA, h1, 20, "100.0")
        val emaLine = line(2, MaFamily.EMA, h1, 20, "100.0")
        val target = level(h1, 20, 110.0)

        // 回显：MA 档位认不出 EMA 的线
        assertEquals(1L, MaAnalysis.lineOf(listOf(maLine, emaLine), MaFamily.MA, target)!!.lineId)
        assertEquals(2L, MaAnalysis.lineOf(listOf(maLine, emaLine), MaFamily.EMA, target)!!.lineId)

        // 对齐：各拉各的
        assertEquals(
            listOf(1L),
            MaAnalysis.alignments(listOf(maLine, emaLine), MaFamily.MA, listOf(target)).map { it.lineId },
        )
        assertEquals(
            listOf(2L),
            MaAnalysis.alignments(listOf(maLine, emaLine), MaFamily.EMA, listOf(target)).map { it.lineId },
        )

        // 孤儿：MA 面板不把 EMA 的线报成孤儿
        assertEquals(emptyList<Long>(), MaAnalysis.orphanLines(listOf(maLine, emaLine), MaFamily.MA, listOf(h1), listOf(20)).map { it.id })
        assertEquals(
            listOf(3L),
            MaAnalysis.orphanLines(
                listOf(maLine, emaLine, line(3, MaFamily.MA, h1, 999, "1.0")),
                MaFamily.MA,
                listOf(h1),
                listOf(20),
            ).map { it.id },
        )
    }

    @Test
    fun `MA 面板点一行同样只有画与删两态`() {
        val target = level(h1, 20, 100.0)
        assertEquals(MaTick.Add, MaAnalysis.tick(emptyList(), MaFamily.MA, target))
        assertEquals(
            MaTick.Remove(7L, null),
            MaAnalysis.tick(listOf(line(7, MaFamily.MA, h1, 20, "100.0")), MaFamily.MA, target),
        )
        // 只有 EMA 的线、没有 MA 的线时，MA 面板应判成「新建」
        assertEquals(
            MaTick.Add,
            MaAnalysis.tick(listOf(line(8, MaFamily.EMA, h1, 20, "100.0")), MaFamily.MA, target),
        )
    }
}
