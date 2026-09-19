package com.waxilo.marketmonitor.domain.repository

import com.waxilo.marketmonitor.domain.update.ReleaseAsset
import java.io.File

/** 一次更新的完整信息（PRD 4.6）。 */
data class UpdateInfo(
    val currentVersion: String,
    /** 去掉 `v` 前缀的 tag，用于与 versionName 比较与展示。 */
    val latestVersion: String,
    val releaseName: String,
    val notes: String,
    val publishedAt: String?,
    /** 拿不到可安装产物时的兜底入口（浏览器打开 Release 页）。 */
    val pageUrl: String,
    val apk: ReleaseAsset?,
    /** 来自 GitHub digest 或 `.sha256` 边车；为空表示无法验证产物完整性。 */
    val expectedSha256: String? = null,
) {
    /**
     * 只有拿到可信校验值才走应用内安装：PRD 4.6 要求「下载产物必须验证摘要」，
     * 无法验证时退回浏览器打开 Release 页，由用户自行判断。
     */
    val canInstallInApp: Boolean get() = apk != null && apk.downloadUrl.isNotBlank() && expectedSha256 != null
}

interface UpdateRepository {

    /**
     * 检查更新。返回 null 表示无可用更新或检查失败（失败不外抛：
     * 启动时的静默检查不应因为 GitHub 限频打扰用户）。
     */
    suspend fun check(currentVersionName: String): UpdateInfo?

    /** 显式检查（设置页「检查更新」），失败时抛出原因供 UI 提示。 */
    suspend fun checkManually(currentVersionName: String): UpdateInfo?

    /**
     * 下载 APK 到 [target]，完成后校验 SHA-256。
     * 校验不通过会删除文件并抛 [java.io.IOException]，绝不交付未验证的产物。
     */
    suspend fun download(
        info: UpdateInfo,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File
}
