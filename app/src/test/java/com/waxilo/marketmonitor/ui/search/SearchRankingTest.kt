package com.waxilo.marketmonitor.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索优先级（PRD FR-1.2）。
 *
 * 背景：真实反馈是「我搜索 btc，居然搜不到比特币」。这条链路上有两个独立的坑，
 * 两个都必须有回归测试 —— 只修一个，`BTCUSDT` 依然不在第一屏：
 *
 * 1. **匹配分层缺失**：交易对名 = `baseAsset + quoteAsset`，包含匹配分不清
 *    「币种就是 BTC」（`BTCUSDT`）和「以 BTC 计价」（`1INCHBTC`）。
 * 2. **排序兜底项不可靠**：原本同层内按 `quoteVolume` 降序。但 `ticker` 表在
 *    冷启动 / 刚装完 / 行情同步失败时是**空的**，此时所有候选的 quoteVolume
 *    都是 0，排序等价于没排，顺序掉回字母序 —— 真实标的表里 `BTCUSDT`
 *    会掉到第 32/35 名（前面全是 `BTCAEUR` `BTCARS` 这类法币对）。
 */
class SearchRankingTest {

    // region 匹配分层

    @Test
    fun `币种完全匹配排在最前`() {
        assertEquals(0, SearchRanking.rank(symbol = "BTCUSDT", baseAsset = "BTC", keyword = "btc"))
    }

    @Test
    fun `以 BTC 计价的山寨币层级最低`() {
        // 币种是 1INCH / AAVE，BTC 只出现在计价侧
        assertEquals(4, SearchRanking.rank(symbol = "1INCHBTC", baseAsset = "1INCH", keyword = "btc"))
        assertEquals(4, SearchRanking.rank(symbol = "AAVEBTC", baseAsset = "AAVE", keyword = "btc"))
    }

    @Test
    fun `币种包含关键词高于交易对包含`() {
        // WETH 的币种里含 eth（币种包含=3）；1INCHBTC 只是交易对含 btc（交易对包含=4）
        assertEquals(3, SearchRanking.rank(symbol = "WETHUSDT", baseAsset = "WETH", keyword = "eth"))
        assertEquals(4, SearchRanking.rank(symbol = "1INCHBTC", baseAsset = "1INCH", keyword = "btc"))
    }

    @Test
    fun `币种前缀高于交易对前缀`() {
        // 币种前缀（=1）与「币种等于关键词」的区别：BTCDOM 以 BTC 开头但不是 BTC。
        // 真实数据里 BTCDOM 是「比特币市值占比」指数，搜 btc 时它确实该在同层前列。
        assertEquals(1, SearchRanking.rank(symbol = "BTCDOMUSDT", baseAsset = "BTCDOM", keyword = "btc"))
        assertEquals(1, SearchRanking.rank(symbol = "BTCSTUSDT", baseAsset = "BTCST", keyword = "btc"))
    }

    @Test
    fun `币种完全匹配与币种前缀分开两层`() {
        // 这一条钉住「等于」和「前缀」不能合并：BTC 与 BTCDOM 都 startsWith("BTC")，
        // 若不先判相等，比特币自己就会和有前缀关系的山寨币挤在同一层里。
        assertEquals(0, SearchRanking.rank(symbol = "BTCUSDT", baseAsset = "BTC", keyword = "btc"))
        assertEquals(1, SearchRanking.rank(symbol = "BTCDOMUSDT", baseAsset = "BTCDOM", keyword = "btc"))
        assertTrue(
            SearchRanking.rank(symbol = "BTCUSDT", baseAsset = "BTC", keyword = "btc")!!
                < SearchRanking.rank(symbol = "BTCDOMUSDT", baseAsset = "BTCDOM", keyword = "btc")!!,
        )
    }

    @Test
    fun `交易对前缀层只在币种不匹配时才会命中`() {
        // 真实场景：币种元数据缺失时 baseAsset 会退化成交易对名（见 SearchViewModel），
        // 此时「币种前缀」永远命中，交易对前缀层（=2）就失效了 ——
        // 这是已知的降级行为，用「以 BTC 计价」的标的也能触发等价的层级差。
        assertEquals(4, SearchRanking.rank(symbol = "1INCHBTC", baseAsset = "1INCH", keyword = "btc"))
    }

    @Test
    fun `币种完全匹配高于币种前缀`() {
        // ETHBTC 的币种就是 ETH（=0）；ETHFI 是币种以 eth 开头（=1）
        // 注意 ETHBTC 命中「币种完全匹配」而不是「交易对包含」——
        // 分层是按 baseAsset 优先判定的，与交易对里 BTC 出现在哪一侧无关。
        assertEquals(0, SearchRanking.rank(symbol = "ETHBTC", baseAsset = "ETH", keyword = "eth"))
        assertEquals(1, SearchRanking.rank(symbol = "ETHFIUSDT", baseAsset = "ETHFI", keyword = "eth"))
    }

