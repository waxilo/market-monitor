package com.waxilo.marketmonitor.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateMirrorTest {

    private val githubUrl = "https://github.com/waxilo/market-monitor/releases/download/0.9.8/app.apk"

    @Test
    fun `前缀为空或空白时直连`() {
        assertEquals(githubUrl, UpdateMirror.apply(githubUrl, ""))
        assertEquals(githubUrl, UpdateMirror.apply(githubUrl, "   "))
    }

    @Test
    fun `前缀带结尾斜杠时不产生双斜杠`() {
        assertEquals(
            "https://gh-proxy.com/$githubUrl",
            UpdateMirror.apply(githubUrl, "https://gh-proxy.com/"),
        )
    }

    @Test
    fun `前缀缺结尾斜杠时自动补上`() {
        assertEquals(
            "https://gh-proxy.com/$githubUrl",
            UpdateMirror.apply(githubUrl, "https://gh-proxy.com"),
        )
    }

    @Test
    fun `前缀两侧空白被忽略`() {
        assertEquals(
            "https://gh-proxy.com/$githubUrl",
            UpdateMirror.apply(githubUrl, "  https://gh-proxy.com/  "),
        )
    }
}
