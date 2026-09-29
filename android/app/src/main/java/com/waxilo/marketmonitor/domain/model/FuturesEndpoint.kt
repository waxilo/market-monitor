package com.waxilo.marketmonitor.domain.model

/**
 * 内置的永续合约行情候选接口（PRD 3.2）。
 *
 * 域名容灾不再靠每次请求逐域回退试探：候选清单固定打包进 App，
 * 设置页「合约行情接口」弹窗在**用户本机**并行实测各接口的连通性与延迟，由用户点选一个使用。
 * 网络环境因地区/代理而异，开发机上的探测结果不作数——检测必须在设备上做。
 *
 * [dialect] 决定取数走哪套适配器：币安同构接口（Aster、币安主域）直接复用 /fapi/v1 路径；
 * 其余各家（OKX / Bybit / Bitget / Gate / MEXC / Hyperliquid / HTX / Bitunix）是**独立盘口**
 * 且协议各异，由 data/remote/dialect 下的适配器负责 URL 构造与响应归一化。
 * 切换接口时上层必须清掉合约缓存，避免两套盘口的数据混进同一 (FUTURES, symbol)。
 *
 * ⚠️ **席位数量不等于容灾能力**：币安主域的镜像 1/2/3 与老镜像曾是 4 个独立席位，但它们与
 * 主域共用同一套后端与同一份可达性（实测：不连代理时 6 个币安域一起全红，Gate / Bitunix
 * 照常），所以在候选里只留主域与 Aster，把位置让给真正独立的盘口。
 */
enum class FuturesDialect {
    /** 币安 /fapi/v1 同构（Aster 官方、币安主域）。 */
    BINANCE,
    OKX,
    BYBIT,
    BITGET,
    GATE,
    MEXC,
    HYPERLIQUID,
    /** HTX（火币）USDT 本位永续：linear-swap-ex 与 linear-swap-api 两套路径。 */
    HTX,
    /** Bitunix：api/v1/futures/market 一整套。 */
    BITUNIX,
}

data class FuturesEndpoint(
    /** 展示名。 */
    val label: String,
    /** REST base URL，不带路径。 */
    val baseUrl: String,
    /** 一行补充说明（盘口来源/可达性预期）。 */
    val note: String,
    val dialect: FuturesDialect,
)

object FuturesEndpoints {

    /** 出厂默认：Aster 官方域，大陆通常可直连。 */
    const val DEFAULT_URL = "https://fapi.asterdex.com"

    val ALL = listOf(
        // —— 币安同构（/fapi/v1 路径直接可用）——
        FuturesEndpoint("Aster 官方", DEFAULT_URL, "Aster 盘口 · 大陆通常可直连", FuturesDialect.BINANCE),
        FuturesEndpoint("币安合约 主域", "https://fapi.binance.com", "币安盘口 · 大陆通常需代理", FuturesDialect.BINANCE),
        // —— 独立盘口（协议各异，走对应适配器）——
        FuturesEndpoint("OKX", "https://www.okx.com", "独立盘口 · 大陆通常需代理", FuturesDialect.OKX),
        FuturesEndpoint("Bybit", "https://api.bybit.com", "独立盘口 · 大陆通常需代理", FuturesDialect.BYBIT),
        FuturesEndpoint("Bitget", "https://api.bitget.com", "独立盘口", FuturesDialect.BITGET),
        FuturesEndpoint("Gate", "https://api.gateio.ws", "独立盘口 · 大陆通常可直连", FuturesDialect.GATE),
        FuturesEndpoint("MEXC", "https://contract.mexc.com", "独立盘口", FuturesDialect.MEXC),
        FuturesEndpoint("Hyperliquid", "https://api.hyperliquid.xyz", "独立盘口 · 链上永续", FuturesDialect.HYPERLIQUID),
        // —— 新增（2026-09-29 实测：接口完整且直连可用；HTX 的域名偶有 DNS 污染，故不宣称稳定）——
        FuturesEndpoint("HTX 火币", "https://api.hbdm.com", "独立盘口 · 域名偶有污染", FuturesDialect.HTX),
        FuturesEndpoint("Bitunix", "https://fapi.bitunix.com", "独立盘口 · 大陆通常可直连", FuturesDialect.BITUNIX),
    )

    /** 规整用户存储值：去空白与尾部斜杠；不在清单里的旧值（如手填镜像）回落到默认。 */
    fun normalize(url: String?): String {
        val trimmed = url?.trim()?.removeSuffix("/") ?: ""
        return ALL.firstOrNull { it.baseUrl == trimmed }?.baseUrl ?: DEFAULT_URL
    }

    /** 按（规整后的）URL 找候选条目，用于解析取数方言。 */
    fun of(url: String?): FuturesEndpoint =
        ALL.first { it.baseUrl == normalize(url) }
}
