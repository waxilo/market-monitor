package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.flow.Flow

/**
 * 应用设置（PRD 8. 设置）。全部为可缺省值，新增字段不得要求用户先迁移。
 */
data class AppSettings(
    /** 主题模式（PRD 4.5「主题：跟随系统 / 浅色 / 深色」）。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 市场浏览的计价币过滤。 */
    val quoteAsset: String = "USDT",
    /** 冷启动默认市场。 */
    val defaultMarket: MarketType = MarketType.SPOT,
    /** 详情页记忆的周期（CandleInterval.storageKey）。 */
    val lastIntervalKey: String = "o:1d",
    /** 主图均线周期。 */
    val maPeriods: List<Int> = listOf(5, 10, 30),
    /** 是否叠加布林带。 */
    val bollEnabled: Boolean = false,
    /**
     * 副图窗格（SubPaneKind 名，逗号分隔）。**支持多选，空串表示不显示副图。**
     * 用单个字符串存是为了兼容 DataStore 的单 key 习惯，避免退化成数组序列化。
     */
    val subPaneKeys: String = "VOLUME",
    /** 十字光标长按开启（PRD FR-2.5）。 */
    val crosshairEnabled: Boolean = true,
    /** 后台预警轮询间隔秒数；受系统省电策略影响，见 PRD 4.3。 */
    val alertPollingSeconds: Int = 5,
    /** 新建预警规则的默认冷却分钟数（PRD 4.5 提醒默认参数）。 */
    val alertDefaultCooldownMinutes: Int = 5,
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
    /**
     * 更新包下载加速前缀（PRD FR-6.2 国内网络加速）。
     * 语义是「拼在原始地址前」，如 `https://gh-proxy.com/https://github.com/...`；
     * 空串表示直连 GitHub。只影响更新包与校验值下载，不影响 API 检查。
     */
    val updateProxyPrefix: String = "",
    val autoUpdateCheck: Boolean = true,
    /** 用户点「以后再说」的版本号，低于或等于该版本不再提示。 */
    val dismissedVersion: String = "",
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun edit(transform: (AppSettings) -> AppSettings)
}

/** 主题模式：跟随系统 / 强制浅色 / 强制深色（PRD 4.5）。 */
enum class ThemeMode(val key: String, val label: String, val shortLabel: String) {
    SYSTEM("system", "跟随系统", "系统"),
    LIGHT("light", "浅色", "浅色"),
    DARK("dark", "深色", "深色"),
    ;

    val isDark: Boolean? get() = when (this) {
        SYSTEM -> null
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
