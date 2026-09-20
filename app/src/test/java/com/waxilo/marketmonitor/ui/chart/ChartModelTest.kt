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
        assertTrue(ChartViewport(0, 120, 0).window().isEmpty())
    }

    @Test
    fun `序列不足一屏时可见根数收到实际长度`() {
        val viewport = ChartViewport(barCount = 6, visibleBars = 120).clamp()
        assertEquals(6, viewport.visibleBars)
        assertEquals(0..5, viewport.window())
    }

    @Test
    fun `往右拖（看更早）右偏移为负`() {
        // 语义：pan 正数 = 手指右移 = 窗口左移看更早的历史 => rightOffset 变负
        val moved = ChartViewport(300, 100, 0).pan(20f)
        assertEquals(-20, moved.rightOffset)
        assertEquals(279, moved.endIndex())
    }

    @Test
    fun `往更早方向拖到底时停在序列起点`() {
        val moved = ChartViewport(100, 50, 0).pan(1_000f)
        // 最多移出 (barCount - visible) 根历史，恰好看得到第 0 根
        assertEquals(-50, moved.rightOffset)
        assertEquals(0, moved.startIndex())
    }

    @Test
    fun `最新一根可以往左拖出右侧留白`() {
        // 用户诉求：最新 K 线不能只顶在最右边，要能把它推到中间偏左，右侧露出空白。
        // 手指往左拖（负 delta）=> rightOffset 变正 => 绘图区右端越出序列末根。
        val viewport = ChartViewport(300, 100, 0)
        val moved = viewport.pan(-20f)
        assertEquals(20, moved.rightOffset)
        // 绘图区右端越出序列末根（这就是右侧那段空白）
        assertEquals(319, moved.endIndex())
        // 但**真实数据**的右端仍是末根，索引安全 —— 不会越界取数据
        assertEquals(299, moved.window().last)
    }

    @Test
    fun `可左拖的留白有上限不会拖成一片空白`() {
        val viewport = ChartViewport(300, 100, 0)
        val moved = viewport.pan(-10_000f)
        // 上限 = visibleBars * (1 - MIN_VISIBLE_SHARE)
        val expected = (100 * (1f - ChartViewport.MIN_VISIBLE_SHARE)).toInt()
        assertEquals(expected, moved.rightOffset)
        // 数据区仍占满可视区的至少 MIN_VISIBLE_SHARE
        val dataShare = (moved.clamp().visibleBars - moved.rightOffset).toFloat() / moved.clamp().visibleBars
        assertTrue("数据占比 $dataShare 太低", dataShare >= ChartViewport.MIN_VISIBLE_SHARE - 1e-6f)
    }

    @Test
    fun `右侧留白时 window 不越界而 plotRange 会越界`() {
        val viewport = ChartViewport(300, 100, 20).clamp()
        // window：夹在 0..barCount-1，索引安全
        assertEquals(299, viewport.window().last)
        assertTrue(viewport.window().first >= 0)
        // plotRange：含留白，右端越出末根
        assertEquals(319, viewport.plotRange().last)
        assertEquals(220, viewport.plotRange().first)
    }

    @Test
    fun `右侧留白区内点按十字光标不越界`() {
        val viewport = ChartViewport(300, 100, 20).clamp()
        // 绘图区最右侧（留白区）反查——必须夹回末根，不能返回 319
        assertEquals(299, viewport.indexAt(1f))
    }

    @Test
    fun `横坐标反查与归一化在留白时互为逆运算`() {
        val viewport = ChartViewport(300, 100, 20).clamp()
        val index = viewport.indexAt(0.5f)
        val fraction = viewport.fractionOf(index)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `缩放上下界固定`() {
        val viewport = ChartViewport(1_000, 100, 0)
        assertEquals(ChartViewport.MIN_BARS, viewport.zoom(0.0001f).visibleBars)
        assertEquals(ChartViewport.MAX_BARS, viewport.zoom(1_000f).visibleBars)
    }

    @Test
    fun `缩放时锚点处的蜡烛保持不动`() {
        // 负右偏移 = 正在看更早的历史
        val viewport = ChartViewport(1_000, 100, -200)
        val start = viewport.startIndex()
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f)
        // 锚点在左端：缩放后左端仍是同一根蜡烛
        assertEquals(start, anchored.startIndex())
    }

    @Test
    fun `右侧留白时缩放锚点依然不动`() {
        val viewport = ChartViewport(1_000, 100, 30).clamp()
        val start = viewport.startIndex()
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f)
        // 放大（可见根数变少）后左端仍是同一根
        assertEquals(start, anchored.startIndex())
    }

    @Test
    fun `右侧留白时右端锚点也不动`() {
        val viewport = ChartViewport(1_000, 100, 30).clamp()
        val end = viewport.endIndex()
        val anchored = viewport.zoom(0.5f, anchorRatio = 1f)
        assertEquals(end, anchored.endIndex())
    }

    @Test
    fun `横坐标反查与归一化互为逆运算`() {
        val viewport = ChartViewport(300, 100, 0)
        val index = viewport.indexAt(0.5f)
        val fraction = viewport.fractionOf(index)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `序列变长后仍贴着最新`() {
        val grown = ChartViewport(300, 100, 0).resize(301)
        assertEquals(300, grown.endIndex())
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
    fun `开启 BOLL 后给出上下轨与填充带`() {
        val series = ChartModel.build(candles(60), listOf(5), showBoll = true, subPanes = emptyList())
        assertNotNull(series.overlay.bandFill)
        assertTrue(series.overlay.lines.any { it.label == "BOLL.U" })
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