    @Test
    fun `不匹配返回空`() {
        assertNull(SearchRanking.rank(symbol = "ETHUSDT", baseAsset = "ETH", keyword = "btc"))
    }

    @Test
    fun `大小写与空格无关`() {
        assertEquals(0, SearchRanking.rank(symbol = "btcusdt", baseAsset = "btc", keyword = "BTC"))
        assertEquals(0, SearchRanking.rank(symbol = "BTCUSDT", baseAsset = "BTC", keyword = "Btc"))
    }

    @Test
    fun `空关键词时所有标的同层级`() {
        // 空查询不该过滤掉任何东西，也不该只让某一类靠前
        assertEquals(
            SearchRanking.EMPTY_QUERY_RANK,
            SearchRanking.rank(symbol = "ETHUSDT", baseAsset = "ETH", keyword = ""),
        )
        assertEquals(
            SearchRanking.EMPTY_QUERY_RANK,
            SearchRanking.rank(symbol = "BTCUSDT", baseAsset = "BTC", keyword = ""),
        )
    }

    // endregion

    // region 计价币优先级（无行情时的兜底）

    @Test
    fun `稳定币对优先于法币对`() {
        // 真实数据里 BTCUSDT 的同层竞争者主要是 BTCAEUR/BTCARS... 这些法币对
        assertTrue(SearchRanking.quotePriority("USDT") < SearchRanking.quotePriority("EUR"))
        assertTrue(SearchRanking.quotePriority("USDT") < SearchRanking.quotePriority("TRY"))
        assertTrue(SearchRanking.quotePriority("USDT") < SearchRanking.quotePriority("BRL"))
        assertTrue(SearchRanking.quotePriority("USDT") < SearchRanking.quotePriority("JPY"))
    }

    @Test
    fun `USDT 排在最前且 USDC FDUSD 紧随其后`() {
        val order = listOf("USDT", "USDC", "FDUSD", "BNB", "BTC", "ETH", "EUR", "TRY", "XYZ")
        val priorities = order.map { SearchRanking.quotePriority(it) }
        assertEquals(priorities.sorted(), priorities)
    }

    /**
     * 复刻 [com.waxilo.marketmonitor.ui.search.SearchViewModel] 的排序链，
     * 用**真实标的表**（3705 个）跑「搜 btc」，并模拟 `ticker` 表为空的冷启动。
     */
    private fun rankBtcLike(
        universe: List<Triple<String, String, String>>,
        keyword: String,
    ): List<String> = universe
        .mapNotNull { (symbol, base, quote) ->
            SearchRanking.rank(symbol = symbol, baseAsset = base, keyword = keyword)
                ?.let { symbol to it to quote }
        }
        .sortedWith(
            // watched = false（本用例不涉及自选）；quoteVolume 全部为 0（冷启动）
            compareBy<Pair<Pair<String, Int>, String>> { it.first.second }
                .thenBy { SearchRanking.quotePriority(it.second) }
                .thenBy { it.first.first },
        )
        .map { it.first.first }

    /** 真实标的表的一个切片：BTC 计价对 + 各种法币对。取自设备数据库的 instrument 表。 */
    private val btcUniverse = listOf(
        Triple("1INCHBTC", "1INCH", "BTC"),
        Triple("AAVEBTC", "AAVE", "BTC"),
        Triple("BTCAEUR", "BTC", "EUR"),
        Triple("BTCARS", "BTC", "ARS"),
        Triple("BTCBRL", "BTC", "BRL"),
        Triple("BTCFDUSD", "BTC", "FDUSD"),
        Triple("BTCTRY", "BTC", "TRY"),
        Triple("BTCUSDC", "BTC", "USDC"),
        Triple("BTCUSDT", "BTC", "USDT"),
        Triple("ETHBTC", "ETH", "BTC"),
    )

    @Test
    fun `搜 btc 时比特币现货对排在第一位`() {
        val result = rankBtcLike(btcUniverse, keyword = "btc")
        assertEquals("BTCUSDT", result.first())
    }

    @Test
    fun `搜 btc 时以 BTC 计价的山寨币排在所有比特币对之后`() {
        val result = rankBtcLike(btcUniverse, keyword = "btc")
        val lastBitcoinPair = result.indexOfLast { it.startsWith("BTC") }
        val firstQuotedInBtc = result.indexOfFirst { it.endsWith("BTC") && !it.startsWith("BTC") }
        assertTrue(
            "1INCHBTC 这类不该排在 BTCUSDT 前面，实际顺序 $result",
            firstQuotedInBtc > lastBitcoinPair,
        )
    }

