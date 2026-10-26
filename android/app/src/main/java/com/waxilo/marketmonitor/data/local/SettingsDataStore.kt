package com.waxilo.marketmonitor.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.waxilo.marketmonitor.domain.indicator.DEFAULT_MA_CATALOG
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.SettingsRepository
import com.waxilo.marketmonitor.domain.repository.ThemeMode
import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 设置持久化（PRD 8. 设置）。
 * 读路径对损坏文件降级为默认值：偏好项丢失只是回到默认，不该让应用起不来。
 */
class SettingsDataStore(private val store: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { it.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun edit(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = DEFAULTS
        return d.copy(
            themeMode = ThemeMode.fromKey(this[Keys.THEME]),
            quoteAsset = this[Keys.QUOTE] ?: d.quoteAsset,
            defaultMarket = MarketType.fromKey(this[Keys.MARKET] ?: d.defaultMarket.key),
            lastIntervalKey = this[Keys.INTERVAL] ?: d.lastIntervalKey,
            intervalKeys = this[Keys.INTERVAL_LIST] ?: d.intervalKeys,
            maVisible = readMaVisible(this, d),
            maCatalog = readMaCatalog(this),
            maColors = this[Keys.MA_COLORS]?.csvToMaColors() ?: d.maColors,
            // 老装机没有这个 key：空表 = 全都睁眼，升级后屏幕观感不变
            maMuted = this[Keys.MA_MUTED]?.csvToInts() ?: d.maMuted,
            emaVisible = this[Keys.EMA_VISIBLE] ?: d.emaVisible,
            emaCatalog = this[Keys.EMA_CATALOG]?.csvToInts() ?: d.emaCatalog,
            emaColors = this[Keys.EMA_COLORS]?.csvToMaColors() ?: d.emaColors,
            emaMuted = this[Keys.EMA_MUTED]?.csvToInts() ?: d.emaMuted,
            bollEnabled = this[Keys.BOLL] ?: d.bollEnabled,
            subPaneKeys = this[Keys.PANE] ?: d.subPaneKeys,
            crosshairEnabled = this[Keys.CROSSHAIR] ?: d.crosshairEnabled,
            alertPollingSeconds = this[Keys.POLL] ?: d.alertPollingSeconds,
            notificationEnabled = this[Keys.NOTIFY] ?: d.notificationEnabled,
            soundEnabled = this[Keys.SOUND] ?: d.soundEnabled,
            vibrateEnabled = this[Keys.VIBRATE] ?: d.vibrateEnabled,
            longVibrateEnabled = this[Keys.LONG_VIBRATE] ?: d.longVibrateEnabled,
            webhookEnabled = this[Keys.WEBHOOK] ?: d.webhookEnabled,
            allowInsecureWebhook = this[Keys.INSECURE] ?: d.allowInsecureWebhook,
            spotRestMirror = this[Keys.SPOT_MIRROR] ?: d.spotRestMirror,
            futuresRestHost = FuturesEndpoints.normalize(this[Keys.FUTURES_HOST]),
            autoFuturesSource = this[Keys.AUTO_FUTURES_SOURCE] ?: d.autoFuturesSource,
            updateMirror = UpdateMirror.fromStored(this[Keys.UPDATE_PROXY]),
            autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: d.autoUpdateCheck,
            dismissedVersion = this[Keys.DISMISSED] ?: d.dismissedVersion,
            // 视角三数：非有限值（理论上写不进，防手改/旧版本）回默认；缩放倍数还必须为正。
            // 横向位置不落盘（见 AppSettings 里那段说明），老装机的 chart_right_offset 直接忽略
            chartVisibleBars = this[Keys.CHART_BARS]?.takeIf { it.isFinite() && it > 0f }
                ?: d.chartVisibleBars,
            chartPriceZoom = this[Keys.CHART_PZOOM]?.takeIf { it.isFinite() && it > 0f }
                ?: d.chartPriceZoom,
            chartPricePan = this[Keys.CHART_PPAN]?.takeIf { it.isFinite() }
                ?: d.chartPricePan,
        )
    }

    /**
     * MA 一族的目录（主图画哪几档）：新 key 优先，其余都是老装机的迁移路径。
     *
     * 老模型有两层：「目录」= 指标条上有哪些 chip，「选中集」= 其中画着的那几条。
     * 新模型只有一层（目录即配置，整族一个显隐开关），所以把**当时画着的那几条**升成新目录 ——
     * 升级前后屏幕上的东西一模一样，用户不会莫名其妙多出或少掉几条线。
     * 一条都没选（裸 K）就退回「五档 ∪ 老自定义」，显隐交给 [readMaVisible] 折算成「藏起来」。
     *
     * 更老的版本把预置五档写死在代码里、只存自定义档（`ma_custom_periods`），所以那条路要
     * 把五档补回来，否则升级后指标条上只剩自定义那几条。
     * 两个 key 都没有 → null（上层用出厂目录）；空串 → 空表（用户把档位清空了）。
     * 范围、去重、条数上限一律不在这里管，归一到 VM 的 `normalizeMaCatalog`。
     */
    private fun readMaCatalog(prefs: Preferences): List<Int>? {
        prefs[Keys.MA_CATALOG]?.let { return it.csvToInts() }
        val legacySelection = prefs[Keys.MA]?.csvToInts()
        if (!legacySelection.isNullOrEmpty()) return legacySelection
        val legacyCustom = prefs[Keys.MA_CUSTOM] ?: return null
        return DEFAULT_MA_CATALOG + legacyCustom.csvToInts()
    }

    /**
     * MA 一族画不画：新 key 优先；老装机按旧模型折算 ——
     * 当时一条都没选 = 裸 K（visible=false），否则看旧的 `ma_hidden`。
     */
    private fun readMaVisible(prefs: Preferences, d: AppSettings): Boolean {
        prefs[Keys.MA_VISIBLE]?.let { return it }
        val legacySelection = prefs[Keys.MA] ?: return d.maVisible
        if (legacySelection.isBlank()) return false
        return prefs[Keys.MA_HIDDEN] != true
    }

    /** 逗号分隔的整数表；解析不出的项丢掉（旧值/手改坏的都走这里）。 */
    private fun String.csvToInts(): List<Int> =
        split(',').mapNotNull { it.trim().toIntOrNull() }

    /**
     * `期数:色槽` 逗号分隔表（如 `5:0,10:1`）；坏项丢掉。
     * 期数范围与槽位是否可用不在这里判 —— 归一到 VM 的 `normalizeMaColors`。
     */
    private fun String.csvToMaColors(): Map<Int, Int> = split(',').mapNotNull { item ->
        val parts = item.split(':')
        if (parts.size != 2) return@mapNotNull null
        val period = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
        val slot = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
        period to slot
    }.toMap()

    /** `期数:色槽` 逗号分隔表；空表写成空串（与「没设过」在偏好层是同一个值，读侧无差别）。 */
    private fun Map<Int, Int>.toCsv(): String =
        entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    private fun AppSettings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.THEME] = themeMode.key
        prefs[Keys.QUOTE] = quoteAsset
        prefs[Keys.MARKET] = defaultMarket.key
        prefs[Keys.INTERVAL] = lastIntervalKey
        prefs[Keys.INTERVAL_LIST] = intervalKeys
        // 老 key `ma_periods`（当时画着的那几条）不再写：新模型里「画什么」由目录 + 两级显隐
        // （族 chip / 逐条小眼睛）决定，它只是迁移时的输入（见 readMaCatalog / readMaVisible），
        // 写回去只会造出第二份真相。
        prefs[Keys.MA_VISIBLE] = maVisible
        // null（没设过）就把 key 撤掉，而不是写空串 —— 空串是「用户把档位清空了」，两件事。
        // 老 key ma_custom_periods 留着不删：读的时候已被 readMaCatalog 并进去，
        // 之后每次写都落在新 key 上，老值成孤儿（无害，同当年撤掉的冷却配置）。
        if (maCatalog == null) prefs.remove(Keys.MA_CATALOG)
        else prefs[Keys.MA_CATALOG] = maCatalog.joinToString(",")
        prefs[Keys.MA_COLORS] = maColors.toCsv()
        // 空表写成空串：读侧 `csvToInts()` 得到空表，与「全都睁眼」同义，无差别
        prefs[Keys.MA_MUTED] = maMuted.joinToString(",")
        prefs[Keys.EMA_VISIBLE] = emaVisible
        if (emaCatalog == null) prefs.remove(Keys.EMA_CATALOG)
        else prefs[Keys.EMA_CATALOG] = emaCatalog.joinToString(",")
        prefs[Keys.EMA_COLORS] = emaColors.toCsv()
        prefs[Keys.EMA_MUTED] = emaMuted.joinToString(",")
        prefs[Keys.BOLL] = bollEnabled
        prefs[Keys.PANE] = subPaneKeys
        prefs[Keys.CROSSHAIR] = crosshairEnabled
        prefs[Keys.POLL] = alertPollingSeconds
        prefs[Keys.NOTIFY] = notificationEnabled
        prefs[Keys.SOUND] = soundEnabled
        prefs[Keys.VIBRATE] = vibrateEnabled
        prefs[Keys.LONG_VIBRATE] = longVibrateEnabled
        prefs[Keys.WEBHOOK] = webhookEnabled
        prefs[Keys.INSECURE] = allowInsecureWebhook
        prefs[Keys.SPOT_MIRROR] = spotRestMirror
        prefs[Keys.FUTURES_HOST] = futuresRestHost
        prefs[Keys.AUTO_FUTURES_SOURCE] = autoFuturesSource
        prefs[Keys.UPDATE_PROXY] = updateMirror.key
        prefs[Keys.AUTO_UPDATE] = autoUpdateCheck
        prefs[Keys.DISMISSED] = dismissedVersion
        prefs[Keys.CHART_BARS] = chartVisibleBars
        prefs[Keys.CHART_PZOOM] = chartPriceZoom
        prefs[Keys.CHART_PPAN] = chartPricePan
    }

    companion object {
        private val DEFAULTS = AppSettings()

        private object Keys {
            val THEME = stringPreferencesKey("theme_mode")
            val QUOTE = stringPreferencesKey("quote_asset")
            val MARKET = stringPreferencesKey("default_market")
            val INTERVAL = stringPreferencesKey("last_interval")
            val INTERVAL_LIST = stringPreferencesKey("interval_list")
            val MA_VISIBLE = booleanPreferencesKey("ma_visible")
            val MA_CATALOG = stringPreferencesKey("ma_catalog")
            val MA_COLORS = stringPreferencesKey("ma_colors")
            /** 逐条小眼睛闭着的期数（逗号分隔）。空串 = 全都睁眼。 */
            val MA_MUTED = stringPreferencesKey("ma_muted")
            val EMA_VISIBLE = booleanPreferencesKey("ema_visible")
            val EMA_CATALOG = stringPreferencesKey("ema_catalog")
            val EMA_COLORS = stringPreferencesKey("ema_colors")
            val EMA_MUTED = stringPreferencesKey("ema_muted")

            /** 老 key：只存自定义档（预置五档不在里面）。留着只读，用于升级迁移。 */
            val MA_CUSTOM = stringPreferencesKey("ma_custom_periods")

            /** 老 key：当时画着的那几条（「选中集」）。只读，迁移成新目录与显隐。 */
            val MA = stringPreferencesKey("ma_periods")

            /** 老 key：均线小眼睛。只读，迁移成 [MA_VISIBLE]。 */
            val MA_HIDDEN = booleanPreferencesKey("ma_hidden")
            val BOLL = booleanPreferencesKey("boll_enabled")
            val PANE = stringPreferencesKey("sub_pane")
            val CROSSHAIR = booleanPreferencesKey("crosshair")
            // key 从 alert_polling_seconds 改名：旧默认 30s 会被持久化下来，
            // 继续读旧 key 的话新默认 5s 对老装机永远不生效（看起来像没改）
            val POLL = intPreferencesKey("alert_polling_seconds_v2")
            val NOTIFY = booleanPreferencesKey("notification_enabled")
            val SOUND = booleanPreferencesKey("sound_enabled")
            val VIBRATE = booleanPreferencesKey("vibrate_enabled")
            val LONG_VIBRATE = booleanPreferencesKey("long_vibrate_enabled")
            val WEBHOOK = booleanPreferencesKey("webhook_enabled")
            val INSECURE = booleanPreferencesKey("allow_insecure_webhook")
            val SPOT_MIRROR = stringPreferencesKey("spot_rest_mirror")
            val FUTURES_HOST = stringPreferencesKey("futures_rest_host")
            /** 合约源自动选择开关（出厂关：缺 key 即关，见 AppSettings.autoFuturesSource）。 */
            val AUTO_FUTURES_SOURCE = booleanPreferencesKey("auto_futures_source")
            val UPDATE_PROXY = stringPreferencesKey("update_proxy_prefix")
            val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
            val DISMISSED = stringPreferencesKey("dismissed_version")
            val CHART_BARS = floatPreferencesKey("chart_visible_bars")
            val CHART_PZOOM = floatPreferencesKey("chart_price_zoom")
            val CHART_PPAN = floatPreferencesKey("chart_price_pan")
        }

        const val FILE_NAME = "settings.preferences_pb"

        fun create(context: Context): SettingsDataStore = SettingsDataStore(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
        )
    }
}
