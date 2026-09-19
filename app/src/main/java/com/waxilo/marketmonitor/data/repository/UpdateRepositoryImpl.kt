package com.waxilo.marketmonitor.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.waxilo.marketmonitor.data.remote.GithubReleaseApi
import com.waxilo.marketmonitor.domain.repository.UpdateInfo
import com.waxilo.marketmonitor.domain.repository.UpdateRepository
import com.waxilo.marketmonitor.domain.update.VersionCompare
import java.io.File
import java.io.IOException

/**
 * 应用内更新（PRD 4.6）：GitHub Releases 取产物 → 校验 SHA-256 → 拉起系统安装器。
 * 只做「有没有新版本」的判定，不缓存 Release 数据，避免展示过期的大小与说明。
 */
class UpdateRepositoryImpl(
    private val api: GithubReleaseApi,
    private val abiPreferences: List<String>,
) : UpdateRepository {

    override suspend fun check(currentVersionName: String): UpdateInfo? =
        runCatching { resolve(currentVersionName) }.getOrNull()

    override suspend fun checkManually(currentVersionName: String): UpdateInfo? =
        resolve(currentVersionName)

    private suspend fun resolve(currentVersionName: String): UpdateInfo? {
        val release = api.latestRelease()
        if (!VersionCompare.hasUpdate(release.tagName, currentVersionName)) return null
        val apk = release.apkAsset(abiPreferences)
        val expected = apk?.let { asset ->
            asset.sha256Hex ?: release.checksumAsset(asset)?.let { runCatching { api.sidecarChecksum(it) }.getOrNull() }
        }
        return UpdateInfo(
            currentVersion = currentVersionName,
            latestVersion = release.tagName.removePrefix("v"),
            releaseName = release.name,
            notes = release.body,
            publishedAt = release.publishedAt,
            pageUrl = release.pageUrl,
            apk = apk,
            expectedSha256 = expected,
        )
    }

    override suspend fun download(
        info: UpdateInfo,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val asset = info.apk ?: throw IOException("该版本没有可安装的产物")
        if (!info.canInstallInApp) throw IOException("未获得可信校验值，请从 Release 页面下载")
        return api.download(asset, target, info.expectedSha256, onProgress)
    }
}

/**
 * 拉起系统安装器（PRD 4.6）。Android 8+ 需要用户授予「安装未知应用」权限，
 * 未授权时引导到本应用的权限页而不是直接抛异常。
 */
class ApkInstaller(private val context: Context, private val authority: String) {

    /** Android 8+ 需要用户授予「安装未知应用」权限（PRD 4.6 安装引导）。 */
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** @return true 表示已发起安装；false 表示权限未授予，调用方应引导用户去设置页。 */
    fun install(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (!canRequestInstalls()) return false
        return runCatching { launchInstaller(file) }.isSuccess
    }

    /** 打开「允许安装未知应用」授权页，定位到本应用。 */
    fun openInstallPermissionSettings(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
