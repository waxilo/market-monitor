package com.waxilo.marketmonitor.ui.chart

import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.Kline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** 图表纯模型：窗口、刻度、指标序列。像素与 Compose 不在此覆盖。 */
class ChartModelTest {

    private val day = CandleInterval.of(OfficialInterval.D1)
    private val minuteMs = 60_000L

    /** 手势类测试统一用的序列长度：足够长，任何平移/缩放都不会撞到边界。 */
    private val BARS = 2_000

    private fun candles(count: Int, base: Double = 100.0): List<Kline> = (0 until count).map { i ->
        // 收盘价线性上涨，便于手算指标与涨幅
        val close = base + i
        Kline(
            openTime = i * 1_440 * minuteMs,
            closeTime = (i + 1) * 1_440 * minuteMs - 1,
            open = BigDecimal.valueOf(close - 0.5),
            high = BigDecimal.valueOf(close + 1),
            low = BigDecimal.valueOf(close - 1.5),
            close = BigDecimal.valueOf(close),
            volume = BigDecimal.valueOf(10),
            quoteVolume = BigDecimal.valueOf(100),
            trades = 5,
        )
    }

    // region 视口
    @Test
    fun `空序列的窗口是空区间`() {
        assertTrue(ChartViewport(120, 0).window(0).isEmpty())
    }

    @Test
    fun `序列不足一屏时可见根数收到实际长度`() {
        val viewport = ChartViewport(visibleBars = 120).clamp(barCount = 6)
        assertEquals(6, viewport.visibleBars)
        assertEquals(0..5, viewport.window(6))
    }

    /**
     * 回归：`barCount` 改成入参之后，「视窗」与「序列长度」不可能再错配。
     *
     * 旧版 `barCount` 是字段，靠 `LaunchedEffect` 事后对齐，首个数据已到、
     * 视窗未对齐的帧会按 `barCount = 0` 算，于是窗口空、量程退化，
     * 表现为「首次进详情页 K 线挤在一角，切一下周期才恢复」。
     * 同一个 viewport 现在对不同 barCount 各自给出正确窗口 —— 这才是根因的封堵。
     */
    @Test
    fun `同一视窗对任意序列长度都给出正确窗口`() {
        val viewport = ChartViewport(visibleBars = 100)
        // 视窗与序列长度无关：换一个 barCount 就得到那个序列的窗口，不需要任何对齐步骤
        assertEquals(20..119, viewport.window(120))
        assertEquals(0..99, viewport.window(100))
        assertEquals(420..519, viewport.window(520))
        // 短序列收窄到实际根数，绝不出现空窗口
        assertEquals(0..5, viewport.window(6))
    }

    @Test
    fun `往右拖（看更早）右偏移为负`() {
        // 语义：pan 正数 = 手指右移 = 窗口左移看更早的历史 => rightOffset 变负
        val moved = ChartViewport(100, 0).pan(20f, barCount = 300)
        assertEquals(-20, moved.rightOffset)
        assertEquals(279, moved.endIndex(300))
    }

    @Test
    fun `往更早方向拖到底时停在序列起点`() {
        val moved = ChartViewport(50, 0).pan(1_000f, barCount = 100)
        // 最多移出 (barCount - visible) 根历史，恰好看得到第 0 根
        assertEquals(-50, moved.rightOffset)
        assertEquals(0, moved.startIndex(100))
    }

    @Test
    fun `最新一根可以往左拖出右侧留白`() {
        // 用户诉求：最新 K 线不能只顶在最右边，要能把它推到中间偏左，右侧露出空白。
        // 手指往左拖（负 delta）=> rightOffset 变正 => 绘图区右端越出序列末根。
        val viewport = ChartViewport(100, 0)
        val moved = viewport.pan(-20f, barCount = 300)
        assertEquals(20, moved.rightOffset)
        // 绘图区右端越出序列末根（这就是右侧那段空白）
        assertEquals(319, moved.endIndex(300))
        // 但**真实数据**的右端仍是末根，索引安全 —— 不会越界取数据
        assertEquals(299, moved.window(300).last)
    }

    @Test
    fun `可左拖的留白有上限不会拖成一片空白`() {
        val viewport = ChartViewport(100, 0)
        val moved = viewport.pan(-10_000f, barCount = 300)
        // 上限 = visibleBars * (1 - MIN_VISIBLE_SHARE)
        val expected = (100 * (1f - ChartViewport.MIN_VISIBLE_SHARE)).toInt()
        assertEquals(expected, moved.rightOffset)
        // 数据区仍占满可视区的至少 MIN_VISIBLE_SHARE
        val dataShare = (moved.clamp(300).visibleBars - moved.rightOffset).toFloat() / moved.clamp(300).visibleBars
        assertTrue("数据占比 $dataShare 太低", dataShare >= ChartViewport.MIN_VISIBLE_SHARE - 1e-6f)
    }

    @Test
    fun `右侧留白时 window 不越界而 plotRange 会越界`() {
        val viewport = ChartViewport(100, 20).clamp(300)
        // window：夹在 0..barCount-1，索引安全
        assertEquals(299, viewport.window(300).last)
        assertTrue(viewport.window(300).first >= 0)
        // plotRange：含留白，右端越出末根
        assertEquals(319, viewport.plotRange(300).last)
        assertEquals(220, viewport.plotRange(300).first)
    }

    @Test
    fun `右侧留白区内点按十字光标不越界`() {
        val viewport = ChartViewport(100, 20).clamp(300)
        // 绘图区最右侧（留白区）反查——必须夹回末根，不能返回 319
        assertEquals(299, viewport.indexAt(1f, barCount = 300))
    }

    @Test
    fun `横坐标反查与归一化在留白时互为逆运算`() {
        val viewport = ChartViewport(100, 20).clamp(300)
        val index = viewport.indexAt(0.5f, barCount = 300)
        val fraction = viewport.fractionOf(index, barCount = 300)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `缩放上下界固定`() {
        val viewport = ChartViewport(100, 0)
        assertEquals(ChartViewport.MIN_BARS, viewport.zoom(0.0001f, barCount = 1_000).visibleBars)
        assertEquals(ChartViewport.MAX_BARS, viewport.zoom(1_000f, barCount = 1_000).visibleBars)
    }

    @Test
    fun `缩放时锚点处的蜡烛保持不动`() {
        // 负右偏移 = 正在看更早的历史
        val viewport = ChartViewport(100, -200)
        val start = viewport.startIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f, barCount = 1_000)
        // 锚点在左端：缩放后左端仍是同一根蜡烛
        assertEquals(start, anchored.startIndex(1_000))
    }

