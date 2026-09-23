package com.waxilo.marketmonitor.domain.model

/**
 * 内置的永续合约行情候选接口（PRD 3.2）。
 *
 * 域名容灾不再靠每次请求逐域回退试探：候选清单固定打包进 App，
 * 设置页「一键检测」在**用户本机**实测连通性与延迟，由用户点选一个使用。
 * 网络环境因地区/代理而异，开发机上的探测结果不作数——检测必须在设备上做。
 *
 * 注意：币安合约与 Aster 是**独立盘口**，同名交易对价格不同；
 * 切换接口时上层必须清掉合约缓存，避免两套数据混进同一 (FUTURES, symbol)。
 */
data class FuturesEndpoint(
    /** 展示名。 */
    val label: String,
    /** REST base URL，不带路径。 */
    val baseUrl: String,
    /** 一行补充说明（盘口来源/可达性预期）。 */
    val note: String,
)

object FuturesEndpoints {

    /** 出厂默认：Aster 官方域，大陆通常可直连。 */
    const val DEFAULT_URL = "https://fapi.asterdex.com"

    val ALL = listOf(
        FuturesEndpoint("Aster 官方", DEFAULT_URL, "Aster 盘口 · 大陆通常可直连"),
        FuturesEndpoint("币安合约 主域", "https://fapi.binance.com", "币安盘口 · 大陆通常需代理"),
        FuturesEndpoint("币安合约 镜像 1", "https://fapi1.binance.com", "币安盘口 · 官方镜像"),
        FuturesEndpoint("币安合约 镜像 2", "https://fapi2.binance.com", "币安盘口 · 官方镜像"),
        FuturesEndpoint("币安合约 镜像 3", "https://fapi3.binance.com", "币安盘口 · 官方镜像"),
    )

    /** 规整用户存储值：去空白与尾部斜杠；不在清单里的旧值（如手填镜像）回落到默认。 */
    fun normalize(url: String?): String {
        val trimmed = url?.trim()?.removeSuffix("/") ?: ""
        return ALL.firstOrNull { it.baseUrl == trimmed }?.baseUrl ?: DEFAULT_URL
    }
}
