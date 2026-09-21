package com.waxilo.marketmonitor.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMirrorTest {

    private val githubUrl = "https://github.com/waxilo/market-monitor/releases/download/0.9.8/app.apk"
    private val apiPath = "/repos/waxilo/market-monitor/releases/latest"
    private val apiBase = "https://api.github.com"

    @Test
    fun `原生站不改写地址`() {
        assertEquals(githubUrl, UpdateMirror.NATIVE.accelerated(githubUrl))
    }

    @Test
    fun `加速站把原地址整体拼在前缀后`() {
        assertEquals(
            "https://gh-proxy.com/$githubUrl",
            UpdateMirror.GH_PROXY_COM.accelerated(githubUrl),
        )
    }

    @Test
    fun `候选链首选所选加速站`() {
        val chain = UpdateMirror.GH_PROXY_COM.apiChain(apiPath, apiBase)
        assertEquals("https://gh-proxy.com/$apiBase$apiPath", chain.first())
    }

    /**
     * 回归：开 VPN 时 api.github.com 常被限流成 403，直连必须留在候选链里，
     * 否则选了加速站之后一旦它停摆，检查更新就彻底没救。
     */
    @Test
    fun `直连始终在 API 候选链里`() {
        assertTrue(UpdateMirror.GH_PROXY_COM.apiChain(apiPath, apiBase).contains("$apiBase$apiPath"))
        assertTrue(UpdateMirror.GHFAST_TOP.apiChain(apiPath, apiBase).contains("$apiBase$apiPath"))
    }

    /** 代不了 API 的站不能进候选链：请求过去只会白拿一个 403。 */
    @Test
    fun `不支持中转 API 的站不出现在 API 候选链`() {
        UpdateMirror.entries.filter { !it.apiCapable && it.prefix.isNotEmpty() }.forEach { weak ->
            val chain = weak.apiChain(apiPath, apiBase)
            assertTrue(weak.name, chain.all { !it.startsWith(weak.prefix) })
        }
    }

    @Test
    fun `候选链去重且包含全部代 API 的站`() {
        val capable = UpdateMirror.entries.count { it.apiCapable }
        val chain = UpdateMirror.NATIVE.apiChain(apiPath, apiBase)
        assertEquals(chain.distinct().size, chain.size)
        assertEquals(capable + 1, chain.size)
    }

    @Test
    fun `下载候选链包含直连与其余各站`() {
        val chain = UpdateMirror.GHFAST_TOP.assetChain(githubUrl)
        assertEquals("https://ghfast.top/$githubUrl", chain.first())
        assertTrue(chain.contains(githubUrl))
        assertEquals(UpdateMirror.entries.size, chain.size)
    }

    @Test
    fun `存过的值能读回本站`() {
        UpdateMirror.entries.forEach { assertEquals(it, UpdateMirror.fromStored(it.key)) }
    }

    /** 旧版本让用户手填前缀，升级后认得内置站就不必回到直连。 */
    @Test
    fun `旧的手填前缀按站点归一`() {
        assertEquals(UpdateMirror.GH_PROXY_COM, UpdateMirror.fromStored("https://gh-proxy.com/"))
        assertEquals(UpdateMirror.GH_PROXY_COM, UpdateMirror.fromStored("https://gh-proxy.com"))
        assertEquals(UpdateMirror.NATIVE, UpdateMirror.fromStored("https://proxy.example.com/"))
        assertEquals(UpdateMirror.NATIVE, UpdateMirror.fromStored(""))
        assertEquals(UpdateMirror.NATIVE, UpdateMirror.fromStored(null))
    }

    @Test
    fun `原生是默认且排在第一项`() {
        assertEquals(UpdateMirror.NATIVE, UpdateMirror.entries.first())
        assertFalse(UpdateMirror.NATIVE.prefix.isNotEmpty())
    }
}
