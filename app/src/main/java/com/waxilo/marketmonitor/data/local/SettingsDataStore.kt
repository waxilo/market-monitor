package com.waxilo.marketmonitor.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.SettingsRepository
import com.waxilo.marketmonitor.domain.repository.ThemeMode
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
            bollEnabled = this[Keys.BOLL] ?: d.bollEnabled,
            subPaneKeys = this[Keys.PANE] ?: d.subPaneKeys,
            crosshairEnabled = this[Keys.CROSSHAIR] ?: d.crosshairEnabled,
            alertPollingSeconds = this[Keys.POLL] ?: d.alertPollingSeconds,
            alertDefaultCooldownMinutes = this[Keys.COOLDOWN] ?: d.alertDefaultCooldownMinutes,
            notificationEnabled = this[Keys.NOTIFY] ?: d.notificationEnabled,
            soundEnabled = this[Keys.SOUND] ?: d.soundEnabled,
            vibrateEnabled = this[Keys.VIBRATE] ?: d.vibrateEnabled,
            webhookEnabled = this[Keys.WEBHOOK] ?: d.webhookEnabled,
            allowInsecureWebhook = this[Keys.INSECURE] ?: d.allowInsecureWebhook,
            spotRestMirror = this[Keys.SPOT_MIRROR] ?: d.spotRestMirror,
            futuresRestMirror = this[Keys.FUTURES_MIRROR] ?: d.futuresRestMirror,
            wsMirror = this[Keys.WS_MIRROR] ?: d.wsMirror,
            updateMirror = UpdateMirror.fromStored(this[Keys.UPDATE_PROXY]),
            autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: d.autoUpdateCheck,
            dismissedVersion = this[Keys.DISMISSED] ?: d.dismissedVersion,
        )
    }

    private fun AppSettings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.THEME] = themeMode.key
        prefs[Keys.QUOTE] = quoteAsset
        prefs[Keys.MARKET] = defaultMarket.key
        prefs[Keys.INTERVAL] = lastIntervalKey
        prefs[Keys.INTERVAL_LIST] = intervalKeys
        prefs[Keys.MA] = maPeriods.joinToString(",")
        prefs[Keys.BOLL] = bollEnabled
        prefs[Keys.PANE] = subPaneKeys
        prefs[Keys.CROSSHAIR] = crosshairEnabled
        prefs[Keys.POLL] = alertPollingSeconds
        prefs[Keys.COOLDOWN] = alertDefaultCooldownMinutes
        prefs[Keys.NOTIFY] = notificationEnabled
        prefs[Keys.SOUND] = soundEnabled
        prefs[Keys.VIBRATE] = vibrateEnabled
        prefs[Keys.WEBHOOK] = webhookEnabled
        prefs[Keys.INSECURE] = allowInsecureWebhook
        prefs[Keys.SPOT_MIRROR] = spotRestMirror
        prefs[Keys.FUTURES_MIRROR] = futuresRestMirror
        prefs[Keys.WS_MIRROR] = wsMirror
        prefs[Keys.UPDATE_PROXY] = updateMirror.key
        prefs[Keys.AUTO_UPDATE] = autoUpdateCheck
        prefs[Keys.DISMISSED] = dismissedVersion
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
            val BOLL = booleanPreferencesKey("boll_enabled")
            val PANE = stringPreferencesKey("sub_pane")
            val CROSSHAIR = booleanPreferencesKey("crosshair")
            // key 从 alert_polling_seconds 改名：旧默认 30s 会被持久化下来，
            // 继续读旧 key 的话新默认 5s 对老装机永远不生效（看起来像没改）
            val POLL = intPreferencesKey("alert_polling_seconds_v2")
            val COOLDOWN = intPreferencesKey("alert_cooldown_minutes")
            val NOTIFY = booleanPreferencesKey("notification_enabled")
            val SOUND = booleanPreferencesKey("sound_enabled")
            val VIBRATE = booleanPreferencesKey("vibrate_enabled")
            val WEBHOOK = booleanPreferencesKey("webhook_enabled")
            val INSECURE = booleanPreferencesKey("allow_insecure_webhook")
            val SPOT_MIRROR = stringPreferencesKey("spot_rest_mirror")
            val FUTURES_MIRROR = stringPreferencesKey("futures_rest_mirror")
            val WS_MIRROR = stringPreferencesKey("ws_mirror")
            val UPDATE_PROXY = stringPreferencesKey("update_proxy_prefix")
            val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
            val DISMISSED = stringPreferencesKey("dismissed_version")
        }

        const val FILE_NAME = "settings.preferences_pb"

        fun create(context: Context): SettingsDataStore = SettingsDataStore(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
        )
    }
}
