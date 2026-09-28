package com.waxilo.marketmonitor.data.repository

import androidx.compose.runtime.Immutable
import com.waxilo.marketmonitor.domain.repository.UpdateInfo
import com.waxilo.marketmonitor.domain.repository.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 一次更新会话的全部状态：检查结果 + 下载进度 + 安装引导。 */
@Immutable
data class UpdateState(
    /** 检查更新的结果；null 表示没查到新版本或尚未检查。 */
    val info: UpdateInfo? = null,
    val checking: Boolean = false,
    /** 0..1 为进度；null 表示未在下载；-1 表示总量未知（服务端没给 Content-Length）。 */
    val progress: Float? = null,
    /** 已下载并校验通过的安装包。 */
    val apk: File? = null,
    val error: String? = null,
    /** 已发起安装但缺少「安装未知应用」权限，需要引导用户去授权。 */
    val needInstallPermission: Boolean = false,
)

/**
 * 更新中心：检查更新与下载安装包。
 *
 * **必须活在应用级作用域**，不能放在 `SettingsViewModel.viewModelScope` 里：
 * 下载动辄数十秒，而用户切到别的页面、甚至退出设置页都是常态 ——
 * ViewModel 一被清除，`viewModelScope` 连同下载协程一起取消，
 * 表现就是「点下载后一换页面下载就断了」。
 *
 * 状态也一并放在这里而不是 ViewModel，理由有两层：
 * 1. 下载在后台完成时设置页可能已经销毁，回到设置页的是**新的** ViewModel，
 *    它得能读到「已经在下载 / 已下载好」，否则用户会看到空白界面，再点一次又重下一遍；
 * 2. [UpdateState.info] 同样是会话级的 —— 若留在 ViewModel 里，切一次页面回来
 *    整张「发现新版本」卡片就消失了，用户会以为下载丢了（实际文件还在）。
 */
class UpdateCenter(
    private val repository: UpdateRepository,
    private val installer: ApkInstaller,
    private val dir: File,
    private val scope: CoroutineScope,
) {

    private val backing = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = backing.asStateFlow()

    /**
     * 显式检查更新（PRD FR-6.1 手动触发需反馈结果）。
     *
     * 检查结果与下载产物是一体的：换了版本，上一轮下好的包就不能再拿来装，
     * 所以这里一并清掉 [UpdateState.apk] 与下载进度。
     */
    fun check(currentVersionName: String) {
        if (backing.value.checking) return
        scope.launch {
            backing.update {
                it.copy(
                    checking = true,
                    error = null,
                    info = null,
                    progress = null,
                    apk = null,
                    needInstallPermission = false,
                )
            }
            try {
                val info = repository.checkManually(currentVersionName)
                backing.update { it.copy(info = info, checking = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backing.update { it.copy(checking = false, error = e.message ?: "检查更新失败") }
            }
        }
    }

    /**
     * 下载 [UpdateState.info] 指向的安装包并校验 SHA-256，完成后自动拉起安装器。
     *
     * 正在下载时重复调用直接忽略：按钮可能被连点，而第二次下载只会把
     * 第一个连接的进度写乱（两个协程往同一个 StateFlow 里塞各自的百分比）。
     */
    fun download() {
        val info = backing.value.info ?: return
        if (backing.value.progress != null) return
        scope.launch {
            backing.update {
                it.copy(progress = -1f, error = null, apk = null, needInstallPermission = false)
            }
            try {
                val target = File(dir, info.apkName)
                val file = repository.download(info, target) { done, total ->
                    // 进度回调在 IO 线程；StateFlow 的 update 本身线程安全
                    val progress = if (total > 0L) done.toFloat() / total else -1f
                    backing.update { it.copy(progress = progress) }
                }
                backing.update { it.copy(progress = null, apk = file) }
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backing.update { it.copy(progress = null, error = e.message ?: "下载失败") }
            }
        }
    }

    /**
     * 拉起系统安装器。下载完成后会自动调一次；用户点「安装」按钮时也走这里。
     *
     * 返回 false 只说明「没有安装未知应用的权限」（Android 8+ 必须用户显式授权），
     * 这时把状态标成 [UpdateState.needInstallPermission]，由 UI 引导去授权页 ——
     * 直接抛异常对用户没有任何可操作性。
     */
    fun install() {
        val apk = backing.value.apk ?: return
        val started = installer.install(apk)
        backing.update { it.copy(needInstallPermission = !started) }
    }
}
