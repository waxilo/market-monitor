package com.waxilo.marketmonitor.domain.model

/**
 * 市场行情类型。币安现货与 USDT-M 合约是两套独立的域名与路径，
 * 同名交易对（如 BTCUSDT）在两个市场语义不同，因此所有行情实体都以 (marketType, symbol) 为身份。
 */
enum class MarketType(
    /** 存储与接口用的稳定标识，不可随意改名（会破坏已持久化数据）。 */
    val key: String,
    /** REST 路径前缀。 */
    val apiPrefix: String,
    /** 默认 REST 域名。 */
    val defaultRestHost: String,
    /** 默认 WebSocket 域名。 */
    val defaultWsHost: String,
    /** 展示名。 */
    val label: String,
) {
    SPOT(
        key = "SPOT",
        apiPrefix = "/api/v3",
        defaultRestHost = "https://api.binance.com",
        defaultWsHost = "wss://stream.binance.com:9443",
        label = "现货",
    ),
    FUTURES(
        key = "FUTURES",
        apiPrefix = "/fapi/v1",
        defaultRestHost = "https://fapi.binance.com",
        defaultWsHost = "wss://fstream.binance.com",
        label = "永续合约",
    ),
    ;

    val isFutures: Boolean get() = this == FUTURES

    companion object {
        fun fromKey(key: String): MarketType = entries.firstOrNull { it.key == key } ?: SPOT
    }
}
