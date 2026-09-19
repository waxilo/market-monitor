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
import com.waxilo.marketmonitor.domain.model.MarketType
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
            quoteAsset = this[Keys.QUOTE] ?: d.quoteAsset,
            defaultMarket = MarketType.fromKey(this[Keys.MARKET] ?: d.defaultMarket.key),
            lastIntervalKey = this[Keys.INTERVAL] ?: d.lastIntervalKey,
            maPeriods = this[Keys.MA]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: d.maPeriods,
            showVolumePane = this[Keys.PANE_VOL] ?: d.showVolumePane,
            showMacdPane = this[Keys.PANE_MACD] ?: d.showMacdPane,
            showRsiPane = this[Keys.PANE_RSI] ?: d.showRsiPane,
            showKdjPane = this[Keys.PANE_KDJ] ?: d.showKdjPane,
            crosshairEnabled = this[Keys.CROSSHAIR] ?: d.crosshairEnabled,
            alertPollingSeconds = this[Keys.POLL] ?: d.alertPollingSeconds,
            notificationEnabled = this[Keys.NOTIFY] ?: d.notificationEnabled,
            soundEnabled = this[Keys.SOUND] ?: d.soundEnabled,
            vibrateEnabled = this[Keys.VIBRATE] ?: d.vibrateEnabled,
            webhookEnabled = this[Keys.WEBHOOK] ?: d.webhookEnabled,
            allowInsecureWebhook = this[Keys.INSECURE] ?: d.allowInsecureWebhook,
            spotRestMirror = this[Keys.SPOT_MIRROR] ?: d.spotRestMirror,
            futuresRestMirror = this[Keys.FUTURES_MIRROR] ?: d.futuresRestMirror,
            wsMirror = this[Keys.WS_MIRROR] ?: d.wsMirror,
            autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: d.autoUpdateCheck,
            dismissedVersion = this[Keys.DISMISSED] ?: d.dismissedVersion,
        )
    }

    private fun AppSettings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.QUOTE] = quoteAsset
        prefs[Keys.MARKET] = defaultMarket.key
        prefs[Keys.INTERVAL] = lastIntervalKey
        prefs[Keys.MA] = maPeriods.joinToString(",")
        prefs[Keys.PANE_VOL] = showVolumePane
        prefs[Keys.PANE_MACD] = showMacdPane
        prefs[Keys.PANE_RSI] = showRsiPane
        prefs[Keys.PANE_KDJ] = showKdjPane
        prefs[Keys.CROSSHAIR] = crosshairEnabled
        prefs[Keys.POLL] = alertPollingSeconds.coerceIn(3, 300)
        prefs[Keys.NOTIFY] = notificationEnabled
        prefs[Keys.SOUND] = soundEnabled
        prefs[Keys.VIBRATE] = vibrateEnabled
        prefs[Keys.WEBHOOK] = webhookEnabled
        prefs[Keys.INSECURE] = allowInsecureWebhook
        prefs[Keys.SPOT_MIRROR] = spotRestMirror
        prefs[Keys.FUTURES_MIRROR] = futuresRestMirror
        prefs[Keys.WS_MIRROR] = wsMirror
        prefs[Keys.AUTO_UPDATE] = autoUpdateCheck
        prefs[Keys.DISMISSED] = dismissedVersion
    }

    companion object {
        private val DEFAULTS = AppSettings()

        private object Keys {
            val QUOTE = stringPreferencesKey("quote_asset")
            val MARKET = stringPreferencesKey("default_market")
            val INTERVAL = stringPreferencesKey("last_interval")
            val MA = stringPreferencesKey("ma_periods")
            val PANE_VOL = booleanPreferencesKey("pane_volume")
            val PANE_MACD = booleanPreferencesKey("pane_macd")
            val PANE_RSI = booleanPreferencesKey("pane_rsi")
            val PANE_KDJ = booleanPreferencesKey("pane_kdj")
            val CROSSHAIR = booleanPreferencesKey("crosshair")
            val POLL = intPreferencesKey("alert_polling_seconds")
            val NOTIFY = booleanPreferencesKey("notification_enabled")
            val SOUND = booleanPreferencesKey("sound_enabled")
            val VIBRATE = booleanPreferencesKey("vibrate_enabled")
            val WEBHOOK = booleanPreferencesKey("webhook_enabled")
            val INSECURE = booleanPreferencesKey("allow_insecure_webhook")
            val SPOT_MIRROR = stringPreferencesKey("spot_rest_mirror")
            val FUTURES_MIRROR = stringPreferencesKey("futures_rest_mirror")
            val WS_MIRROR = stringPreferencesKey("ws_mirror")
            val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
            val DISMISSED = stringPreferencesKey("dismissed_version")
        }

        const val FILE_NAME = "settings.preferences_pb"

        fun create(context: Context): SettingsDataStore = SettingsDataStore(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
        )
    }
}
