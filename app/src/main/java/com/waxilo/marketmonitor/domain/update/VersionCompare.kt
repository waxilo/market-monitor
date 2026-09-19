package com.waxilo.marketmonitor.domain.update

/**
 * 语义化版本（PRD 4.6：App 内更新按 tag `vX.Y.Z` 比较，不能靠字符串相等）。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布标识，如 "beta.1"；正式版为空字符串。 */
    val preRelease: String = "",
) {
    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-$preRelease"
}

object VersionCompare {

    /** 兼容 `v1.2.3` / `1.2` / `1.2.3-beta.1+build5`。解析失败返回 null。 */
    fun parse(raw: String): SemVer? {
        var text = raw.trim().removePrefix("v").removePrefix("V")
        if (text.isEmpty()) return null
        val plusIndex = text.indexOf('+')
        if (plusIndex >= 0) text = text.substring(0, plusIndex)
        var preRelease = ""
        val dashIndex = text.indexOf('-')
        if (dashIndex >= 0) {
            preRelease = text.substring(dashIndex + 1)
            text = text.substring(0, dashIndex)
        }
        val parts = text.split('.').map { it.trim() }
        if (parts.isEmpty() || parts.any { it.isNotEmpty() && !it.all(Char::isDigit) }) return null

        fun num(index: Int): Int = parts.getOrNull(index)?.toIntOrNull() ?: 0
        return SemVer(num(0), num(1), num(2), preRelease)
    }

    fun compare(a: SemVer, b: SemVer): Int {
        val core = compareInts(a.major, b.major)
            .orElse { compareInts(a.minor, b.minor) }
            .orElse { compareInts(a.patch, b.patch) }
        if (core != 0) return core
        return comparePreRelease(a.preRelease, b.preRelease)
    }

    /** 最新版本 tag 是否比当前 versionName 更新（解析失败一律视为无更新，避免误弹提示）。 */
    fun hasUpdate(latestTag: String?, currentVersionName: String?): Boolean {
        val latest = latestTag?.let(::parse) ?: return false
        val current = currentVersionName?.let(::parse) ?: return false
        return compare(latest, current) > 0
    }

    private fun comparePreRelease(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return 1 // 正式版大于任何预发布版
        if (b.isEmpty()) return -1
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            // 前缀相同时，标识符段少者更小（1.0.0-alpha < 1.0.0-alpha.1）
            val l = left.getOrNull(i) ?: return -1
            val r = right.getOrNull(i) ?: return 1
            val result = compareIdentifier(l, r)
            if (result != 0) return result
        }
        return 0
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val lNum = left.toIntOrNull()
        val rNum = right.toIntOrNull()
        return when {
            lNum != null && rNum != null -> compareInts(lNum, rNum)
            lNum != null -> -1 // 数字标识符小于字母标识符
            rNum != null -> 1
            else -> left.compareTo(right)
        }
    }

    private fun compareInts(a: Int, b: Int): Int = a.compareTo(b)

    private inline fun Int.orElse(block: () -> Int): Int = if (this != 0) this else block()
}

/** GitHub Release 资产。`digest` 为 GitHub 侧的 `sha256:<hex>`，可能为空。 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String? = null,
) {
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
    val isSha256Sidecar: Boolean get() = name.endsWith(".sha256", ignoreCase = true)

    /** 归一化为纯十六进制小写。 */
    val sha256Hex: String?
        get() = digest?.substringAfter(':')?.takeIf { it.isNotBlank() }
}

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
    /** Release 页面地址，无法应用内安装时用它走浏览器。 */
    val pageUrl: String = "",
) {
    val version: SemVer? get() = VersionCompare.parse(tagName)

    /** 优先匹配设备 ABI 的 APK，其次通用包，最后任意 APK。 */
    fun apkAsset(abiPreferences: List<String> = emptyList()): ReleaseAsset? {
        val apks = assets.filter { it.isApk }
        if (apks.isEmpty()) return null
        if (apks.size == 1) return apks.first()
        for (abi in abiPreferences) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { !it.name.contains('-') } ?: apks.first()
    }

    /** 发布流程产出的 `<apk>.sha256` 边车文件（GitHub digest 缺失时的兜底）。 */
    fun checksumAsset(apk: ReleaseAsset): ReleaseAsset? =
        assets.firstOrNull { it.isSha256Sidecar && it.name.removeSuffix(".sha256") == apk.name }
            ?: assets.firstOrNull { it.isSha256Sidecar }
}
