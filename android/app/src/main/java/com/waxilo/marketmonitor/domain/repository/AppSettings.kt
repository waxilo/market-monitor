package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import kotlinx.coroutines.flow.Flow

/**
 * 主图均线的出厂目录见 `domain/indicator/MaFamily.kt`（族 → 目录），
 * 这里只管持久化，不再持有默认值。
 */

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
    /**
     * 周期条上摊开的方块（CandleInterval.storageKey，逗号分隔，**顺序即展示顺序**）。
     * 空串 = 用 CandleInterval.quickPickPresets 默认集。格式同 [subPaneKeys]。
     */
    val intervalKeys: String = "",
    /**
     * 主图 MA 一族画不画（指标条上那枚 MA chip 的亮灭）。
     * **只藏不删** —— [maCatalog] 与 [maColors] 原样保留，再点一下 chip 就回来。
     */
    val maVisible: Boolean = true,
    /**
     * MA 一族的**目录**：主图上画哪几档（有序）。`null` = 没设过，用出厂五档；
     * **空表是合法偏好** —— 用户把档位全删了就该是真的没有（chip 点亮也不画线）。
     */
    val maCatalog: List<Int>? = null,
    /**
     * MA 一族的线色：**期数 → 色槽 0~7**（见 `ui/chart/ChartSeries.kt` 的 `MA_LINE_ROLES`）。
     * 空表 = 没设过（老装机），上层按目录升序补成 0~4…，与旧版观感一致。
     */
    val maColors: Map<Int, Int> = emptyMap(),
    /**
     * MA 一族里**逐条小眼睛闭着**的期数（`⊆ maCatalog`）。
     * 空表 = 全都睁眼（老装机的迁移路径也是它）；只藏不删，随时点回来。
     */
    val maMuted: List<Int> = emptyList(),
    /** 主图 EMA 一族画不画（出厂默认关 —— 想看指数均线得自己点亮那枚 chip）。 */
    val emaVisible: Boolean = false,
    /** EMA 一族的目录（与 [maCatalog] 同构，各自独立、各自 8 条上限）。 */
    val emaCatalog: List<Int>? = null,
    /** EMA 一族的线色：期数 → 色槽 0~7（见 `EMA_LINE_ROLES`，与 MA 的八槽不撞色）。 */
    val emaColors: Map<Int, Int> = emptyMap(),
    /** EMA 一族里逐条小眼睛闭着的期数（`⊆ emaCatalog`）。 */
    val emaMuted: List<Int> = emptyList(),
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
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    /** 预警通知是否用长震动（三轮长振）；关闭后走系统默认节奏的短振。 */
    val longVibrateEnabled: Boolean = true,
    val webhookEnabled: Boolean = true,
    /** 允许局域网 http 端点，仅调试用（PRD FR-4.1）。 */
    val allowInsecureWebhook: Boolean = false,
    /** 备用域名，逗号分隔；为空表示仅用官方域名。 */
    val spotRestMirror: String = "",
    /**
     * 用户选定的合约行情接口（[com.waxilo.marketmonitor.domain.model.FuturesEndpoints] 之一）。
     * 合约行情只发往这一个域名，不再逐域回退试探；设置页一键检测后点选。
     */
    val futuresRestHost: String = FuturesEndpoints.DEFAULT_URL,
    /**
     * 合约源「自动选择」：当前接口失败或响应超过 500ms 时并行测速，按 `FuturesAutoSelector`
     * 的规则换源（失败→最先响应的可用源；慢→仅当候选明显更快）。
     *
     * **出厂关闭**（2026-10-23）：换源要清空合约缓存、重拉 K 线与交易对，而各盘口 OHLC
     * 本来就略有差异 —— 这种「在用户背后换源」的动作必须由用户主动点头，不能默认替用户做主。
     * 关闭时 [futuresRestHost] 就是唯一真相，失败会如实报错（页面自带重试）。
     */
    val autoFuturesSource: Boolean = false,
    /**
     * 更新加速站（PRD FR-6.2 国内网络加速）。默认直连 GitHub。
     * 既作用于更新包与校验值的下载，也作用于检查更新的 API —— 见 [UpdateMirror.apiChain]。
     */
    val updateMirror: UpdateMirror = UpdateMirror.NATIVE,
    val autoUpdateCheck: Boolean = true,
    /** 用户点「以后再说」的版本号，低于或等于该版本不再提示。 */
    val dismissedVersion: String = "",
    /**
     * K 线视角偏好（**全局一份**，与指标同口径）：看多宽（可见根数）、右端停哪
     * （右侧留白 / 回看多少根）、纵向刻度（缩放倍数 + 平移比例）。
     *
     * 四个数就是 `ChartViewport` 的「用户意图」—— 切周期、换标的、重启一律保持；
     * 双击复位 = 回默认**并遗忘**（写回这里的默认值）。缺省 = 默认视角
     * （60 根、右侧留白 3 根、纵向自动量程），与 `ChartViewport.initial()` 对齐。
     * 越界值在使用处由 `ChartViewport.clamp` / `ValueRange` 的钳制兜底。
     */
    val chartVisibleBars: Float = 60f,
    val chartRightOffset: Float = 3f,
    val chartPriceZoom: Float = 1f,
    val chartPricePan: Float = 0f,
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