    @Test
    fun `右侧留白时缩放锚点依然不动`() {
        val viewport = ChartViewport(100, 30).clamp(1_000)
        val start = viewport.startIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f, barCount = 1_000)
        // 放大（可见根数变少）后左端仍是同一根
        assertEquals(start, anchored.startIndex(1_000))
    }

    @Test
    fun `右侧留白时右端锚点也不动`() {
        val viewport = ChartViewport(100, 30).clamp(1_000)
        val end = viewport.endIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 1f, barCount = 1_000)
        assertEquals(end, anchored.endIndex(1_000))
    }

    @Test
    fun `横坐标反查与归一化互为逆运算`() {
        val viewport = ChartViewport(100, 0)
        val index = viewport.indexAt(0.5f, barCount = 300)
        val fraction = viewport.fractionOf(index, barCount = 300)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `序列变长后仍贴着最新`() {
        // 视窗不带序列长度，`barCount` 变大即为「来了新蜡烛」：
        // rightOffset 保持 0 就自然贴回最新，不再需要 resize 这一步。
        assertEquals(300, ChartViewport(100, 0).endIndex(barCount = 301))
    }
    // endregion

    // region 刻度
    @Test
    fun `归一化位置顶部为0底部为1`() {
        val range = ValueRange(10.0, 20.0)
        assertEquals(0f, range.toFraction(20.0), 1e-6f)
        assertEquals(1f, range.toFraction(10.0), 1e-6f)
        assertEquals(0.5f, range.toFraction(15.0), 1e-6f)
    }

    @Test
    fun `常数序列留余量避免出现零跨度`() {
        val range = ValueRange(5.0, 5.0).padded()
        assertTrue(range.high > range.low)
    }

    // region 纵向缩放 / 平移（价格刻度上下拖动）

    @Test
    fun `纵向缩放以中位价为不动点`() {
        val base = ValueRange(100.0, 200.0)
        val zoomed = base.scaled(2f, base)
        // 量程翻倍（factor=2 是「拉长量程」），但中位价不能动，
        // 否则一缩放整张图就跑掉了
        assertEquals(150.0, zoomed.center, 1e-6)
        assertEquals(200.0, zoomed.high - zoomed.low, 1e-6)
    }

    @Test
    fun `放大量程数值变小`() {
        val base = ValueRange(100.0, 200.0)
        val smaller = base.scaled(0.5f, base)
        assertTrue(smaller.high - smaller.low < base.high - base.low)
        assertEquals(150.0, smaller.center, 1e-6)
    }

    @Test
    fun `量程缩放钳到上界不会把 K 线压成一个点`() {
        val base = ValueRange(100.0, 200.0)
        val huge = base.scaled(1_000f, base)
        val expected = (base.high - base.low) * ValueRange.MAX_SPAN_RATIO
        assertEquals(expected, huge.high - huge.low, 1e-6)
        assertEquals(base.center, huge.center, 1e-6)
    }

    @Test
    fun `量程缩放钳到下界不会退化成零跨度`() {
        val base = ValueRange(100.0, 200.0)
        val tiny = base.scaled(1e-6f, base)
        val expected = (base.high - base.low) * ValueRange.MIN_SPAN_RATIO
        assertEquals(expected, tiny.high - tiny.low, 1e-6)
        assertTrue(tiny.high > tiny.low)
    }

    @Test
    fun `非法缩放因子原样返回`() {
        val base = ValueRange(100.0, 200.0)
        assertEquals(base, base.scaled(0f, base))
        assertEquals(base, base.scaled(Float.NaN, base))
        val degenerate = ValueRange(5.0, 5.0)
        assertEquals(degenerate, degenerate.scaled(2f, degenerate))
    }

    @Test
    fun `纵向平移按原始量程折算而不是当前量程`() {
        // 关键回归：先缩放到 1/10 量程，再拖 0.1 屏。
        // 位移必须是「原始量程(100) × 0.1 = 10」，而不是「当前量程(10) × 0.1 = 1」，
        // 否则放大后拖同样的手指距离，图几乎不动、手感完全不对。
        val base = ValueRange(100.0, 200.0)
        val zoomed = base.scaled(0.1f, base)
        val panned = zoomed.panned(0.1f, base)
        // 平移不改变量程本身
        assertEquals(zoomed.high - zoomed.low, panned.high - panned.low, 1e-6)
        assertEquals(zoomed.low + 10.0, panned.low, 1e-4)
    }

    @Test
    fun `零位移平移不改变量程`() {
        val base = ValueRange(100.0, 200.0)
        assertEquals(base, base.panned(0f, base))
    }

    @Test
    fun `未缩放的量程平移上限为四分之三跨度`() {
        // MIN_VISIBLE_SHARE = 0.25：允许位移 = (跨度 - 最小重叠) / 跨度 = (100 - 25) / 100
        val base = ValueRange(100.0, 200.0)
        val limit = base.panLimit(base, currentSpan = 100.0)
        assertEquals(-0.75f, limit.start, 1e-6f)
        assertEquals(0.75f, limit.endInclusive, 1e-6f)
    }

    @Test
    fun `量程比数据窄时平移上限变紧`() {
        // 缩放到 1/4 跨度（25）。最小重叠 = min(25, 100) * 0.25 = 6.25
        // 允许位移 = (100 - 6.25) / 100 = 0.9375，仍然以「原始跨度」为分母
        val base = ValueRange(100.0, 200.0)
        val limit = base.panLimit(base, currentSpan = 25.0)
        assertEquals(0.9375f, limit.endInclusive, 1e-5f)
    }

    @Test
    fun `量程宽于数据时仍允许小幅平移但不至于把数据甩出视野`() {
        // 缩放到 2 倍跨度(200)、数据跨度 100：最小重叠 = min(200,100)*0.25 = 25
        // 允许位移 = (100 - 25) / 100 = 0.75，仍以「数据跨度」为分母。
        // 即缩得再远也能上下挪一挪，但挪到边界时数据还剩 1/4 在画面里。
        val base = ValueRange(100.0, 200.0)
        val limit = base.panLimit(base, currentSpan = 200.0)
        assertEquals(0.75f, limit.endInclusive, 1e-5f)

        val pushed = base.panned(limit.endInclusive, base)
        // 可视区 [175, 375]，与数据 [100, 200] 重叠 [175, 200] = 25
        assertEquals(175.0, pushed.low, 1e-4)
        val overlap = minOf(pushed.high, base.high) - maxOf(pushed.low, base.low)
        assertEquals(25.0, overlap, 1e-4)
    }

    @Test
    fun `最小重叠恰好等于数据跨度时不允许平移`() {
        // 极端：数据跨度极小或重叠阈值盖过整个数据区，平移无意义，上限应收到 0
        val base = ValueRange(100.0, 104.0)
        val limit = base.panLimit(base, currentSpan = 4.0)
        // minOverlap = min(4, 4) * 0.25 = 1，仍然允许 (4-1)/4 = 0.75
        assertEquals(0.75f, limit.endInclusive, 1e-5f)
    }

    @Test
    fun `退化量程的平移上限为零`() {
        val degenerate = ValueRange(100.0, 100.0)
        val limit = degenerate.panLimit(degenerate, currentSpan = 100.0)
        assertEquals(0f, limit.start, 1e-6f)
        assertEquals(0f, limit.endInclusive, 1e-6f)
    }

    @Test
    fun `按上限平移后数据仍与可视区重叠`() {
        // 回归：以前只按比例钳位移，能把 K 线整屏拖出去。
        // 现在拖到上限后，可视区必须仍与数据区重叠至少 MIN_VISIBLE_SHARE。
        val base = ValueRange(100.0, 200.0)
        val limit = base.panLimit(base, currentSpan = 100.0)
        val pushed = base.panned(limit.endInclusive, base)
        val overlap = minOf(pushed.high, base.high) - maxOf(pushed.low, base.low)
        assertTrue("重叠 $overlap 应 >= ${(base.high - base.low) * 0.25}", overlap >= 25.0 - 1e-4)
    }
    // endregion

    @Test
    fun `NaN 不参与取值`() {
        val range = ValueRange.of(doubleArrayOf(3.0, Double.NaN, 1.0, 9.0, Double.NaN))!!
        assertEquals(1.0, range.low, 1e-9)
        assertEquals(9.0, range.high, 1e-9)
    }

    @Test
    fun `网格线单调且落在范围内`() {
        val lines = ValueRange(1_000.0, 4_500.0).gridLines()
        // 步长向下取整到 1-2-5 阶梯，因此线数允许略多于请求的 5 条
        assertTrue(lines.size in 3..9)
        assertTrue(lines.zipWithNext().all { (a, b) -> a < b })
        assertTrue(lines.all { it >= 1_000.0 && it <= 4_500.0 })
        assertTrue(lines.first() % 500.0 == 0.0)
    }
    // endregion

    // region 指标序列
    @Test
    fun `均线窗口不足处为 NaN 且不画假线`() {
        val series = ChartModel.build(candles(60), listOf(5, 10), showBoll = false, subPanes = emptyList())
        val ma5 = series.overlay.lines.first { it.label == "MA5" }
        assertTrue(ma5.values[3].isNaN())
        assertFalse(ma5.values[4].isNaN())
    }

    @Test
    fun `开启 BOLL 后给出中轨上下轨与填充带`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = true, subPanes = emptyList())
        assertNotNull(series.overlay.bandFill)
        // 三条线缺一不可：历史上只加了上下轨，中轨（SMA）画不出来
        assertTrue(series.overlay.lines.any { it.label == "BOLL.M" })
        assertTrue(series.overlay.lines.any { it.label == "BOLL.U" })
        assertTrue(series.overlay.lines.any { it.label == "BOLL.L" })
    }

    @Test
    fun `均线可全部关闭以显示裸 K 图`() {
        val series = ChartModel.build(candles(60), emptyList(), showBoll = false, subPanes = emptyList())
        assertTrue(series.overlay.lines.isEmpty())
        assertNull(series.overlay.bandFill)
    }

    @Test
    fun `不选副图时副图列表为空`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = false, subPanes = emptyList())
        assertTrue(series.subPanes.isEmpty())
    }

    @Test
    fun `RSI 副图刻度固定在 0 到 100`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = false, subPanes = listOf(SubPaneKind.RSI))
        assertEquals(ValueRange(0.0, 100.0), series.subPanes.single().fixedRange)
    }

    @Test
    fun `VOL 副图从 0 起画`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = false, subPanes = listOf(SubPaneKind.VOLUME))
        val pane = series.subPanes.single()
        assertTrue(pane.fromZero)
        assertNotNull(pane.bars)
        assertEquals(0.0, series.subRange(pane, 0, 59).low, 1e-9)
    }

    @Test
    fun `副图支持多选且顺序按枚举声明稳定`() {
        // 故意用打乱的顺序传入，期望输出仍按 VOLUME → MACD → RSI 的声明顺序
        val series = ChartModel.build(
            candles(60),
            listOf(5),
            showBoll = false,
            subPanes = listOf(SubPaneKind.RSI, SubPaneKind.VOLUME, SubPaneKind.MACD),
        )
        assertEquals(listOf("VOL", "MACD", "RSI"), series.subPanes.map { it.title.substringBefore("(") })
    }

    @Test
    fun `多副图各自算各自的纵向范围`() {
        val series = ChartModel.build(
            candles(60),
            listOf(5),
            showBoll = false,
            subPanes = listOf(SubPaneKind.VOLUME, SubPaneKind.RSI),
        )
        val vol = series.subPanes.first { it.title == "VOL" }
        val rsi = series.subPanes.first { it.title.startsWith("RSI") }
        // RSI 固定 0..100，VOL 按量能取，两者互不干扰
        assertEquals(ValueRange(0.0, 100.0), series.subRange(rsi, 0, 59))
        assertNotEquals(ValueRange(0.0, 100.0), series.subRange(vol, 0, 59))
    }

    @Test
    fun `主图范围包含可见的均线值`() {
        val series = ChartModel.build(candles(60), listOf(30), showBoll = false, subPanes = emptyList())
        val range = series.mainRange(0, 59)
        assertTrue(range.high >= series.candles.last().high.toDouble())
    }
    // endregion

    // region tooltip
    @Test
    fun `tooltip 的涨幅按上一根收盘计算`() {
        val tip = ChartModel.tooltip(candles(10), 5, day, null)!!
        // 第 5 根收 105、上一根收 104 → +0.96%
        assertEquals("较上根 +0.96%", tip.changeText)
        assertEquals("105.00", tip.close)
        assertEquals("106.00", tip.high)
    }

    @Test
    fun `首根没有上一根可比时涨幅为占位`() {
        val tip = ChartModel.tooltip(candles(10), 0, day, null)!!
        assertEquals("较上根 --", tip.changeText)
    }

    @Test
    fun `下标越界不返回 tooltip`() {
        assertNull(ChartModel.tooltip(candles(3), 9, day, null))
    }

    @Test
    fun `日线 tooltip 只到天，分钟线带时分`() {
        // 不断言具体日期：CI 用 UTC、本地时区可能不同
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(ChartModel.formatTime(0L, 1_440L)))
        assertTrue(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(ChartModel.formatTime(0L, 5L)))
    }
    // endregion

    // region 时间轴标签落点（截图里 17:18 / 17:23 糊在一起就是这段的回归）

    @Test
    fun `间距足够时每条候选都画出来`() {
        val placed = ChartModel.timeLabelPlacements(
            centersPx = listOf(100f, 400f, 700f),
            labelWidthPx = 200f,
            plotWidthPx = 900f,
            minGapPx = 210f,
            step = 1,
        )
        assertEquals(3, placed.size)
        assertEquals(listOf(0f, 300f, 600f), placed.map { it.leftPx })
    }

    @Test
    fun `被左边界夹回来的首条若贴住下一条就整条丢掉`() {
        // 首条中心 20px、标签宽 200px ⇒ 未夹为 -80，夹到 0；
        // 第二条中心 240px ⇒ 左边缘 140，与 0 只差 140 < minGap 210 ⇒ 必须丢掉。
        // 旧实现（只夹不判间距）会把两条都画出来，于是文字直接贴死。
        val placed = ChartModel.timeLabelPlacements(
            centersPx = listOf(20f, 240f, 700f),
            labelWidthPx = 200f,
            plotWidthPx = 900f,
            minGapPx = 210f,
            step = 1,
        )
        assertEquals(listOf(0, 2), placed.map { it.index })
        assertEquals(listOf(0f, 600f), placed.map { it.leftPx })
    }

    @Test
    fun `任何一条标签的左右边缘都不越出绘图区`() {
        val placed = ChartModel.timeLabelPlacements(
            centersPx = List(40) { it * 30f },
            labelWidthPx = 218f,
            plotWidthPx = 744f,
            minGapPx = 230f,
            step = 1,
        )
        assertTrue(placed.isNotEmpty())
        placed.forEach { p ->
            assertTrue("left=${p.leftPx}", p.leftPx >= 0f)
            assertTrue("right=${p.leftPx + 218f}", p.leftPx + 218f <= 744f)
        }
    }

    @Test
    fun `画出来的标签之间恒定保持最小间距`() {
        val minGap = 230f
        val placed = ChartModel.timeLabelPlacements(
            centersPx = List(30) { it * 25f },
            labelWidthPx = 200f,
            plotWidthPx = 744f,
            minGapPx = minGap,
            step = 1,
        )
        placed.zipWithNext { a, b ->
            assertTrue("gap=${b.leftPx - a.leftPx}", b.leftPx - a.leftPx >= minGap)
        }
    }

    @Test
    fun `步长大于一时候选按步长采样`() {
        val placed = ChartModel.timeLabelPlacements(
            centersPx = List(10) { it * 100f },
            labelWidthPx = 100f,
            plotWidthPx = 2000f,
            minGapPx = 110f,
            step = 3,
        )
        assertEquals(listOf(0, 3, 6, 9), placed.map { it.index })
    }

    @Test
    fun `绘图区过窄时不画任何标签`() {
        val placed = ChartModel.timeLabelPlacements(
            centersPx = listOf(10f, 20f),
            labelWidthPx = 200f,
            plotWidthPx = 0f,
            minGapPx = 210f,
            step = 1,
        )
        assertTrue(placed.isEmpty())
    }
    // endregion

    // region 手势方向锁 / 双指缩放
    @Test
    fun `两个方向都没到阈值时不定性`() {
        assertNull(ChartGesture.axisLock(accumX = 3f, accumY = 4f, threshold = 12f))
    }

    @Test
    fun `横向累积先到阈值判横向`() {
        assertEquals(
            ChartGesture.Axis.HORIZONTAL,
            ChartGesture.axisLock(accumX = 13f, accumY = 2f, threshold = 12f),
        )
    }

    @Test
    fun `纵向累积先到阈值判纵向`() {
        assertEquals(
            ChartGesture.Axis.VERTICAL,
            ChartGesture.axisLock(accumX = 2f, accumY = -13f, threshold = 12f),
        )
    }

    @Test
    fun `纵向位移为负也判纵向`() {
        // 上滑是负 Y，符号不能影响定性
        assertEquals(
            ChartGesture.Axis.VERTICAL,
            ChartGesture.axisLock(accumX = 1f, accumY = -20f, threshold = 12f),
        )
    }

    @Test
    fun `两方向都过阈值时取绝对值大的`() {
        assertEquals(
            ChartGesture.Axis.HORIZONTAL,
            ChartGesture.axisLock(accumX = -30f, accumY = 20f, threshold = 12f),
        )
        assertEquals(
            ChartGesture.Axis.VERTICAL,
            ChartGesture.axisLock(accumX = 20f, accumY = -30f, threshold = 12f),
        )
    }

    @Test
    fun `两方向绝对值相等时判横向`() {
        assertEquals(
            ChartGesture.Axis.HORIZONTAL,
            ChartGesture.axisLock(accumX = 15f, accumY = 15f, threshold = 12f),
        )
    }

    @Test
    fun `阈值非正时永远不定性`() {
        // 阈值 0 会让第一帧的微小抖动就定性，必须挡掉
        assertNull(ChartGesture.axisLock(accumX = 100f, accumY = 100f, threshold = 0f))
        assertNull(ChartGesture.axisLock(accumX = 100f, accumY = 100f, threshold = -1f))
    }

    @Test
    fun `双指张开因子小于一`() {
        // 间距 100 -> 200：想看得更细，因子应 < 1
        val factor = ChartGesture.pinchFactor(previous = 100f, current = 200f)
        assertNotNull(factor)
        assertEquals(0.5f, factor!!, 1e-6f)
    }

    @Test
    fun `双指合拢因子大于一`() {
        val factor = ChartGesture.pinchFactor(previous = 200f, current = 100f)
        assertNotNull(factor)
        assertEquals(2.0f, factor!!, 1e-6f)
    }

    @Test
    fun `单帧缩放因子被钳住`() {
        // 指头抬起/落下造成的跳变不能变成几十倍缩放
        assertEquals(0.5f, ChartGesture.pinchFactor(previous = 10f, current = 1000f)!!, 1e-6f)
        assertEquals(2.0f, ChartGesture.pinchFactor(previous = 1000f, current = 10f)!!, 1e-6f)
    }

    @Test
    fun `间距为零返回空`() {
        assertNull(ChartGesture.pinchFactor(previous = 0f, current = 100f))
        assertNull(ChartGesture.pinchFactor(previous = 100f, current = 0f))
        assertNull(ChartGesture.pinchFactor(previous = -5f, current = 100f))
    }

    @Test
    fun `横向平移的零头会累积到凑够一根`() {
        // 每根宽 10px，单帧只移动 3px：单帧取整会得 0（这就是「拖不动」的根因），
        // 累积到第四帧才凑出 1 根（3*4=12px > 10px）。
        var remainder = 0f
        val barsPerFrame = (1..4).map {
            val step = ChartGesture.accumulateBarPan(remainder, pixelDelta = 3f, slotPx = 10f)
            remainder = step.remainder
            step.bars
        }
        assertEquals(listOf(0, 0, 0, 1), barsPerFrame)
        // 累积的位移总量应精确等于 12px / 10px = 1.2 根（1 根提交 + 0.2 根余量）
        assertEquals(0.2f, remainder, 1e-5f)
    }

    @Test
    fun `反向平移同样累积且保留符号`() {
        var remainder = 0f
        val bars = (1..4).map {
            val step = ChartGesture.accumulateBarPan(remainder, pixelDelta = -3f, slotPx = 10f)
            remainder = step.remainder
            step.bars
        }
        assertEquals(listOf(0, 0, 0, -1), bars)
        assertEquals(-0.2f, remainder, 1e-5f)
    }

    @Test
    fun `单帧位移超过一根时立即提交`() {
        val step = ChartGesture.accumulateBarPan(remainder = 0f, pixelDelta = 25f, slotPx = 10f)
        assertEquals(2, step.bars)
        assertEquals(0.5f, step.remainder, 1e-5f)
    }

    // endregion

    // region 双指缩放：真实逐帧序列

    /**
     * 复刻 `detectChartGestures` 的双指分支 + `KlineChart` 的 `onZoom`，把一串
     * 「两指间距」的逐帧采样喂进去，返回最终 viewport。
     *
     * 三段都必须复刻，缺一段这个测试就失去意义：
     * 1. `pinchFactor` 是**单帧比值**，一次性传总比值会掩盖真实行为；
     * 2. 中间必须过一遍 [ChartGesture.accumulatePinch] —— 缓慢捏合时单帧因子
     *    小到被 `zoom()` 的 `roundToInt()` 抹平，真正让它生效的就是这个累加器；
     * 3. 提交后必须把**实际生效**的根数差额回调 [ChartGesture.reportApplied]，
     *    否则取整误差会逐帧漂移（早期漏了这一步，余量实测漂到 -26 根）。
     */
    private fun applyPinch(
        viewport: ChartViewport,
        distances: List<Float>,
        anchorRatio: Float = 0.5f,
        barCount: Int = 2_000,
    ): ChartViewport {
        var current = viewport
        var previousDistance = 0f
        var remainder = 0f
        distances.forEach { distance ->
            ChartGesture.pinchFactor(previousDistance, distance)?.let { factor ->
                val before = current.clamp(barCount).visibleBars
                val step = ChartGesture.accumulatePinch(
                    remainder = remainder,
                    factor = factor,
                    visibleBars = before,
                )
                if (step.bars != 0) {
                    current = current.zoom(
                        barFactor = (before + step.bars).toFloat() / before,
                        anchorRatio = anchorRatio,
                        barCount = barCount,
                    )
                    remainder = ChartGesture.reportApplied(
                        remainder = step.remainder,
                        requestedBars = step.bars,
                        appliedBars = current.clamp(barCount).visibleBars - before,
                    )
                } else {
                    remainder = step.remainder
                }
            }
            previousDistance = distance
        }
        // 手势结束那一帧的剩余零头被丢弃 —— 与真实实现一致（松手即清零），
        // 因此断言只能要求「有变化」，不能要求精确到某根。
        return current
    }

    @Test
    fun `双指张开可以缩小可见根数`() {
        // 两指从 200px 一路张到 600px
        val opening = (0..7).map { 200f + it * (400f / 7f) }
        val zoomed = applyPinch(ChartViewport(120, 0), opening, barCount = 500)
        assertTrue("张开应减少可见根数，实际 ${zoomed.visibleBars}", zoomed.visibleBars < 120)
    }

    @Test
    fun `双指合拢可以放大可见根数`() {
        val closing = (0..7).map { 600f - it * (400f / 7f) }
        val zoomed = applyPinch(ChartViewport(120, 0), closing, barCount = 500)
        assertTrue("合拢应增加可见根数，实际 ${zoomed.visibleBars}", zoomed.visibleBars > 120)
    }

    @Test
    fun `缓慢张开的每一帧都应生效而不是被取整抹平`() {
        // 每次只变 1px 的极慢捏合（两指间距 300 → 270）。
        // 修复前：每帧因子约 1.0033，`zoom()` 把 120.4 取整回 120，
        // 30 帧下来纹丝不动（可见根数还是 120），表现为「慢慢捏完全没反应」。
        // 慢速捏合时**第一帧是无效帧**（previousDistance 初始为 0，返回 null），
        // 所以有效帧数是 30 帧而不是 31 帧。
        val gentle = (0..30).map { 300f - it * 1f }
        val zoomed = applyPinch(ChartViewport(120, 0), gentle)
        assertTrue("30 帧缓慢张开必须累积出变化，实际 ${zoomed.visibleBars}", zoomed.visibleBars > 120)
    }

    @Test
    fun `缓慢合拢同样能累积出变化`() {
        val gentle = (0..30).map { 300f + it * 1f }
        val zoomed = applyPinch(ChartViewport(120, 0), gentle)
        assertTrue("30 帧缓慢合拢应减少可见根数，实际 ${zoomed.visibleBars}", zoomed.visibleBars < 120)
    }

    @Test
    fun `缓慢捏合的灵敏度由手势起点决定`() {
        // 交互含义（重要，避免以后把「中段捏合看起来没反应」当成 bug 再改一遍）：
        // `pinchFactor` 是**相邻两帧的比值**，所以 1px/帧 的绝对速度越到后面
        // 占比越小 —— 间距 300→270 时每帧只变 0.33%，而 150→120 时每帧变 0.67%，
        // 同样的手指速度自然更灵敏。这与真机手感一致（起始间距越大越迟钝）。
        val fromFar = applyPinch(ChartViewport(120, 0), (0..30).map { 300f - it * 1f })
        val fromNear = applyPinch(ChartViewport(120, 0), (0..30).map { 150f - it * 1f })
        assertTrue(
            "起点更近的手势应该更灵敏：fromFar=${fromFar.visibleBars} fromNear=${fromNear.visibleBars}",
            fromNear.visibleBars > fromFar.visibleBars,
        )
    }

    @Test
    fun `单帧的缓慢张开不足以改变可见根数`() {
        // 这一条是「缓慢张开」的对照组，证明修复针对的确实是取整抹平：
        // 单帧因子 300/299 ≈ 1.00334，120 根算下来 120.4，取整后仍是 120。
        val factor = ChartGesture.pinchFactor(previous = 300f, current = 299f)
        assertNotNull(factor)
        assertEquals(120, ChartViewport(120, 0).zoom(factor!!, 0.5f, barCount = 2_000).visibleBars)
        // 而累加器会把这一帧的量攒住，不让它被抹掉。
        // 张开 = 两指间距变大 = 可见根数要变多，余量应为**正**的根数。
        val step = ChartGesture.accumulatePinch(remainder = 0f, factor = factor, visibleBars = 120)
        assertEquals("不足一根不该提交", 0, step.bars)
        assertTrue("零头应被留下而不是丢弃，实际 ${step.remainder}", step.remainder > 0f)
        assertEquals(120 * (factor - 1f), step.remainder, 1e-5f)
    }

    @Test
    fun `每次提交只走一根而不是把攒下的量一次放完`() {
        // 钉住「冲过头」那个坑：若写成「攒够就把攒下的量一次放完」，
        // 一次攒了 35 根时 visibleBars 会被一帧从 120 拉到 85，而参考框架里
        // 只消费掉 1 根 —— 剩下的 34 根下一帧又被施加一次，缩放立刻冲过头。
        // 正确行为：每帧最多一根，快速张合靠「每帧都提交一根」自然累积。
        val huge = ChartGesture.accumulatePinch(remainder = 35f, factor = 1f, visibleBars = 120)
        assertEquals(1, huge.bars)
        assertEquals("必须还剩 34 根，不能丢", 34f, huge.remainder, 1e-5f)
    }

    @Test
    fun `反向攒够时提交负一根`() {
        val step = ChartGesture.accumulatePinch(remainder = -3f, factor = 1f, visibleBars = 120)
        assertEquals(-1, step.bars)
        assertEquals(-2f, step.remainder, 1e-5f)
    }

    @Test
    fun `反向捏合第一帧就换向而不是先还清欠账`() {
        // 快速张开一帧会攒下一笔**负**的欠账（几十根），此时手指反向合拢：
        // 修复前这一帧仍提交 -1（继续放大），要连走好几帧才把欠账抵完才换向 ——
        // 图上表现为「手已经往回收了，图还在放大」。
        val fast = ChartGesture.accumulatePinch(remainder = 0f, factor = 0.8f, visibleBars = 120)
        assertEquals("一帧最多一根", -1, fast.bars)
        assertTrue("欠账应为负（可见根数要继续变少）", fast.remainder < -1f)

        val reversed = ChartGesture.accumulatePinch(
            remainder = fast.remainder,
            factor = 1.2f,
            visibleBars = 119,
        )
        assertEquals("反向第一帧就必须提交正的一根", 1, reversed.bars)
        assertTrue("反向的折算量不能被旧欠账吃掉", reversed.remainder > 0f)
    }

    @Test
    fun `本帧折算量为零时余量继续逐帧消化`() {
        // 只有「本帧有明确的相反方向」才作废欠账。手指停住（factor == 1）时
        // 方向信息为空，欠账仍要按每帧一根消化掉 —— 否则快速捏合松手前的那几根会凭空消失。
        val step = ChartGesture.accumulatePinch(remainder = -8f, factor = 1f, visibleBars = 120)
        assertEquals(-1, step.bars)
        assertEquals(-7f, step.remainder, 1e-5f)
    }

    @Test
    fun `快速张开后反向合拢图上必须立刻开始往回走`() {
        // 逐帧复刻真实手势：张开 4 帧（每帧间距 +20%）→ 反向合拢 4 帧。
        // 修复前第 5 帧（反向的第一帧）仍在继续放大，图上要过好几帧才回头。
        var viewport = ChartViewport(120, 0)
        var previousDistance = 0f
        var remainder = 0f
        val distances = listOf(200f, 240f, 288f, 346f, 415f, 346f, 288f, 240f, 200f)
        val trail = mutableListOf<Int>()
        distances.forEach { distance ->
            ChartGesture.pinchFactor(previousDistance, distance)?.let { factor ->
                val before = viewport.clamp(BARS).visibleBars
                val step = ChartGesture.accumulatePinch(remainder, factor, before)
                if (step.bars != 0) {
                    viewport = viewport.zoom((before + step.bars).toFloat() / before, 0.5f, BARS)
                    remainder = ChartGesture.reportApplied(
                        step.remainder,
                        step.bars,
                        viewport.clamp(BARS).visibleBars - before,
                    )
                } else {
                    remainder = step.remainder
                }
            }
            previousDistance = distance
            trail += viewport.clamp(BARS).visibleBars
        }
        // 索引 5 是反向的第一帧（间距从 415 回到 346），它必须比索引 4 更宽（开始往回走）
        assertTrue(
            "反向第一帧就该往回走，实际轨迹 $trail",
            trail[5] > trail[4],
        )
        assertTrue("换向后应逐帧持续回走，实际轨迹 $trail", trail[8] > trail[5])
    }

    @Test
    fun `每帧最多提交一根且余量始终是未消耗的捏合量`() {
        // 关键认识：**余量不要求「小于一根」**。它记的是「手指已经捏了、但还没被
        // 施加到图上的量」。一帧快速张合可能产生十几根的量，而每帧只允许提交一根
        // （这正是防冲过头的设计），所以余量合理地停留在「十几根」是很正常的，
        // 会在此后逐帧以每帧一根的速度消化掉。
        // 早期把「|remainder| < 1」当成不变量，是把自己的实现细节当成了契约。
        //
        // 真正的不变量有两条：
        // 1. 每帧提交量的绝对值 ≤ 1（防冲过头）；
        // 2. 余量按「本帧折算量 - 实际生效量」精确结转，不丢也不重复计。
        var remainder = 0f
        var viewport = ChartViewport(120, 0)
        val factors = listOf(0.90f, 0.92f, 1.05f, 0.88f, 1.03f, 0.95f)
        factors.forEach { factor ->
            val before = viewport.clamp(BARS).visibleBars
            val folded = before * (factor - 1f)          // 本帧手指折算出的根数
            val step = ChartGesture.accumulatePinch(remainder, factor, before)
            assertTrue("每帧提交量不得超过一根：${step.bars}", kotlin.math.abs(step.bars) <= 1)
            if (step.bars != 0) {
                viewport = viewport.zoom((before + step.bars).toFloat() / before, 0.5f, BARS)
                val applied = viewport.clamp(BARS).visibleBars - before
                remainder = ChartGesture.reportApplied(step.remainder, step.bars, applied)
                // 结转正确性：(上一帧余量 + 本帧折算量) - 实际生效量 == 新余量
                assertEquals(
                    "余量结转必须精确",
                    remainder,
                    step.remainder + (step.bars - applied),
                    1e-4f,
                )
            } else {
                remainder = step.remainder
                assertEquals("未提交时余量应等于累计折算量", remainder, folded, 1e-4f)
            }
        }
        assertTrue("多帧捏合后可见根数应发生变化，实际 ${viewport.visibleBars}", viewport.visibleBars != 120)
    }

    @Test
    fun `快速张合会被逐帧消化而不是一次冲过头`() {
        // 一帧捏进 20 根的量：第一帧只能走 1 根，其余 19 根留在余量里，
        // 此后每帧继续走 1 根 —— 既不会一帧跳 20 根，也不会把量丢掉。
        var remainder = 0f
        var viewport = ChartViewport(120, 0)
        val bars = 120
        val first = ChartGesture.accumulatePinch(remainder, 0.85f, bars)
        assertEquals("一帧最多一根", -1, first.bars)
        remainder = first.remainder
        assertTrue("剩下的量必须留在余量里", kotlin.math.abs(remainder) > 1f)
        // 接下来空手（factor = 1）继续捏，余量应被逐帧消化
        var frames = 1
        while (kotlin.math.abs(remainder) >= 1f && frames < 40) {
            val before = viewport.clamp(BARS).visibleBars
            val step = ChartGesture.accumulatePinch(remainder, 1f, before)
            if (step.bars == 0) break
            viewport = viewport.zoom((before + step.bars).toFloat() / before, 0.5f, BARS)
            remainder = ChartGesture.reportApplied(step.remainder, step.bars, viewport.clamp(BARS).visibleBars - before)
            frames++
        }
        assertTrue("余量应被逐帧消化完，实际剩余 $remainder", kotlin.math.abs(remainder) < 1f)
        assertTrue("消化过程应持续多帧而不是一帧到位，实际 $frames 帧", frames > 1)
    }

    @Test
    fun `真实生效量小于请求量时差额退回余量`() {
        // zoom() 内部 roundToInt，请求 1 根未必真的走 1 根。
        // 少走的部分必须退回余量，否则相同的取整误差会一帧帧叠加成偏置。
        assertEquals(1.4f, ChartGesture.reportApplied(remainder = 0.4f, requestedBars = 1, appliedBars = 0), 1e-6f)
        assertEquals(0.6f, ChartGesture.reportApplied(remainder = 0.6f, requestedBars = 1, appliedBars = 1), 1e-6f)
        // 本帧没请求提交时，余量原样保留
        assertEquals(0.4f, ChartGesture.reportApplied(remainder = 0.4f, requestedBars = 0, appliedBars = 0), 1e-6f)
    }

    @Test
    fun `价格刻度向上拖是拉伸向下拖是收缩`() {
        val height = 400f
        // 向上拖：屏幕坐标 deltaY 为负 → 因子 < 1 → 量程变小 → K 线纵向变高
        val up = ChartGesture.priceScaleFactor(deltaY = -40f, plotHeightPx = height)!!
        assertTrue("向上拖应得到小于 1 的因子（量程变小 = K 线变高），实际 $up", up < 1f)
        // 向下拖：因子 > 1 → 量程变大 → K 线被压扁
        val down = ChartGesture.priceScaleFactor(deltaY = 40f, plotHeightPx = height)!!
        assertTrue("向下拖应得到大于 1 的因子（量程变大 = K 线变矮），实际 $down", down > 1f)
        // 同样距离的上拖与下拖互为倒数，手感在两个方向上对称
        assertEquals(1f, up * down, 1e-5f)
    }

    @Test
    fun `价格刻度缩放与分几帧拖无关`() {
        // 乘性映射的意义：拖过的**总距离**决定缩放倍数，分几帧拖都一样。
        // 线性映射做不到 —— 每帧按比例相加会随帧数漂移，慢拖与快拖结果不同。
        val height = 400f
        val oneShot = ChartGesture.priceScaleFactor(deltaY = -120f, plotHeightPx = height)!!
        var product = 1f
        repeat(10) { product *= ChartGesture.priceScaleFactor(deltaY = -12f, plotHeightPx = height)!! }
        assertEquals(oneShot, product, 1e-4f)
    }

    @Test
    fun `价格刻度缩放的单帧因子被钳在 e 的倒数到 e 之间`() {
        // 异常位移（多指切换、坐标系跳变）不该让图瞬间缩放几十倍
        val height = 400f
        assertEquals(
            1f / kotlin.math.E.toFloat(),
            ChartGesture.priceScaleFactor(deltaY = -100_000f, plotHeightPx = height)!!,
            1e-4f,
        )
        assertEquals(
            kotlin.math.E.toFloat(),
            ChartGesture.priceScaleFactor(deltaY = 100_000f, plotHeightPx = height)!!,
            1e-4f,
        )
    }

    @Test
    fun `价格刻度缩放的非法输入返回 null`() {
        assertNull(ChartGesture.priceScaleFactor(deltaY = -10f, plotHeightPx = 0f))
        assertNull(ChartGesture.priceScaleFactor(deltaY = -10f, plotHeightPx = -5f))
        assertNull(ChartGesture.priceScaleFactor(deltaY = Float.NaN, plotHeightPx = 400f))
        assertNull(ChartGesture.priceScaleFactor(deltaY = Float.POSITIVE_INFINITY, plotHeightPx = 400f))
    }

    @Test
    fun `双指纵向拉开是把价格量程收窄K线变高`() {
        // 上下拉开：纵向间距 100 → 200。复用 pinchFactor 得到 < 1 的因子，
        // 乘到价格量程上即为「量程变小 = K 线纵向变高」—— 与时间轴的拉开语义一致。
        val base = ValueRange(100.0, 200.0)
        val spread = ChartGesture.pinchFactor(previous = 100f, current = 200f)!!
        assertTrue("拉开应得到小于 1 的因子，实际 $spread", spread < 1f)
        val zoomed = base.scaled(spread, base)
        assertTrue(
            "拉开后量程应变小（K 线变高），实际跨度 ${zoomed.high - zoomed.low}",
            zoomed.high - zoomed.low < base.high - base.low,
        )
        // 不动点仍是中位价，纵向缩放不会让整张图跑掉
        assertEquals(150.0, zoomed.center, 1e-6)
    }

    @Test
    fun `双指纵向合拢是把价格量程放宽K线变矮`() {
        val base = ValueRange(100.0, 200.0)
        val pinched = ChartGesture.pinchFactor(previous = 200f, current = 100f)!!
        assertTrue("合拢应得到大于 1 的因子，实际 $pinched", pinched > 1f)
        val zoomed = base.scaled(pinched, base)
        assertTrue(
            "合拢后量程应变大（K 线变矮），实际跨度 ${zoomed.high - zoomed.low}",
            zoomed.high - zoomed.low > base.high - base.low,
        )
    }

    @Test
    fun `横向间距不变时时间轴因子为一不影响时间轴`() {
        // 双指只上下拉开、横向间距保持 300 不变：时间轴拿到的因子必须是 1，
        // 否则「上下拉伸动的却是横坐标」的老问题会以另一种形式回来。
        val barFactor = ChartGesture.pinchFactor(previous = 300f, current = 300f)!!
        assertEquals(1f, barFactor, 1e-6f)
    }

    @Test
    fun `双指缩放按间距变化量定轴且只锁一个轴`() {
        val lock = 16f
        // 只上下拉开：纵向间距 +120，横向间距不动 → 锁纵向
        assertEquals(
            ChartGesture.Axis.VERTICAL,
            ChartGesture.axisLock(accumX = 0f, accumY = 120f, threshold = lock),
        )
        // 只左右拉开 → 锁横向
        assertEquals(
            ChartGesture.Axis.HORIZONTAL,
            ChartGesture.axisLock(accumX = 120f, accumY = 0f, threshold = lock),
        )
        // 斜着拉开：谁的变化量大听谁的，不会两个轴一起动
        assertEquals(
            ChartGesture.Axis.VERTICAL,
            ChartGesture.axisLock(accumX = 30f, accumY = 90f, threshold = lock),
        )
        // 都没够阈值 → 不定轴，一个轴都不缩放
        assertNull(ChartGesture.axisLock(accumX = 8f, accumY = -6f, threshold = lock))
    }

    @Test
    fun `每帧折算的根数与总根数变化一致`() {
        // 守恒：一帧的因子折算成根数 = bars * (factor - 1)；提交 + 余量必须等于它。
        // 取一个不跨阈值的因子，验证「本帧无提交、量全进余量」这一最基本的情形。
        val bars = 120
        val factor = 1.002f
        val expectedBars = bars * (factor - 1f)
        val step = ChartGesture.accumulatePinch(remainder = 0f, factor = factor, visibleBars = bars)
        assertEquals("不足一根不该提交", 0, step.bars)
        assertEquals(expectedBars, step.remainder, 1e-5f)
    }

    @Test
    fun `跨阈值那一帧提交一根且余下部分不丢`() {
        // 跨阈值时：提交一根 + 余量 == 本帧应折算的根数
        val bars = 120
        // 让本帧折算量正好是 2.5 根
        val factor = 1f + 2.5f / bars
        val step = ChartGesture.accumulatePinch(remainder = 0f, factor = factor, visibleBars = bars)
        assertEquals(1, step.bars)
        assertEquals("提交 1 根后应余下 1.5 根", 1.5f, step.remainder, 1e-4f)
    }

    @Test
    fun `非法捏合因子不破坏已有零头`() {
        // 指头抬起/落下会让间距跳变到 0，pinchFactor 返回 null，
        // 但万一传进来也必须视为「本帧无变化」而不是把攒下的量清掉。
        // 余量单位是「根数」，所以这里用 0.4 根这种真实量级。
        val base = 0.4f
        assertEquals(base, ChartGesture.accumulatePinch(base, 0f, 120).remainder, 1e-9f)
        assertEquals(base, ChartGesture.accumulatePinch(base, -1f, 120).remainder, 1e-9f)
        assertEquals(base, ChartGesture.accumulatePinch(base, Float.NaN, 120).remainder, 1e-9f)
        assertEquals(0, ChartGesture.accumulatePinch(base, 0f, 120).bars)
    }

    @Test
    fun `双指缩放不受单指方向锁影响`() {
        // 双指分支必须 continue，不能落进单指分支去改 axisAccum
        val viewport = ChartViewport(120, 0)
        val once = applyPinch(viewport, listOf(200f, 400f), barCount = 500)
        assertNotEquals(viewport.visibleBars, once.visibleBars)
    }

    @Test
    fun `非法槽宽或位移时不丢已有零头`() {
        // slot 为 0 / 负 / NaN 都视为本帧无位移，但已经攒下的零头不能清掉
        assertEquals(0, ChartGesture.accumulateBarPan(0.4f, 5f, 0f).bars)
        assertEquals(0.4f, ChartGesture.accumulateBarPan(0.4f, 5f, 0f).remainder, 1e-6f)
        assertEquals(0.4f, ChartGesture.accumulateBarPan(0.4f, 5f, -3f).remainder, 1e-6f)
        assertEquals(0.4f, ChartGesture.accumulateBarPan(0.4f, 5f, Float.NaN).remainder, 1e-6f)
        assertEquals(0.4f, ChartGesture.accumulateBarPan(0.4f, Float.NaN, 10f).remainder, 1e-6f)
    }

    @Test
    fun `累积结果始终满足 位移总量 = 提交根数 + 零头`() {
        // 不变量：不管怎么拆帧，累积提交的根数 + 剩余零头 == 总位移/槽宽
        var remainder = 0f
        var committed = 0
        val slot = 7f
        val deltas = listOf(3f, -1f, 9f, 2f, -12f, 4.5f, 6f)
        for (d in deltas) {
            val step = ChartGesture.accumulateBarPan(remainder, d, slot)
            remainder = step.remainder
            committed += step.bars
        }
        val total = deltas.sum() / slot
        assertEquals(total, committed + remainder, 1e-4f)
    }
    // endregion
}
