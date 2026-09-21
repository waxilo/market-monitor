package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.update.ReleaseAsset
import com.waxilo.marketmonitor.domain.update.ReleaseInfo
import com.waxilo.marketmonitor.domain.update.UpdateMirror
import kotlinx.coroutines.CancellationException
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
 * 所有请求都按**候选地址链**依次尝试（见 [UpdateMirror.apiChain] / [assetChain]）：
 * 所选加速站 → 直连 → 其他站。两个现实问题逼出来的设计——
 * 公益代理随时停摆，单点等于把更新押在它身上；而开着 VPN 时
 * `api.github.com` 常因共享机房出口 IP 被 GitHub 直接 403（下载却正常），
 * 所以 API 也必须能改走中转，不能只加速下载。
 */
class GithubReleaseApi(
    private val client: OkHttpClient,
    private val owner: String,
    private val repo: String,
    private val apiBase: String = "https://api.github.com",
    private val mirror: () -> UpdateMirror = { UpdateMirror.NATIVE },
) {

    /** 最新正式版发布；GitHub 的 `latest` 已排除 draft 与 prerelease。 */
    suspend fun latestRelease(): ReleaseInfo {
        val path = "/repos/$owner/$repo/releases/latest"
        val element = getJson(mirror().apiChain(path, apiBase), "检查更新")
        return MarketJson.DEFAULT.decodeFromJsonElement(GithubReleaseDto.serializer(), element).toDomain()
    }

    /** 读取 `.sha256` 边车文件，取第一个空白前的十六进制串。 */
    suspend fun sidecarChecksum(asset: ReleaseAsset): String? {
        val text = firstOk(assetChain(asset.downloadUrl), "读取校验值") { url ->
            request(url, "User-Agent" to USER_AGENT)
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
        target.parentFile?.mkdirs()
        var lastError: Exception? = null
        for (url in assetChain(asset.downloadUrl)) {
            try {
                return downloadOnce(url, target, expectedSha256, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                // 换下一个源：半截文件不能留给安装器
                target.delete()
            }
        }
        throw lastError ?: IOException("下载失败：没有可用的下载地址")
    }

    private suspend fun downloadOnce(
        url: String,
        target: File,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // 阻塞读不会自动响应取消，逐块检查协程状态，让用户离开页面能真正停止下载
        val job = coroutineContext[kotlinx.coroutines.Job]
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream")
            .build()
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
            throw IOException("安装包校验失败，请换个加速站重试")
        }
        target
    }

    private suspend fun getJson(urls: List<String>, what: String): JsonElement {
        val text = firstOk(urls, what) { url ->
            request(
                url,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            )
        }
        return MarketJson.DEFAULT.parseToJsonElement(text)
    }

    /** 产物地址链：所选加速站 → 直连 → 其他站。 */
    private fun assetChain(url: String): List<String> = mirror().assetChain(url)

    /**
     * 依次尝试候选地址，第一个成功即用。
     * 全失败时把最后一个错误抛回去，并说明试过几个源——只报 403 用户不知道该干什么。
     */
    private suspend fun firstOk(urls: List<String>, what: String, fetch: suspend (String) -> String): String {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                return fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MarketApiException) {
                // 404 是「这个文件本来就没有」（比如发布没带 .sha256 边车），
                // 换一家只会再 404 一次，直接停手让调用方按缺失处理
                if (e.httpCode == 404) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }
        val tail = if (urls.size > 1) "（已依次尝试 ${urls.size} 个源）" else ""
        throw IOException("$what 失败：${lastError?.message ?: "无可用地址"}$tail")
    }

    private suspend fun request(url: String, vararg headers: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = if (response.code == 403) "（可能被限流或拒绝该出口 IP）" else ""
                    throw MarketApiException(response.code, 0, "HTTP ${response.code}$hint")
                }
                response.body?.string().orEmpty()
            }
        }

    companion object {
        /** GitHub API 强制要求 User-Agent，缺失返回 403。 */
        const val USER_AGENT = "market-monitor-android"
    }
}
