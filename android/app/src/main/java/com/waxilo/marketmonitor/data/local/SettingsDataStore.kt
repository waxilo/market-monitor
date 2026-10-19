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
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.DEFAULT_MA_CATALOG
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
            maPeriods = this[Keys.MA]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: d.maPeriods,
            maCatalog = readMaCatalog(this),
            maColors = this[Keys.MA_COLORS]?.csvToMaColors() ?: d.maColors,
            maHidden = this[Keys.MA_HIDDEN] ?: d.maHidden,
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
            updateMirror = UpdateMirror.fromStored(this[Keys.UPDATE_PROXY]),
            autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: d.autoUpdateCheck,
            dismissedVersion = this[Keys.DISMISSED] ?: d.dismissedVersion,
            // 视角四数：非有限值（理论上写不进，防手改/旧版本）回默认；缩放倍数还必须为正
            chartVisibleBars = this[Keys.CHART_BARS]?.takeIf { it.isFinite() && it > 0f }
                ?: d.chartVisibleBars,
            chartRightOffset = this[Keys.CHART_OFFSET]?.takeIf { it.isFinite() }
                ?: d.chartRightOffset,
            chartPriceZoom = this[Keys.CHART_PZOOM]?.takeIf { it.isFinite() && it > 0f }
                ?: d.chartPriceZoom,
            chartPricePan = this[Keys.CHART_PPAN]?.takeIf { it.isFinite() }
                ?: d.chartPricePan,
        )
    }

    /**
     * 均线目录（指标条上有哪些 chip）：新 key 优先；只有老 key（`ma_custom_periods`）时，
     * 并成「出厂五档 + 老自定义」。
     *
     * 老版本把预置五档**写死在代码里**、只存自定义档，所以迁移必须把五档补回来 ——
     * 直接拿老值当目录的话，升级后指标条上只剩自定义那几条，五档凭空消失。
     * 两个 key 都没有 → null（上层用出厂目录）；空串 → 空表（用户把目录清空了）。
     * 范围、去重、条数上限一律不在这里管，归一到 VM 的 normalizeMaCatalog。
     */
    private fun readMaCatalog(prefs: Preferences): List<Int>? {
        prefs[Keys.MA_CATALOG]?.let { return it.csvToInts() }
        val legacy = prefs[Keys.MA_CUSTOM] ?: return null
        return DEFAULT_MA_CATALOG + legacy.csvToInts()
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

    private fun AppSettings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.THEME] = themeMode.key
        prefs[Keys.QUOTE] = quoteAsset
        prefs[Keys.MARKET] = defaultMarket.key
        prefs[Keys.INTERVAL] = lastIntervalKey
        prefs[Keys.INTERVAL_LIST] = intervalKeys
        prefs[Keys.MA] = maPeriods.joinToString(",")
        // null（没设过）就把 key 撤掉，而不是写空串 —— 空串是「用户把目录清空了」，两件事。
        // 老 key ma_custom_periods 留着不删：读的时候已被 readMaCatalog 并进去，
        // 之后每次写都落在新 key 上，老值成孤儿（无害，同当年撤掉的冷却配置）。
        if (maCatalog == null) prefs.remove(Keys.MA_CATALOG)
        else prefs[Keys.MA_CATALOG] = maCatalog.joinToString(",")
        prefs[Keys.MA_COLORS] = maColors.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
        prefs[Keys.MA_HIDDEN] = maHidden
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
        prefs[Keys.UPDATE_PROXY] = updateMirror.key
        prefs[Keys.AUTO_UPDATE] = autoUpdateCheck
        prefs[Keys.DISMISSED] = dismissedVersion
        prefs[Keys.CHART_BARS] = chartVisibleBars
        prefs[Keys.CHART_OFFSET] = chartRightOffset
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
            val MA = stringPreferencesKey("ma_periods")
            val MA_CATALOG = stringPreferencesKey("ma_catalog")
            val MA_COLORS = stringPreferencesKey("ma_colors")

            /** 老 key：只存自定义档（预置五档不在里面）。留着只读，用于升级迁移。 */
            val MA_CUSTOM = stringPreferencesKey("ma_custom_periods")
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
            val UPDATE_PROXY = stringPreferencesKey("update_proxy_prefix")
            val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
            val DISMISSED = stringPreferencesKey("dismissed_version")
            val CHART_BARS = floatPreferencesKey("chart_visible_bars")
            val CHART_OFFSET = floatPreferencesKey("chart_right_offset")
            val CHART_PZOOM = floatPreferencesKey("chart_price_zoom")
            val CHART_PPAN = floatPreferencesKey("chart_price_pan")
        }

        const val FILE_NAME = "settings.preferences_pb"

        fun create(context: Context): SettingsDataStore = SettingsDataStore(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
        )
    }
}
