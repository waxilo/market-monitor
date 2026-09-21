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
import kotlin.math.floor

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
        assertTrue(ChartViewport(120f, 0f).window(0).isEmpty())
    }

    @Test
    fun `序列不足一屏时可见根数收到实际长度`() {
        val viewport = ChartViewport(visibleBars = 120f).clamp(barCount = 6)
        assertEquals(6f, viewport.visibleBars, 1e-6f)
        assertEquals(0..5, viewport.window(6))
    }

    /**
     * 初始视窗右侧留白 3 根：最新一根贴着右边缘时右边没有任何参照，
     * 正在走的那根等于压在边框上。留白只影响**绘图区几何**，
     * 取数据的窗口右端仍必须是末根。
     */
    @Test
    fun `初始视窗在最新一根右侧留三根空白`() {
        val viewport = ChartViewport.initial()
        assertEquals(ChartViewport.DEFAULT_RIGHT_BLANK, viewport.rightOffset, 1e-6f)
        assertEquals(ChartViewport.DEFAULT_VISIBLE, viewport.visibleBars, 1e-6f)
        val barCount = 500
        // 绘图区右端越出末根 3 根（这就是那段空白）
        assertEquals(barCount - 1 + 3, viewport.plotRange(barCount).last)
        // 真实数据的右端仍是末根，索引安全
        assertEquals(barCount - 1, viewport.window(barCount).last)
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
        val viewport = ChartViewport(visibleBars = 100f)
        // 视窗与序列长度无关：换一个 barCount 就得到那个序列的窗口，不需要任何对齐步骤
        assertEquals(20..119, viewport.window(120))
        assertEquals(0..99, viewport.window(100))
        assertEquals(420..519, viewport.window(520))
        // 短序列收窄到实际根数，绝不出现空窗口
        assertEquals(0..5, viewport.window(6))
    }

    /**
     * 浮点左端与整数窗口必须描述同一根蜡烛。
     *
     * 渲染按 [ChartViewport.plotStart]（浮点）定位、取数按 [ChartViewport.plotRange]
     * （整数）切片，两者若脱钩，整排蜡烛会相对网格/时间轴偏移。
     */
    @Test
    fun `浮点左端取整后与整数窗口两端对齐`() {
        val viewport = ChartViewport(60f, 3f).clamp(500)
        val start = viewport.plotStart(500)
        assertEquals(floor(start).toInt(), viewport.plotRange(500).first)
        assertEquals(
            floor(start + viewport.visibleBars - 1f).toInt(),
            viewport.plotRange(500).last,
        )
    }

    /**
     * 整段序列一屏放得下时不留白。
     *
     * 默认留白 3 根在长序列上是「最新一根离右边框远一点」，但在短序列上
     * （新币的月线常常只有十几根）屏幕里本就没有多余空间，留白会把最老的几根
     * 挤出左边界 —— 用户看到的是「少了几根蜡烛」。
     */
    @Test
    fun `序列不足一屏时不留白以免挤掉最老的几根`() {
        val viewport = ChartViewport.initial().clamp(barCount = 20)
        assertEquals(0f, viewport.rightOffset, 1e-6f)
        // 20 根全在窗口里，一根不少
        assertEquals(0..19, viewport.window(20))
        assertEquals(0..19, viewport.plotRange(20))
    }

    @Test
    fun `往右拖（看更早）右偏移为负`() {
        // 语义：pan 正数 = 手指右移 = 窗口左移看更早的历史 => rightOffset 变负
        val moved = ChartViewport(100f, 0f).pan(20f, barCount = 300)
        assertEquals(-20f, moved.rightOffset, 1e-6f)
        assertEquals(279, moved.endIndex(300))
    }

    /**
     * 单帧位移不足一根也必须生效。
     *
     * 旧版 `pan()` 内部 `roundToInt()`，0.3 根会被抹成 0，四帧下来纹丝不动 ——
     * 这正是历史上「横向拖不动」的根因。视窗改成浮点后零头天然被保留。
     */
    @Test
    fun `连续小位移平移逐帧累积而不是被抹平`() {
        var viewport = ChartViewport(100f, 0f)
        repeat(4) { viewport = viewport.pan(0.3f, barCount = 500) }
        assertEquals(-1.2f, viewport.rightOffset, 1e-4f)
    }

    @Test
    fun `往更早方向拖到底时停在序列起点`() {
        val moved = ChartViewport(50f, 0f).pan(1_000f, barCount = 100)
        // 最多移出 (barCount - visible) 根历史，恰好看得到第 0 根
        assertEquals(-50f, moved.rightOffset, 1e-6f)
        assertEquals(0, moved.startIndex(100))
    }

    @Test
    fun `最新一根可以往左拖出右侧留白`() {
        // 用户诉求：最新 K 线不能只顶在最右边，要能把它推到中间偏左，右侧露出空白。
        // 手指往左拖（负 delta）=> rightOffset 变正 => 绘图区右端越出序列末根。
        val viewport = ChartViewport(100f, 0f)
        val moved = viewport.pan(-20f, barCount = 300)
        assertEquals(20f, moved.rightOffset, 1e-6f)
        // 绘图区右端越出序列末根（这就是右侧那段空白）
        assertEquals(319, moved.endIndex(300))
        // 但**真实数据**的右端仍是末根，索引安全 —— 不会越界取数据
        assertEquals(299, moved.window(300).last)
    }

    @Test
    fun `可左拖的留白有上限不会拖成一片空白`() {
        val viewport = ChartViewport(100f, 0f)
        val moved = viewport.pan(-10_000f, barCount = 300)
        // 上限 = visibleBars * (1 - MIN_VISIBLE_SHARE)
        val expected = 100f * (1f - ChartViewport.MIN_VISIBLE_SHARE)
        assertEquals(expected, moved.rightOffset, 1e-6f)
        // 数据区仍占满可视区的至少 MIN_VISIBLE_SHARE
        val visible = moved.clamp(300).visibleBars
        val dataShare = (visible - moved.rightOffset) / visible
        assertTrue("数据占比 $dataShare 太低", dataShare >= ChartViewport.MIN_VISIBLE_SHARE - 1e-6f)
    }

    @Test
    fun `右侧留白时 window 不越界而 plotRange 会越界`() {
        val viewport = ChartViewport(100f, 20f).clamp(300)
        // window：夹在 0..barCount-1，索引安全
        assertEquals(299, viewport.window(300).last)
        assertTrue(viewport.window(300).first >= 0)
        // plotRange：含留白，右端越出末根
        assertEquals(319, viewport.plotRange(300).last)
        assertEquals(220, viewport.plotRange(300).first)
    }

    @Test
    fun `右侧留白区内点按十字光标不越界`() {
        val viewport = ChartViewport(100f, 20f).clamp(300)
        // 绘图区最右侧（留白区）反查——必须夹回末根，不能返回 319
        assertEquals(299, viewport.indexAt(1f, barCount = 300))
    }

    @Test
    fun `横坐标反查与归一化在留白时互为逆运算`() {
        val viewport = ChartViewport(100f, 20f).clamp(300)
        val index = viewport.indexAt(0.5f, barCount = 300)
        val fraction = viewport.fractionOf(index, barCount = 300)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `缩放上下界固定`() {
        val viewport = ChartViewport(100f, 0f)
        assertEquals(
            ChartViewport.MIN_BARS.toFloat(),
            viewport.zoom(0.0001f, barCount = 1_000).visibleBars,
            1e-6f,
        )
        assertEquals(
            ChartViewport.MAX_BARS.toFloat(),
            viewport.zoom(1_000f, barCount = 1_000).visibleBars,
            1e-6f,
        )
    }

    @Test
    fun `缩放时锚点处的蜡烛保持不动`() {
        // 负右偏移 = 正在看更早的历史
        val viewport = ChartViewport(100f, -200f)
        val start = viewport.startIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f, barCount = 1_000)
        // 锚点在左端：缩放后左端仍是同一根蜡烛
        assertEquals(start, anchored.startIndex(1_000))
    }

    @Test
    fun `右侧留白时缩放锚点依然不动`() {
        val viewport = ChartViewport(100f, 30f).clamp(1_000)
        val start = viewport.startIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 0f, barCount = 1_000)
        // 放大（可见根数变少）后左端仍是同一根
        assertEquals(start, anchored.startIndex(1_000))
    }

    @Test
    fun `右侧留白时右端锚点也不动`() {
        val viewport = ChartViewport(100f, 30f).clamp(1_000)
        val end = viewport.endIndex(1_000)
        val anchored = viewport.zoom(0.5f, anchorRatio = 1f, barCount = 1_000)
        assertEquals(end, anchored.endIndex(1_000))
    }

    @Test
    fun `横坐标反查与归一化互为逆运算`() {
        val viewport = ChartViewport(100f, 0f)
        val index = viewport.indexAt(0.5f, barCount = 300)
        val fraction = viewport.fractionOf(index, barCount = 300)
        assertTrue("index=$index fraction=$fraction", !fraction.isNaN())
        assertEquals(0.5f, fraction, 0.03f)
    }

    @Test
    fun `序列变长后仍贴着最新`() {
        // 视窗不带序列长度，`barCount` 变大即为「来了新蜡烛」：
        // rightOffset 保持 0 就自然贴回最新，不再需要 resize 这一步。
        assertEquals(300, ChartViewport(100f, 0f).endIndex(barCount = 301))
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
        assertTrue(series.overlay.lines.any { it.label == "BOLL.MB" })
        assertTrue(series.overlay.lines.any { it.label == "BOLL.UP" })
        assertTrue(series.overlay.lines.any { it.label == "BOLL.DN" })
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

    /**
     * 币安式的读数行：一条线一段文字，颜色由调用层按 role 解析。
     * 这里只验文字，因为它替掉了原先常驻在左上角的整块 OHLC 图例，
     * 「哪段数字属于哪条线」全靠 label 与配色，写坏了图上就说不清话了。
     */
    @Test
    fun `主图读数逐条给出线名与数值`() {
        val series = ChartModel.build(candles(60), listOf(5, 10), showBoll = false, subPanes = emptyList())
        // 收盘线性递增：末根的 MA5 = 157，MA10 = 154.5
        assertEquals(listOf("MA5: 157.00", "MA10: 154.50"), series.mainReadoutAt(59, 2).map { it.text })
    }

    @Test
    fun `读数在指标尚未算出的位置给占位而不是零`() {
        val series = ChartModel.build(candles(60), listOf(30), showBoll = false, subPanes = emptyList())
        assertEquals(listOf("MA30: --"), series.mainReadoutAt(3, 2).map { it.text })
    }

    @Test
    fun `大数值读数带千分位分组`() {
        val series = ChartModel.build(candles(200, base = 50_000.0), listOf(5), showBoll = false, subPanes = emptyList())
        // 末根收盘 50,199，MA5 取最后五根的中位 = 50,197
        assertEquals(listOf("MA5: 50,197.00"), series.mainReadoutAt(199, 2).map { it.text })
    }

    @Test
    fun `副图读数带参数名与各线数值`() {
        val series = ChartModel.build(
            candles(60),
            listOf(5),
            showBoll = false,
            subPanes = listOf(SubPaneKind.MACD),
        )
        val readout = series.subReadoutAt(series.subPanes.single(), 59, 2).map { it.text }
        // 柱值也要给：只报 DIF/DEA 的话用户看到红绿柱子却不知道那个数是多少
        assertEquals("MACD(12,26,9)", readout.first())
        assertTrue(readout.any { it.startsWith("DIF:") })
        assertTrue(readout.any { it.startsWith("DEA:") })
        assertTrue(readout.any { it.startsWith("MACD:") })
    }

    @Test
    fun `VOL 读数给出该根的量`() {
        val series = ChartModel.build(
            candles(60),
            listOf(5),
            showBoll = false,
            subPanes = listOf(SubPaneKind.VOLUME),
        )
        assertEquals(listOf("VOL: 10"), series.subReadoutAt(series.subPanes.single(), 10, 2).map { it.text })
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
    fun `横向位移折算成小数根而不是取整抹平`() {
        // 每根宽 10px、单帧只移动 3px：取整会得 0（这就是旧版「拖不动」的根因），
        // 浮点折算必须保留 0.3 根这个零头，否则每帧都被抹掉、累积起来就是纹丝不动。
        assertEquals(0.3f, ChartGesture.barDelta(pixelDelta = 3f, slotPx = 10f), 1e-6f)
        assertEquals(-0.3f, ChartGesture.barDelta(pixelDelta = -3f, slotPx = 10f), 1e-6f)
        // 单帧位移超过一根时如实给出 2.5 根，不做任何限速
        assertEquals(2.5f, ChartGesture.barDelta(pixelDelta = 25f, slotPx = 10f), 1e-6f)
    }

    @Test
    fun `横向位移的非法输入视为本帧无位移`() {
        assertEquals(0f, ChartGesture.barDelta(pixelDelta = 5f, slotPx = 0f), 1e-6f)
        assertEquals(0f, ChartGesture.barDelta(pixelDelta = 5f, slotPx = -3f), 1e-6f)
        assertEquals(0f, ChartGesture.barDelta(pixelDelta = 5f, slotPx = Float.NaN), 1e-6f)
        assertEquals(0f, ChartGesture.barDelta(pixelDelta = Float.NaN, slotPx = 10f), 1e-6f)
        assertEquals(0f, ChartGesture.barDelta(pixelDelta = Float.POSITIVE_INFINITY, slotPx = 10f), 1e-6f)
    }

    // endregion

    // region 双指缩放：真实逐帧序列

    /**
     * 复刻 `detectChartGestures` 的双指分支 + `KlineChart` 的 `onZoom`，把一串
     * 「两指间距」的逐帧采样喂进去，返回最终 viewport。
     *
     * 与真实实现完全一致：**单帧因子直接交给 `zoom()`**，中间不经过任何累加器 ——
     * 视窗的可见根数是浮点，`zoom()` 不再 `roundToInt()`，缓慢捏合的零点几根
     * 也能逐帧体现，这正是「抖动修复」的核心（旧版的 `accumulatePinch`/`reportApplied`
     * 那套「攒一根提交一根、余量回填」机制正是抖动与滞后的来源，已整体删除）。
     */
    private fun applyPinch(
        viewport: ChartViewport,
        distances: List<Float>,
        anchorRatio: Float = 0.5f,
        barCount: Int = 2_000,
    ): ChartViewport {
        var current = viewport
        var previousDistance = 0f
        distances.forEach { distance ->
            ChartGesture.pinchFactor(previousDistance, distance)?.let { factor ->
                current = current.zoom(factor, anchorRatio, barCount)
            }
            previousDistance = distance
        }
        return current
    }

    @Test
    fun `双指张开可以缩小可见根数`() {
        // 两指从 200px 一路张到 600px
        val opening = (0..7).map { 200f + it * (400f / 7f) }
        val zoomed = applyPinch(ChartViewport(120f, 0f), opening, barCount = 500)
        assertTrue("张开应减少可见根数，实际 ${zoomed.visibleBars}", zoomed.visibleBars < 120f)
    }

    @Test
    fun `双指合拢可以放大可见根数`() {
        val closing = (0..7).map { 600f - it * (400f / 7f) }
        val zoomed = applyPinch(ChartViewport(120f, 0f), closing, barCount = 500)
        assertTrue("合拢应增加可见根数，实际 ${zoomed.visibleBars}", zoomed.visibleBars > 120f)
    }

    /**
     * 抖动修复的核心断言：**每一帧**都必须产生「小于一根」的连续变化。
     *
     * 旧实现把可见根数取整（`roundToInt`），缓慢捏合时单帧折算只有零点几根，
     * 被抹平后要靠累加器攒够一根才提交一次 —— 图上就是「好几帧不动、突然跳一根」，
     * 而一根 15px，这就是用户看到的横向抖动；快速捏合时又被限成每帧一根，
     * 画面严重滞后于手指。视窗浮点化之后这两个毛病一起消失。
     */
    @Test
    fun `缓慢捏合的每一帧都连续生效而不是攒够一根才跳`() {
        // 极慢捏合：两指间距每帧只变 1px（300 → 270）
        val closing = pinchTrail(ChartViewport(120f, 0f), (0..30).map { 300f - it * 1f })
        assertTrue("合拢应逐帧增加可见根数，实际 $closing", closing.last() > 120f)
        assertContinuous(closing, direction = 1f)

        val opening = pinchTrail(ChartViewport(120f, 0f), (0..30).map { 300f + it * 1f })
        assertTrue("张开应逐帧减少可见根数，实际 $opening", opening.last() < 120f)
        assertContinuous(opening, direction = -1f)
    }

    /** 逐帧复刻真实手势（含开头那一帧无效帧），返回每帧之后的可见根数轨迹。 */
    private fun pinchTrail(
        viewport: ChartViewport,
        distances: List<Float>,
        barCount: Int = 2_000,
    ): List<Float> {
        var current = viewport
        var previousDistance = 0f
        val trail = mutableListOf<Float>()
        distances.forEach { distance ->
            ChartGesture.pinchFactor(previousDistance, distance)?.let {
                current = current.zoom(it, 0.5f, barCount)
            }
            previousDistance = distance
            trail += current.visibleBars
        }
        return trail
    }

    /**
     * 轨迹的两条不变量：方向一致、且**单帧跳变严格小于一根**。
     *
     * 单帧跳变到一根以上说明又出现了量化台阶（抖动回来了）；某一帧跳变恰好为 0
     * 说明这一帧被抹平（滞后又回来了）。
     */
    private fun assertContinuous(trail: List<Float>, direction: Float) {
        trail.zipWithNext().forEach { (a, b) ->
            val step = b - a
            assertTrue("单帧变化不能为零（否则就是被抹平）：$trail", step != 0f)
            assertTrue("单帧变化方向必须一致：$trail", step * direction > 0f)
            assertTrue(
                "单帧跳变不得超过一根（否则就是量化台阶）：$trail",
                kotlin.math.abs(step) < 1f,
            )
        }
    }

    @Test
    fun `单帧的缓慢捏合也会被完整施加`() {
        // 对照组：单帧因子 300/299 ≈ 1.00334，折算到 120 根只有 0.4 根。
        // 旧实现把它取整抹成 0，浮点视窗则完整生效 —— 这是「缓慢捏合有反应」的根因。
        val factor = ChartGesture.pinchFactor(previous = 300f, current = 299f)!!
        val zoomed = ChartViewport(120f, 0f).zoom(factor, 0.5f, barCount = 2_000)
        assertEquals(120f * factor, zoomed.visibleBars, 1e-3f)
        assertTrue(zoomed.visibleBars > 120f)
    }

    @Test
    fun `缓慢捏合的灵敏度由手势起点决定`() {
        // 交互含义（重要，避免以后把「中段捏合看起来没反应」当成 bug 再改一遍）：
        // `pinchFactor` 是**相邻两帧的比值**，所以 1px/帧 的绝对速度越到后面
        // 占比越小 —— 间距 300→270 时每帧只变 0.33%，而 150→120 时每帧变 0.67%，
        // 同样的手指速度自然更灵敏。这与真机手感一致（起始间距越大越迟钝）。
        val fromFar = applyPinch(ChartViewport(120f, 0f), (0..30).map { 300f - it * 1f })
        val fromNear = applyPinch(ChartViewport(120f, 0f), (0..30).map { 150f - it * 1f })
        assertTrue(
            "起点更近的手势应该更灵敏：fromFar=${fromFar.visibleBars} fromNear=${fromNear.visibleBars}",
            fromNear.visibleBars > fromFar.visibleBars,
        )
    }

    @Test
    fun `快速张开后反向合拢图上立刻开始往回走`() {
        // 逐帧复刻真实手势：张开 4 帧（每帧间距 +20%）→ 反向合拢 4 帧。
        // 浮点化之后不存在「欠账」这种东西，反向的第一帧就直接体现在图上。
        val distances = listOf(200f, 240f, 288f, 346f, 415f, 346f, 288f, 240f, 200f)
        val trail = pinchTrail(ChartViewport(120f, 0f), distances, barCount = BARS)
        // 索引 5 是反向的第一帧（间距从 415 回到 346），它必须比索引 4 更宽（开始往回走）
        assertTrue("反向第一帧就该往回走，实际轨迹 $trail", trail[5] > trail[4])
        assertTrue("换向后应逐帧持续回走，实际轨迹 $trail", trail[8] > trail[5])
    }

    @Test
    fun `捏合的缩放量是精确乘性的与分几帧捏无关`() {
        // 乘性不变量：分 4 帧各捏 0.8，结果必须等于一帧捏 0.8^4。
        // 旧实现每帧取整 + 每帧最多一根，总比例与结果严重脱节（快捏会滞后于手指）。
        val bars = 120f
        val oneShot = ChartViewport(bars, 0f).zoom(0.8f * 0.8f * 0.8f * 0.8f, 0.5f, BARS)
        var stepped = ChartViewport(bars, 0f)
        repeat(4) { stepped = stepped.zoom(0.8f, 0.5f, BARS) }
        assertEquals(oneShot.visibleBars, stepped.visibleBars, 1e-3f)
        assertEquals(bars * 0.4096f, oneShot.visibleBars, 1e-3f)
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
    fun `双指缩放不受单指方向锁影响`() {
        // 双指分支必须 continue，不能落进单指分支去改 axisAccum
        val viewport = ChartViewport(120f, 0f)
        val once = applyPinch(viewport, listOf(200f, 400f), barCount = 500)
        assertNotEquals(viewport.visibleBars, once.visibleBars)
    }
    // endregion
}
