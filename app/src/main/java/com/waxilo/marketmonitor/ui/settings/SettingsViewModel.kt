package com.waxilo.marketmonitor.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.BuildConfig
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.ThemeMode
import com.waxilo.marketmonitor.domain.repository.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页状态：载入一次当前设置快照，编辑即时写入仓库。 */
@Immutable
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quoteAsset: String = "USDT",
    val defaultMarket: MarketType = MarketType.SPOT,
    val spotRestMirror: String = "",
    val futuresRestMirror: String = "",
    val wsMirror: String = "",
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val webhookEnabled: Boolean = true,
    val alertPollingSeconds: Int = 30,
    val autoUpdateCheck: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
    /** 检查更新的结果：null 表示未检查或进行中。 */
    val updateInfo: UpdateInfo? = null,
    val updateError: String? = null,
    val updating: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings

    private val backing = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = backing.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsUiState(),
    )

    init {
        viewModelScope.launch {
            settings.current().let {
                backing.value = SettingsUiState(
                    themeMode = it.themeMode,
                    quoteAsset = it.quoteAsset,
                    defaultMarket = it.defaultMarket,
                    spotRestMirror = it.spotRestMirror,
                    futuresRestMirror = it.futuresRestMirror,
                    wsMirror = it.wsMirror,
                    notificationEnabled = it.notificationEnabled,
                    soundEnabled = it.soundEnabled,
                    vibrateEnabled = it.vibrateEnabled,
                    webhookEnabled = it.webhookEnabled,
                    alertPollingSeconds = it.alertPollingSeconds,
                    autoUpdateCheck = it.autoUpdateCheck,
                )
            }
        }
    }

    fun setThemeMode(v: ThemeMode) {
        backing.update { it.copy(themeMode = v) }
        persist { s -> s.copy(themeMode = v) }
    }

    fun setQuoteAsset(v: String) {
        backing.update { it.copy(quoteAsset = v) }
        persist { s -> s.copy(quoteAsset = v.ifBlank { "USDT" }) }
    }

    fun setDefaultMarket(v: MarketType) {
        backing.update { it.copy(defaultMarket = v) }
        persist { s -> s.copy(defaultMarket = v) }
    }

    fun setSpotRestMirror(v: String) {
        backing.update { it.copy(spotRestMirror = v) }
        persist { s -> s.copy(spotRestMirror = v) }
    }

    fun setFuturesRestMirror(v: String) {
        backing.update { it.copy(futuresRestMirror = v) }
        persist { s -> s.copy(futuresRestMirror = v) }
    }

    fun setWsMirror(v: String) {
        backing.update { it.copy(wsMirror = v) }
        persist { s -> s.copy(wsMirror = v) }
    }

    fun setNotificationEnabled(v: Boolean) {
        backing.update { it.copy(notificationEnabled = v) }
        persist { s -> s.copy(notificationEnabled = v) }
    }

    fun setSoundEnabled(v: Boolean) {
        backing.update { it.copy(soundEnabled = v) }
        persist { s -> s.copy(soundEnabled = v) }
    }

    fun setVibrateEnabled(v: Boolean) {
        backing.update { it.copy(vibrateEnabled = v) }
        persist { s -> s.copy(vibrateEnabled = v) }
    }

    fun setWebhookEnabled(v: Boolean) {
        backing.update { it.copy(webhookEnabled = v) }
        persist { s -> s.copy(webhookEnabled = v) }
    }

    fun setAlertPollingSeconds(v: Int) {
        backing.update { it.copy(alertPollingSeconds = v) }
        persist { s -> s.copy(alertPollingSeconds = v) }
    }

    fun setAutoUpdateCheck(v: Boolean) {
        backing.update { it.copy(autoUpdateCheck = v) }
        persist { s -> s.copy(autoUpdateCheck = v) }
    }

    /** DataStore 写入是 suspend，在后台作用域持久化，不阻塞 UI（UI 已即时回应用户改动）。 */
    private fun persist(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.edit(transform) }
    }

    /** 显式检查更新（PRD FR-6.1 手动触发需反馈结果）。 */
    fun checkUpdate() {
        viewModelScope.launch {
            backing.update { it.copy(updating = true, updateError = null) }
            try {
                val info = container.updateRepository.checkManually(BuildConfig.VERSION_NAME)
                backing.update { it.copy(updateInfo = info, updating = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backing.update { it.copy(updateError = e.message ?: "检查更新失败", updating = false) }
            }
        }
    }
}