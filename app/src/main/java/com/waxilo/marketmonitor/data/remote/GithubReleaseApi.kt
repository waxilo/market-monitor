package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.update.ReleaseAsset
import com.waxilo.marketmonitor.domain.update.ReleaseInfo
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * GitHub Releases 读取与产物下载（PRD 4.6 应用内更新）。
 * 未登录调用有 60 次/小时的额度，检查频率由设置项与启动节流控制。
 *
 * [proxyPrefix] 是设置里的下载加速前缀（PRD FR-6.2）：只作用于产物下载与校验值读取，
 * API 检查始终保持直连——加速代理普遍只中转 github.com 的资产，代 API 反而更不稳。
 */
class GithubReleaseApi(
    private val client: OkHttpClient,
    private val owner: String,
    private val repo: String,
    private val apiBase: String = "https://api.github.com",
    private val proxyPrefix: () -> String = { "" },
) {

    /** 产物地址套上加速前缀；前缀为空时原样直连。 */
    private fun accelerated(url: String): String = UpdateMirror.apply(url, proxyPrefix())

    /** 最新正式版发布；GitHub 的 `latest` 已排除 draft 与 prerelease。 */
    suspend fun latestRelease(): ReleaseInfo {
        val element = get("$apiBase/repos/$owner/$repo/releases/latest")
        return MarketJson.DEFAULT.decodeFromJsonElement(GithubReleaseDto.serializer(), element).toDomain()
    }

    /** 读取 `.sha256` 边车文件，取第一个空白前的十六进制串。 */
    suspend fun sidecarChecksum(asset: ReleaseAsset): String? {
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(accelerated(asset.downloadUrl)).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw MarketApiException(response.code, 0, "读取校验值失败：HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
        return text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length >= 32 }
    }

    /**
     * 流式下载到 [target] 并边写边算 SHA-256。
     * 校验不通过即删除文件——半包或被替换的包都不能交给安装器。
     */
    suspend fun download(
        asset: ReleaseAsset,
        target: File,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        if (asset.downloadUrl.isBlank()) throw IOException("更新包地址为空")
        return withContext(Dispatchers.IO) {
            // 阻塞读不会自动响应取消，逐块检查协程状态，让用户离开页面能真正停止下载
            val job = coroutineContext[kotlinx.coroutines.Job]
            val request = Request.Builder()
                .url(accelerated(asset.downloadUrl))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream")
                .build()
            target.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw MarketApiException(response.code, 0, "下载失败：HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("下载响应为空")
                val total = response.body?.contentLength() ?: -1L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                FileOutputStream(target).use { out ->
                    input.use { source ->
                        var downloaded = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                            downloaded += read
                            job?.ensureActive()
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
                target.delete()
                throw IOException("安装包校验失败，请重新下载")
            }
            target
        }
    }

    private suspend fun get(url: String): JsonElement {
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw MarketApiException(response.code, 0, "检查更新失败：HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
        return MarketJson.DEFAULT.parseToJsonElement(text)
    }

    companion object {
        /** GitHub API 强制要求 User-Agent，缺失返回 403。 */
        const val USER_AGENT = "market-monitor-android"
    }
}