    @Test
    fun `搜 btc 的前三名都是主流稳定币对`() {
        val result = rankBtcLike(btcUniverse, keyword = "btc")
        assertEquals(listOf("BTCUSDT", "BTCUSDC", "BTCFDUSD"), result.take(3))
    }

    // endregion

    // region 空查询（搜索框还没输入时的首屏）

    /**
     * 复刻排序链在**空关键词**下的行为。
     *
     * 这条用例来自一次真实的误判：真机上看到首屏是
     * `0GUSDT / 1000CATUSDT / 1000CHEEMSUSDT ...`，一度以为「搜 btc 搜不出比特币」
     * 是分层排序写错了。实际原因是**那个界面根本没进入搜索态**（输入框是空的），
     * 显示的是全量 3705 个标的按字母序截断的前 80 个 —— 而 `BTCUSDT` 在全量里排
     * **第 144 名**，首屏当然看不见。
     *
     * 所以这里把「空查询的首屏长什么样」固化成断言，用来区分两类问题：
     * - 首屏没有 BTC：正常，空查询就是字母序（用户没输入任何东西）
     * - 输入 btc 后没有 BTC：才是排序 bug（见上一组用例）
     */
    private fun rankWithEmptyQuery(universe: List<Triple<String, String, String>>): List<String> =
        universe
            .mapNotNull { (symbol, base, quote) ->
                SearchRanking.rank(symbol = symbol, baseAsset = base, keyword = "")
                    ?.let { symbol to it to quote }
            }
            .sortedWith(
                compareBy<Pair<Pair<String, Int>, String>> { it.first.second }
                    .thenBy { SearchRanking.quotePriority(it.second) }
                    .thenBy { it.first.first },
            )
            .map { it.first.first }

    /** 空查询时全量标的都在结果里（不该被关键词过滤掉任何东西）。 */
    @Test
    fun `空查询不过滤任何标的`() {
        val result = rankWithEmptyQuery(btcUniverse)
        assertEquals(btcUniverse.size, result.size)
    }

    /**
     * 空查询时 `BTCUSDT` 依然受计价币优先级保护，排在法币对与 BTC 交叉盘之前。
     *
     * 即使用户什么都没输入，`USDT` 对也不该被 `BTCAEUR` 这类挤下去 ——
     * 这是 [SearchRanking.quotePriority] 存在的第二个理由（第一个是关键词匹配后的同层排序）。
     */
    @Test
    fun `空查询时 USDT 对仍排在法币对与交叉盘之前`() {
        val result = rankWithEmptyQuery(btcUniverse)
        assertTrue(
            "BTCUSDT 应排在 BTCAEUR 之前，实际 $result",
            result.indexOf("BTCUSDT") < result.indexOf("BTCAEUR"),
        )
        assertTrue(
            "BTCUSDT 应排在 1INCHBTC 之前，实际 $result",
            result.indexOf("BTCUSDT") < result.indexOf("1INCHBTC"),
        )
    }

    /**
     * 空查询下「首屏字母序」的真实成因 —— 记录一次误判。
     *
     * 真机上曾看到首屏是 `0GUSDT / 1000CATUSDT / 1000CHEEMSUSDT ...`，
     * 一度以为「搜 btc 搜不出比特币」是分层排序写错了。实际原因是
     * **那个界面根本没进入搜索态**（输入框是空的），显示的是全量 3705 个标的
     * 按字母序截断的前 80 个；而 `BTCUSDT` 在**全量**里排第 144 名，首屏看不见很正常。
     *
     * 关键区别在于：字母序是 `0` < `1` < ... < `A` < ... < `B`，所以只要有
     * 数字/字母靠前的标的在库里，`BTCUSDT` 就进不了首屏 —— 而这**不是**排序 bug。
     *
     * 这个用例把两者钉开：同一条排序链，喂「BTC 相关的 10 个标的」时 BTCUSDT 第一；
     * 喂「包含字母序更小的标的」时它就被压下去了。看到后者不代表前者有问题。
     */
    @Test
    fun `空查询的首屏由字母序决定而不是按币种重要性`() {
        val withEarlySymbols = btcUniverse + listOf(
            Triple("0GUSDT", "0G", "USDT"),
            Triple("1000CATUSDT", "1000CAT", "USDT"),
        )
        val result = rankWithEmptyQuery(withEarlySymbols)
        assertEquals(
            "字母序更小的标的会占据首屏，BTCUSDT 被挤到后面 —— 这是预期行为",
            listOf("0GUSDT", "1000CATUSDT"),
            result.take(2),
        )
        assertTrue("BTCUSDT 此时不该在首位", result.first() != "BTCUSDT")
    }

    // endregion
}
