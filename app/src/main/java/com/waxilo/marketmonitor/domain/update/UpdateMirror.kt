package com.waxilo.marketmonitor.domain.update

/**
 * 更新加速站（PRD FR-6.2 国内网络加速）。
 *
 * 语义是「拼在原始地址前」，由代理自行转发到 GitHub：
 * `https://gh-proxy.com/` + `https://github.com/o/r/releases/download/...`。
 * 不做域名替换式的镜像——那需要逐家适配规则，且换代理后地址拼不出来。
 *
 * 候选地址一律**成链给出**而不是只出一个：加速站随时可能停摆（这类公益代理
 * 寿命以周计），单一地址等于把更新押在它身上；链上只要有一个通，用户就拿到包。
 *
 * [apiCapable] 记录「这家是否中转 api.github.com」——实测只有部分代理放开，
 * 代不了 API 的站放进检查更新的候选里只会白跑一趟 403。
 */
enum class UpdateMirror(
    val key: String,
    val label: String,
    /** 拼在原始地址前的前缀；空串表示直连 GitHub。 */
    val prefix: String,
    val apiCapable: Boolean,
) {
    /** 默认项：不加速。 */
    NATIVE("native", "GitHub 原生", "", apiCapable = false),
    GH_PROXY_COM("gh-proxy.com", "gh-proxy.com", "https://gh-proxy.com/", apiCapable = true),
    GHFAST_TOP("ghfast.top", "ghfast.top", "https://ghfast.top/", apiCapable = false),
    GHPROXY_NET("ghproxy.net", "ghproxy.net", "https://ghproxy.net/", apiCapable = false),
    GH_PROXY_ORG("gh-proxy.org", "gh-proxy.org", "https://gh-proxy.org/", apiCapable = true),
    ;

    /** 原样直连时返回自身，否则前缀 + 原地址（前缀统一规整成一个结尾斜杠）。 */
    fun accelerated(url: String): String {
        if (prefix.isEmpty()) return url
        return prefix.trimEnd('/') + "/" + url
    }

    /**
     * 检查更新的候选：所选站（仅当它代 API）→ 直连 → 其他代 API 的站。
     *
     * 直连排在所选站之后：**开 VPN 时 api.github.com 反而更容易 403** ——
     * VPN 出口是共享机房 IP，GitHub 按 IP 限流甚至直接拒绝，而 github.com 的
     * 资产下载不受影响。所以「下载能成、检查报 403」时，让 API 走代理中转。
     */
    fun apiChain(path: String, apiBase: String): List<String> {
        val direct = apiBase.trimEnd('/') + path
        return buildList {
            if (apiCapable) add(accelerated(direct))
            add(direct)
            API_CAPABLE.filter { it != this@UpdateMirror }.forEach { add(it.accelerated(direct)) }
        }.distinct()
    }

    /** 下载产物 / 校验值的候选：所选站 → 直连 → 其他站。 */
    fun assetChain(url: String): List<String> = buildList {
        add(accelerated(url))
        if (this@UpdateMirror != NATIVE) add(url)
        ENTRIES.filter { it != NATIVE && it != this@UpdateMirror }.forEach { add(it.accelerated(url)) }
    }.distinct()

    companion object {
        private val ENTRIES = entries.toList()
        private val API_CAPABLE = ENTRIES.filter { it.apiCapable }

        /**
         * 解析存下来的值。
         *
         * 旧版本让用户手填前缀，因此这里也认前缀：能对上内置站的保留选择，
         * 对不上的自定义前缀只能放弃（加速站已改为内置下拉，不再有自由文本入口）。
         */
        fun fromStored(raw: String?): UpdateMirror {
            val value = raw?.trim().orEmpty()
            ENTRIES.firstOrNull { it.key == value }?.let { return it }
            if (value.isEmpty()) return NATIVE
            val normalized = value.trimEnd('/')
            return ENTRIES.firstOrNull { it.prefix.isNotEmpty() && it.prefix.trimEnd('/') == normalized }
                ?: NATIVE
        }
    }
}
