package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.flow.Flow

/**
 * 应用设置（PRD 8. 设置）。全部为可缺省值，新增字段不得要求用户先迁移。
 */
data class AppSettings(
    /** 市场浏览的计价币过滤。 */
    val quoteAsset: String = "USDT",
    /** 冷启动默认市场。 */
    val defaultMarket: MarketType = MarketType.SPOT,
    /** 详情页记忆的周期（CandleInterval.storageKey）。 */
    val lastIntervalKey: String = "o:1m",
    /** 主图均线周期。 */
    val maPeriods: List<Int> = listOf(7, 25, 99),
    /** 副图窗格：成交量 / MACD / RSI / KDJ。 */
    val showVolumePane: Boolean = true,
    val showMacdPane: Boolean = true,
    val showRsiPane: Boolean = false,
    val showKdjPane: Boolean = false,
    /** 十字光标长按开启（PRD FR-2.5）。 */
    val crosshairEnabled: Boolean = true,
    /** 后台预警轮询间隔秒数；受系统省电策略影响，见 PRD 4.3。 */
    val alertPollingSeconds: Int = 10,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val webhookEnabled: Boolean = true,
    /** 允许局域网 http 端点，仅调试用（PRD FR-4.1）。 */
    val allowInsecureWebhook: Boolean = false,
    /** 备用域名，逗号分隔；为空表示仅用官方域名。 */
    val spotRestMirror: String = "",
    val futuresRestMirror: String = "",
    val wsMirror: String = "",
    val autoUpdateCheck: Boolean = true,
    /** 用户点「以后再说」的版本号，低于或等于该版本不再提示。 */
    val dismissedVersion: String = "",
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun edit(transform: (AppSettings) -> AppSettings)
}
