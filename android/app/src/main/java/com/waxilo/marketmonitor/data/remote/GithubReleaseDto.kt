package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.domain.update.ReleaseAsset
import com.waxilo.marketmonitor.domain.update.ReleaseInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
    /** GitHub 为 Release 产物自动计算的 `sha256:<hex>`，旧数据可能缺失。 */
    val digest: String? = null,
)

@Serializable
data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GithubAssetDto> = emptyList(),
)

fun GithubReleaseDto.toDomain(): ReleaseInfo = ReleaseInfo(
    tagName = tagName,
    name = name.ifBlank { tagName },
    body = body,
    publishedAt = publishedAt,
    assets = assets.map {
        ReleaseAsset(
            name = it.name,
            downloadUrl = it.browserDownloadUrl,
            size = it.size,
            digest = it.digest,
        )
    },
    pageUrl = htmlUrl,
)
