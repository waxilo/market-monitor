package com.waxilo.marketmonitor.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceSignerTest {

    // 币安官方文档的签名示例（secret + query 原文），Aster 同构可直接复用。
    // 期望值经 openssl dgst -sha256 -hmac 独立验算。
    @Test
    fun `官方示例向量签名一致`() {
        val secret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0n"
        val query = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC" +
            "&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559"
        assertEquals(
            "5705b53ae3caa75b9c87ef0c4c7a1037d3ca7c23905bfe71475a52cbac7ee9d5",
            BinanceSigner.hmacSha256Hex(secret, query),
        )
    }

    @Test
    fun `参数拼接顺序即签名顺序，空格编成 %20`() {
        val qs = BinanceSigner.queryString(
            listOf("symbol" to "BTCUSDT", "side" to "BUY", "remark" to "a b"),
        )
        assertEquals("symbol=BTCUSDT&side=BUY&remark=a%20b", qs)
    }
}
