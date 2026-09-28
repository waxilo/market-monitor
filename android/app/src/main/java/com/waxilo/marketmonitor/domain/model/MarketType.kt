package com.waxilo.marketmonitor.domain.model

/**
 * 市场行情类型。现货走币安，永续合约走 Aster（fapi.asterdex.com）。
 * 两套接口的 REST 路径前缀同构（币安 /fapi/v1 语义），但同名交易对
 * （如 BTCUSDT）在两个市场是独立盘口，因此所有行情实体都以 (marketType, symbol) 为身份。
 *
 * 合约选 Aster 而非 fapi.binance.com 的原因：后者大陆网络不可直连，
 * Aster 同构接口实测可直连且文档公开（docs.asterdex.com）。
 */
enum class MarketType(
    /** 存储与接口用的稳定标识，不可随意改名（会破坏已持久化数据）。 */
    val key: String,
    /** REST 路径前缀。 */
    val apiPrefix: String,
    /** 默认 REST 域名。 */
    val defaultRestHost: String,
    /** 展示名。 */
    val label: String,
    /** 数据来源的展示名（列表页脚注与设置页文案用）。 */
    val sourceName: String,
) {
    SPOT(
        key = "SPOT",
        apiPrefix = "/api/v3",
        defaultRestHost = "https://api.binance.com",
        label = "现货",
        sourceName = "币安",
    ),
    FUTURES(
        key = "FUTURES",
        apiPrefix = "/fapi/v1",
        defaultRestHost = "https://fapi.asterdex.com",
        label = "永续合约",
        sourceName = "Aster",
    ),
    ;

    val isFutures: Boolean get() = this == FUTURES

    companion object {
        fun fromKey(key: String): MarketType = entries.firstOrNull { it.key == key } ?: SPOT
    }
}
