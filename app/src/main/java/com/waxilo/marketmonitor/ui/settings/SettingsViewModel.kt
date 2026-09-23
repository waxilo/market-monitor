package com.waxilo.marketmonitor.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.BuildConfig
import com.waxilo.marketmonitor.data.remote.MarketApiException
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.ThemeMode
import com.waxilo.marketmonitor.domain.repository.UpdateInfo
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 单个合约行情接口的一键检测结果。 */
sealed interface ProbeOutcome {
    data class Reachable(val latencyMs: Long) : ProbeOutcome
    data class Failed(val reason: String) : ProbeOutcome
}

/** 设置页状态：载入一次当前设置快照，编辑即时写入仓库。 */
@Immutable
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quoteAsset: String = "USDT",
    /** 用户选定的合约行情接口（FuturesEndpoints 之一）。 */
    val futuresHost: String = FuturesEndpoints.DEFAULT_URL,
    /** 一键检测结果：baseUrl → 结论；不在表里的即「未检测」。 */
    val probeResults: Map<String, ProbeOutcome> = emptyMap(),
    val probing: Boolean = false,
    val notificationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val alertPollingSeconds: Int = 5,
    val autoUpdateCheck: Boolean = true,
    /** 更新加速站；NATIVE 表示直连 GitHub。 */
    val updateMirror: UpdateMirror = UpdateMirror.NATIVE,
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
                // 只覆盖设置类字段：更新类字段由下面那个订阅负责镜像。
                // 这里若整体 `backing.value = SettingsUiState(...)`，就会把订阅刚填进去的
                // 「发现新版本 / 下载进度」再清成 null —— DataStore 读取比 StateFlow 订阅慢，
                // 顺序上必然后到，于是表现为「切回设置页卡片消失」。
                backing.update { s ->
                    s.copy(
                        themeMode = it.themeMode,
                        quoteAsset = it.quoteAsset,
                        futuresHost = it.futuresRestHost,
                        notificationEnabled = it.notificationEnabled,
                        soundEnabled = it.soundEnabled,
                        vibrateEnabled = it.vibrateEnabled,
                        alertPollingSeconds = it.alertPollingSeconds,
                        autoUpdateCheck = it.autoUpdateCheck,
                        updateMirror = it.updateMirror,
                    )
                }
            }
        }
        // 更新相关的状态（检查结果 / 下载进度 / 已下载的包）全部是**应用级**的，
        // 见 UpdateCenter。这里只做镜像：下载期间离开设置页时本 ViewModel 会被清除，
        // 但检查与下载继续；再回来时是新的 ViewModel，靠这次订阅就能立刻显示
        // 「发现新版本 0.9.1 · 已下载 42%」而不是一张空白的初始界面。
        viewModelScope.launch {
            container.updateCenter.state.collect { s ->
                backing.update {
                    it.copy(
                        updateInfo = s.info,
                        updating = s.checking,
                        downloadProgress = s.progress,
                        downloadedApk = s.apk,
                        updateError = s.error,
                        needInstallPermission = s.needInstallPermission,
                    )
                }
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

    /**
     * 一键检测：对全部内置候选接口在本机**并行**各发一次 ping，结果逐行回填。
     * 连通性因网络环境（地区/代理）而异，开发机上的探测不作数，必须在用户设备上测——
     * 这正是把「选哪个接口」交给用户的原因。
     */
    fun detectEndpoints() {
        if (state.value.probing) return
        backing.update { it.copy(probing = true, probeResults = emptyMap()) }
        viewModelScope.launch {
            coroutineScope {
                FuturesEndpoints.ALL.map { endpoint ->
                    async {
                        val outcome = runCatching { container.probeFuturesEndpoint(endpoint.baseUrl) }
                            .fold(
                                onSuccess = { ProbeOutcome.Reachable(it) as ProbeOutcome },
                                onFailure = { e ->
                                    // runCatching 会把取消也吞成「失败」：协程被撤就安静退出，
                                    // 别给正在离开的页面写一条假的检测结论
                                    if (e is CancellationException) throw e
                                    ProbeOutcome.Failed(probeFailure(e))
                                },
                            )
                        backing.update { s -> s.copy(probeResults = s.probeResults + (endpoint.baseUrl to outcome)) }
                    }
                }.forEach { it.await() }
            }
            backing.update { it.copy(probing = false) }
        }
    }

    /**
     * 选定合约行情接口：持久化并清空合约行情缓存。
     * Aster 与币安合约是独立盘口，旧盘口的快照/蜡烛不清掉会和新数据混进
     * 同一 (FUTURES, symbol) 画出假 K 线；交易对清单也一并清空，搜索页会向新接口重新同步。
     */
    fun setFuturesHost(url: String) {
        val normalized = FuturesEndpoints.normalize(url)
        if (normalized == state.value.futuresHost) return
        backing.update { it.copy(futuresHost = normalized) }
        persist { s -> s.copy(futuresRestHost = normalized) }
        viewModelScope.launch { container.marketRepository.clearMarketCache(MarketType.FUTURES) }
    }

    /** 网络异常的长文案（堆栈味）在手机上没意义，折成一行短结论。 */
    private fun probeFailure(t: Throwable): String = when (t) {
        is MarketApiException -> "HTTP ${t.httpCode}"
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "域名解析失败"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "连不上"
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

    fun setAlertPollingSeconds(v: Int) {
        backing.update { it.copy(alertPollingSeconds = v) }
        persist { s -> s.copy(alertPollingSeconds = v) }
    }

    fun setAutoUpdateCheck(v: Boolean) {
        backing.update { it.copy(autoUpdateCheck = v) }
        persist { s -> s.copy(autoUpdateCheck = v) }
    }

    fun setUpdateMirror(v: UpdateMirror) {
        backing.update { it.copy(updateMirror = v) }
        persist { s -> s.copy(updateMirror = v) }
    }

    /** DataStore 写入是 suspend，在后台作用域持久化，不阻塞 UI（UI 已即时回应用户改动）。 */
    private fun persist(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.edit(transform) }
    }

    /**
     * 显式检查更新（PRD FR-6.1 手动触发需反馈结果）。
     *
     * 检查本身也交给应用级 [com.waxilo.marketmonitor.data.repository.UpdateCenter]：
     * 结果要与下载产物同生共死（换了版本，上一轮下好的包就不能再装），
     * 而且切页面回来时那张「发现新版本」卡片必须还在。
     */
    fun checkUpdate() {
        container.updateCenter.check(BuildConfig.VERSION_NAME)
    }

    /** 请求下载更新包并校验 SHA-256（PRD 4.6）；实际下载在应用级作用域里跑。 */
    fun downloadUpdate() {
        container.updateCenter.download()
    }

    /** 拉起系统安装器；缺「安装未知应用」权限时状态会被标成需授权，由 UI 引导。 */
    fun installDownloaded() {
        container.updateCenter.install()
    }

    /** 打开「允许安装未知应用」授权页。 */
    fun openInstallPermissionSettings() {
        runCatching { container.startActivity(container.installer.openInstallPermissionSettings()) }
    }
}