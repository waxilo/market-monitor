package com.waxilo.marketmonitor.domain.model

/**
 * 市场行情类型。现货可在币安 / Gate 之间切换（见 [SpotSource]），永续合约在设置页
 * 选定的一家接口取数。同名交易对（如 BTCUSDT）在两个市场是独立盘口，
 * 因此所有行情实体都以 (marketType, symbol) 为身份。
 *
 * 合约默认 Aster 而非 fapi.binance.com：后者大陆网络不可直连，
 * Aster 同构接口实测可直连且文档公开（docs.asterdex.com）。
 */
enum class MarketType(
    /** 存储与接口用的稳定标识，不可随意改名（会破坏已持久化数据）。 */
    val key: String,
    /** 默认 REST 路径前缀（现货两家各自的真实前缀在 SpotDialects 里）。 */
    val apiPrefix: String,
    /** 默认 REST 域名。 */
    val defaultRestHost: String,
    /** 展示名。 */
    val label: String,
) {
    SPOT(
        key = "SPOT",
        apiPrefix = "/api/v3",
        defaultRestHost = "https://api.binance.com",
        label = "现货",
    ),
    FUTURES(
        key = "FUTURES",
        apiPrefix = "/fapi/v1",
        defaultRestHost = "https://fapi.asterdex.com",
        label = "永续合约",
    ),
    ;

    val isFutures: Boolean get() = this == FUTURES

    companion object {
        fun fromKey(key: String): MarketType = entries.firstOrNull { it.key == key } ?: SPOT
    }
}
