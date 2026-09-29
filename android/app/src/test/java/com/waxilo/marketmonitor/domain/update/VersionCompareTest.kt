package com.waxilo.marketmonitor.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `解析带前缀与缺段的版本号`() {
        assertEquals(SemVer(1, 2, 3), VersionCompare.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 0), VersionCompare.parse("1.2"))
        assertEquals(SemVer(1, 0, 0, "beta.1"), VersionCompare.parse("v1.0.0-beta.1+build7"))
        assertNull(VersionCompare.parse("release-3"))
        assertNull(VersionCompare.parse(""))
        assertNull(VersionCompare.parse("1.x.3"))
    }

    @Test
    fun `按数值而非字典序比较`() {
        assertTrue(VersionCompare.compare(SemVer(1, 10, 0), SemVer(1, 9, 0)) > 0)
        assertTrue(VersionCompare.compare(SemVer(0, 2, 0), SemVer(0, 10, 0)) < 0)
        assertEquals(0, VersionCompare.compare(SemVer(1, 2, 3), SemVer(1, 2, 3)))
        assertTrue(VersionCompare.compare(SemVer(2, 0, 0), SemVer(1, 99, 99)) > 0)
    }

    @Test
    fun `预发布版低于正式版`() {
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0, "rc.1"), SemVer(1, 0, 0)) < 0)
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0), SemVer(1, 0, 0, "rc.1")) > 0)
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0, "beta"), SemVer(1, 0, 0, "rc")) < 0)
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0, "alpha.1"), SemVer(1, 0, 0, "alpha")) > 0)
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0, "alpha.2"), SemVer(1, 0, 0, "alpha.10")) < 0)
        assertTrue(VersionCompare.compare(SemVer(1, 0, 0, "1"), SemVer(1, 0, 0, "alpha")) < 0)
    }

    @Test
    fun `最新版本与当前 versionName 的更新判定`() {
        assertTrue(VersionCompare.hasUpdate("v0.2.0", "0.1.0"))
        assertFalse(VersionCompare.hasUpdate("v0.1.0", "0.1.0"))
        assertFalse(VersionCompare.hasUpdate("v0.0.9", "0.1.0"))
        // 解析失败一律视为无更新：宁可不提醒，也不因为产物名格式变化弹假更新
        assertFalse(VersionCompare.hasUpdate("nightly-2026", "0.1.0"))
        assertFalse(VersionCompare.hasUpdate(null, "0.1.0"))
        assertFalse(VersionCompare.hasUpdate("v0.2.0", null))
    }

    @Test
    fun `预发布版可升级到正式版`() {
        assertTrue(VersionCompare.hasUpdate("v1.0.0", "1.0.0-beta.1"))
        assertTrue(VersionCompare.hasUpdate("v1.0.0-beta.2", "1.0.0-beta.1"))
        assertFalse(VersionCompare.hasUpdate("v1.0.0-beta.1", "1.0.0"))
    }

    @Test
    fun `从产物名里取版本`() {
        assertEquals("0.9.26", VersionCompare.versionFromAssetName("market-monitor-0.9.26.apk"))
        // ABI 后缀在版本号后面，且 ABI 名不含点 —— 不会被误当成版本
        assertEquals("0.9.26", VersionCompare.versionFromAssetName("market-monitor-0.9.26-arm64-v8a.apk"))
        assertEquals("0.9.26", VersionCompare.versionFromAssetName("market-monitor-v0.9.26.apk"))
        // 边车与应用内下载都靠这个后缀配对，名字里照样带版本
        assertEquals("0.9.26", VersionCompare.versionFromAssetName("market-monitor-0.9.26.apk.sha256"))
    }

    @Test
    fun `产物名里没有版本时返回 null 而不是瞎猜`() {
        assertNull(VersionCompare.versionFromAssetName("market-monitor.apk"))
        assertNull(VersionCompare.versionFromAssetName("notes.txt"))
        // `2026-09-29` 这种日期没有点，不构成版本
        assertNull(VersionCompare.versionFromAssetName("2026-09-29-report.txt"))
    }
}

class ReleaseInfoTest {

    private fun asset(name: String, digest: String? = null) = ReleaseAsset(
        name = name,
        downloadUrl = "https://example.com/$name",
        size = 1_024_000,
        digest = digest,
    )

    private fun release(vararg assets: ReleaseAsset) = ReleaseInfo(
        // 固定通道：tag 恒为 android-latest、不带版本，版本在产物名里
        tagName = "android-latest",
        name = "Android 更新通道",
        body = "## 更新内容\n- 新增自定义周期",
        publishedAt = "2026-09-19T08:00:00Z",
        assets = assets.toList(),
    )

    @Test
    fun `版本号来自产物名 —— 固定通道的 tag 不带版本`() {
        assertEquals("0.2.0", release(asset("market-monitor-0.2.0.apk")).apkVersion())
        // 通道里没有可识别的 APK → 读不到版本，上层据此判「无更新」而不是瞎猜
        assertNull(release(asset("notes.txt")).apkVersion())
    }

    @Test
    fun `产物名带 ABI 后缀也能取到版本`() {
        val info = release(asset("market-monitor-0.2.0-arm64-v8a.apk"))
        assertEquals("0.2.0", info.apkVersion(listOf("arm64-v8a")))
    }

    @Test
    fun `单包直接命中`() {
        val info = release(asset("app-debug.apk"), asset("notes.txt"))
        assertEquals("app-debug.apk", info.apkAsset()?.name)
    }

    @Test
    fun `多 ABI 时优先设备架构`() {
        val info = release(
            asset("market-armeabi-v7a.apk"),
            asset("market-arm64-v8a.apk"),
        )
        assertEquals("market-arm64-v8a.apk", info.apkAsset(listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("market-armeabi-v7a.apk", info.apkAsset(listOf("armeabi-v7a"))?.name)
    }

    @Test
    fun `无架构匹配时回退通用包`() {
        val info = release(asset("app-universal.apk"), asset("app-arm64-v8a.apk"))
        assertEquals("app-universal.apk", info.apkAsset(listOf("x86"))?.name)
    }

    @Test
    fun `没有 APK 资产时返回空以走浏览器兜底`() {
        assertNull(release(asset("source.zip")).apkAsset())
    }

    @Test
    fun `校验值优先用 GitHub digest 并归一化前缀`() {
        val withDigest = asset("app-debug.apk", digest = "sha256:ABCDEF")
        assertEquals("abcdef", withDigest.sha256Hex?.lowercase())
        assertNull(asset("app-debug.apk").sha256Hex)
    }

    @Test
    fun `边车校验文件按同名匹配`() {
        val apk = asset("app-debug.apk")
        val info = release(asset("other.apk.sha256"), asset("app-debug.apk.sha256"), apk)
        assertEquals("app-debug.apk.sha256", info.checksumAsset(apk)?.name)
    }
}
