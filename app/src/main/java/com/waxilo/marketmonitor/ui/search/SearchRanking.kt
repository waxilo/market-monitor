package com.waxilo.marketmonitor.ui.search

/**
 * 搜索匹配的优先级判定（PRD FR-1.2）。
 *
 * 抽成纯函数是为了能在 JVM 上直接断言：这条逻辑错了表现为
 * 「搜 btc 搜不到比特币」这种一眼看不出原因的结果 —— 列表明明有几十条，
 * 但都不是用户要找的那个。留在 ViewModel 里只能靠真机手点，回归了也不知道。
 */
object SearchRanking {

    /**
     * 匹配质量分层，数字越小越靠前；返回 `null` 表示不匹配。
     *
     * 为什么不能「包含即命中」：交易对名 = `baseAsset + quoteAsset`，
     * 搜 "btc" 会同时命中两类完全不同的东西：
     * - **币种就是 BTC**：`BTCUSDT`（用户想要的）
     * - **以 BTC 计价**：`1INCHBTC` / `AAVEBTC` / `ETHBTC`（几百个，币种本身不是 BTC）
     *
     * 若一视同仁按名字排，`1INCHBTC` 因为字母序靠前会排在 `BTCUSDT` 上面，
     * 用户翻半天也找不到比特币。所以必须按**匹配位置**分层。
     *
     * 分层规则（贴合「搜什么先给什么」的直觉）：
     * - `0` 币种完全等于关键词：`btc` → BTC
     * - `1` 币种以关键词开头：`btc` → BTCDOM
     * - `2` 交易对以关键词开头：`btc` → BTCUSDT、BTCFDUSD
     * - `3` 币种包含关键词：`eth` → WETH
     * - `4` 交易对包含关键词：`btc` → 1INCHBTC（BTC 只出现在计价侧，最低优先级）
     */
    fun rank(symbol: String, baseAsset: String, keyword: String): Int? {
        if (keyword.isEmpty()) return EMPTY_QUERY_RANK
        val k = keyword.uppercase()
        val base = baseAsset.uppercase()
        val sym = symbol.uppercase()
        return when {
            base == k -> 0
            base.startsWith(k) -> 1
            sym.startsWith(k) -> 2
            base.contains(k) -> 3
            sym.contains(k) -> 4
            else -> null
        }
    }

    /**
     * 计价币的优先级，数字越小越靠前；返回 `null` 表示「不在主流行列」（排在后面）。
     *
     * 这是分层之后的**第二道排序**，缺了它「搜 btc 找不到比特币」只解决一半：
     * 币种匹配上之后，`BTCUSDT / BTCFDUSD / BTCTRY / BTCBRL ...` 会挤在同一层里。
     *
     * 关键约束（不能用 `quoteVolume` 代替）：`ticker` 表在**冷启动、刚装完、或
     * 行情同步失败**时是空的，此时所有候选的 `quoteVolume` 都是 `BigDecimal.ZERO`，
     * 按成交量排序完全等价于「不排序」—— 顺序会回落到字母序。
     * 实测真实标的表：字母序下 `BTCUSDT` 在第 **32/35** 名（前面全是 `BTCAEUR`
     * `BTCARS` 这类法币对），用户在第一屏根本看不到它。
     *
     * 所以这里给「真实交易最主要的计价币」一个**与行情无关**的稳定优先级：
     * 稳定币在最前（USDT/USDC/FDUSD 是绝大多数人的实际交易对），
     * 其次是 BTC/ETH 两个交叉盘，再是其它法币。
     */
    fun quotePriority(quoteAsset: String): Int = when (quoteAsset.uppercase()) {
        "USDT" -> 0
        "USDC" -> 1
        "FDUSD" -> 2
        "TUSD" -> 3
        "BNB" -> 4
        "BTC" -> 5
        "ETH" -> 6
        "EUR" -> 7
        "TRY" -> 8
        else -> 9
    }

    /**
     * 空关键词（刚进搜索页）时所有标的同分。
     *
     * 用最低层级而不是 `null`：空查询不该过滤掉任何东西，
     * 此时排序完全交给后面的「自选置顶 + 计价币 + 成交额」。
     */
    const val EMPTY_QUERY_RANK = 4
}
