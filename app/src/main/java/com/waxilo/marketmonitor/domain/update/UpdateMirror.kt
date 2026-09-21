package com.waxilo.marketmonitor.domain.update

/**
 * 更新包下载加速前缀（PRD FR-6.2 国内网络加速）。
 *
 * 语义是「拼在原始地址前」，由代理自行转发到 GitHub：
 * `https://gh-proxy.com/` + `https://github.com/o/r/releases/download/...`。
 * 不做域名替换式的镜像——那需要逐家适配规则，且换代理后地址拼不出来。
 */
object UpdateMirror {

    /** 前缀为空（或只有空白）时原样直连；否则规整结尾斜杠后拼接。 */
    fun apply(url: String, prefix: String): String {
        val normalized = prefix.trim()
        if (normalized.isEmpty()) return url
        return normalized.trimEnd('/') + "/" + url
    }
}
