package com.waxilo.marketmonitor.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.BuildConfig
import com.waxilo.marketmonitor.di.AppContainer
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
import java.io.File

/** 设置页状态：载入一次当前设置快照，编辑即时写入仓库。 */
@Immutable
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quoteAsset: String = "USDT",
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val webhookEnabled: Boolean = true,
    val alertPollingSeconds: Int = 5,
    val autoUpdateCheck: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
    /** 检查更新的结果：null 表示未检查或进行中。 */
    val updateInfo: UpdateInfo? = null,
    val updateError: String? = null,
    val updating: Boolean = false,
    /** 下载中：0..1 为进度，null 表示未在下载（total 未知时用 -1 表示不确定进度）。 */
    val downloadProgress: Float? = null,
    /** 已下载并校验通过的安装包；点「安装」时才拉起系统安装器。 */
    val downloadedApk: File? = null,
    /** 已发起安装但缺少「安装未知应用」权限，需要引导用户去授权。 */
    val needInstallPermission: Boolean = false,
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
            backing.update {
                // 重新检查时丢掉上一轮已下载的包：可能换了版本，旧的不能拿来装
                it.copy(updating = true, updateError = null, downloadProgress = null, downloadedApk = null)
            }
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

    /**
     * 下载更新包并校验 SHA-256（PRD 4.6）。
     *
     * 落到 `cacheDir/updates/`：`file_paths.xml` 里声明的就是 `cache-path updates/`，
     * 换目录会让 `FileProvider.getUriForFile` 直接抛 IllegalArgumentException。
     *
     * 校验与「能否应用内安装」的判定都在数据层，这里只负责把进度搬到 UI 上；
     * 拿到不可信产物（缺 sha256）时数据层会抛错，UI 展示错误即可。
     */
    fun downloadUpdate() {
        val info = backing.value.updateInfo ?: return
        if (backing.value.downloadProgress != null) return
        viewModelScope.launch {
            backing.update { it.copy(downloadProgress = -1f, updateError = null, needInstallPermission = false) }
            try {
                val target = File(container.updateDir, info.apkName)
                val file = container.updateRepository.download(info, target) { done, total ->
                    val progress = if (total > 0L) (done.toFloat() / total) else -1f
                    // 进度回调在 IO 线程，写入 StateFlow 是线程安全的
                    backing.update { it.copy(downloadProgress = progress) }
                }
                backing.update { it.copy(downloadProgress = null, downloadedApk = file) }
                installDownloaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backing.update { it.copy(downloadProgress = null, updateError = e.message ?: "下载失败") }
            }
        }
    }

    /**
     * 拉起系统安装器。
     *
     * 返回 false 只说明「没有安装未知应用的权限」（Android 8+ 必须用户显式授权），
     * 这时把状态标成 [SettingsUiState.needInstallPermission]，由 UI 引导去授权页 ——
     * 直接抛异常对用户没有任何可操作性。
     */
    fun installDownloaded() {
        val apk = backing.value.downloadedApk ?: return
        val started = container.installer.install(apk)
        backing.update { it.copy(needInstallPermission = !started) }
    }

    /** 打开「允许安装未知应用」授权页。 */
    fun openInstallPermissionSettings() {
        runCatching { container.startActivity(container.installer.openInstallPermissionSettings()) }
    }
}