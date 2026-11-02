package com.waxilo.marketmonitor.domain.model

/**
 * 现货行情来源（PRD 3.2）。
 *
 * 与合约不同，现货**不做本机测速**：两家真正的区别只有「当前网络能不能直连」，
 * 移动网络下这一点尤其明显 —— 大陆蜂窝网络通常连不上币安，而 Gate 一般可直连。
 * 用户按自己的网络常识点一下即可，没必要为两个选项再打一轮探测。
 *
 * 出厂仍是币安（与桌面端同口径）：`data-api.binance.vision` 优先，再退官方镜像。
 * 这些域共享同一个 IP 限频池，所以这层只解决可达性，不解决限频；现货与合约是
 * **两条独立**的限频 / 封禁线，合约被限不影响这里。
 *
 * ❗内部交易对一律使用**币安写法**（`BTCUSDT`）作为稳定键：自选、预警、K 线缓存都以
 * 它落库。Gate 的 `BTC_USDT` 只在网络层进出时换算（见 `SpotSymbols`），切换数据源
 * 不会把用户已有的自选 / 预警全部作废 —— 这是与桌面端（内部用 Gate 写法）不同的取舍，
 * 因为 App 的存量数据本来就是币安写法。
 */
enum class SpotSource(
    /** 持久化用的稳定标识，不可改名。 */
    val key: String,
    /** 展示名。 */
    val label: String,
    /** 依次尝试的 REST 主机：前一个失败（网络 / 5xx / 网关类状态）才试下一个。 */
    val hosts: List<String>,
    /** 一行补充说明（可达性预期）。 */
    val note: String,
) {
    BINANCE(
        key = "BINANCE",
        label = "币安现货",
        hosts = listOf(
            "https://data-api.binance.vision",
            "https://api1.binance.com",
            "https://api2.binance.com",
            "https://api3.binance.com",
            "https://api4.binance.com",
            "https://api.binance.com",
        ),
        note = "data-api 镜像优先 · 与合约限频/封禁分开计",
    ),
    GATE(
        key = "GATE",
        label = "Gate 现货",
        hosts = listOf("https://api.gateio.ws"),
        note = "大陆通常可直连 · 移动网络下更稳",
    ),
    ;

    /** 设置页展示用的简短主机名（去掉协议）。 */
    val primaryHost: String get() = hosts.first().removePrefix("https://")

    companion object {
        val DEFAULT: SpotSource = BINANCE

        /** 规范化存储值：未知或空值回落到出厂源。 */
        fun normalize(raw: String?): SpotSource {
            val key = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }
}
