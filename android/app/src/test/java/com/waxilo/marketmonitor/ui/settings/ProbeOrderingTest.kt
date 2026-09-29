package com.waxilo.marketmonitor.ui.settings

import com.waxilo.marketmonitor.domain.model.FuturesDialect
import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接口弹窗的排序规则。
 *
 * 背景是真实反馈：「我明明在最上面，测速排序后还得往上滑才能看到」。
 * 那条反馈里有两件事，这里只负责**排序本身**（顺序对不对），视口回顶由
 * `FuturesSourcePickerDialog` 里的 `pinToTop` 负责（Compose 的 LazyColumn 滚动
 * 行为，纯 JVM 单测测不到）：
 *
 * 如果顺序本身错了（比如未检测的被排到通了的前面），那再怎么回顶也白搭 ——
 * 所以这里把顺序的三条性质钉死。
 */
class ProbeOrderingTest {

    private fun endpoint(label: String, url: String) =
        FuturesEndpoint(label, url, note = "", dialect = FuturesDialect.BINANCE)

    // 故意用一个「原始顺序」跟任何排序结果都不相同的清单，免得断言被巧合绿掉
    private val all = listOf(
        endpoint("A", "https://a.example"),
        endpoint("B", "https://b.example"),
        endpoint("C", "https://c.example"),
        endpoint("D", "https://d.example"),
    )

    private fun labels(list: List<FuturesEndpoint>) = list.map { it.label }

    @Test
    fun `通的按延迟升序排在最前面`() {
        val ordered = ProbeOrdering.of(
            all,
            mapOf(
                "https://a.example" to ProbeOutcome.Reachable(300),
                "https://b.example" to ProbeOutcome.Reachable(90),
                "https://c.example" to ProbeOutcome.Reachable(120),
            ),
        )
        // B(90) → C(120) → A(300) → D(未检测)
        assertEquals(listOf("B", "C", "A", "D"), labels(ordered))
    }

    @Test
    fun `延迟相同时保持清单原始顺序`() {
        // 排序必须稳定：否则每次重排顺序都在变，用户刚瞄到的那条就跑了
        val ordered = ProbeOrdering.of(
            all,
            mapOf(
                "https://c.example" to ProbeOutcome.Reachable(100),
                "https://a.example" to ProbeOutcome.Reachable(100),
                "https://d.example" to ProbeOutcome.Reachable(100),
            ),
        )
        assertEquals(listOf("A", "C", "D", "B"), labels(ordered))
    }

    @Test
    fun `连不上的排在通的之后 未检测的之前`() {
        val ordered = ProbeOrdering.of(
            all,
            mapOf(
                "https://a.example" to ProbeOutcome.Failed("超时"),
                "https://b.example" to ProbeOutcome.Reachable(50),
                "https://c.example" to ProbeOutcome.Failed("DNS"),
            ),
        )
        // 通了的最前；两条失败的按原始顺序（A 在 C 前）；没结论的 D 垫底
        assertEquals(listOf("B", "A", "C", "D"), labels(ordered))
    }

    @Test
    fun `一条结果都还没回来时顺序保持原样`() {
        // 弹窗打开到第一条结果返回之间：这时如果顺序乱跳，用户会以为列表抽风
        assertEquals(labels(all), labels(ProbeOrdering.of(all, emptyMap())))
    }

    @Test
    fun `全部都能连上时第一条就是最快的`() {
        // 这个弹窗存在的意义就是「挑最快的」，所以第一名必须是最小延迟那条
        val ordered = ProbeOrdering.of(
            all,
            mapOf(
                "https://a.example" to ProbeOutcome.Reachable(420),
                "https://b.example" to ProbeOutcome.Reachable(180),
                "https://c.example" to ProbeOutcome.Reachable(260),
                "https://d.example" to ProbeOutcome.Reachable(95),
            ),
        )
        assertEquals("D", ordered.first().label)
        assertEquals(listOf("D", "B", "C", "A"), labels(ordered))
    }

    @Test
    fun `逐条回填的过程中快的会一路浮上来`() {
        // 真实节奏：12 条结果先后到达，每到达一条就重排一次。
        // 这里模拟「最慢的先回来、最快的最后回来」，确认第一名的位置跟着结论走。
        var results = mapOf<String, ProbeOutcome>("https://a.example" to ProbeOutcome.Reachable(500))
        assertEquals("A", ProbeOrdering.of(all, results).first().label)

        results = results + ("https://c.example" to ProbeOutcome.Reachable(200))
        assertEquals("C", ProbeOrdering.of(all, results).first().label)

        results = results + ("https://b.example" to ProbeOutcome.Reachable(30))
        assertEquals("B", ProbeOrdering.of(all, results).first().label)
    }

    @Test
    fun `清单里出现表里没有的域也不会崩`() {
        // 防御：结果表的 key 来自探测，理论上都在 ALL 里；真多出来一条也不该影响顺序
        val ordered = ProbeOrdering.of(
            all,
            mapOf(
                "https://a.example" to ProbeOutcome.Reachable(10),
                "https://ghost.example" to ProbeOutcome.Reachable(1),
            ),
        )
        assertEquals(listOf("A", "B", "C", "D"), labels(ordered))
    }
}
