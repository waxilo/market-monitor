package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.dto.ErrorResponseDto
import com.waxilo.marketmonitor.data.remote.dto.ExchangeInfoDto
import com.waxilo.marketmonitor.data.remote.dto.TickerDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.data.remote.dto.toKline
import com.waxilo.marketmonitor.domain.model.InstrumentMeta
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** REST 域名来源：设置里的镜像域优先，其后是官方域名（PRD 3.2 多域名容灾）。 */
interface RestHosts {
    fun hostsFor(market: MarketType): List<String>
}

class DefaultRestHosts(
    private val spotMirror: () -> String = { "" },
    private val futuresMirror: () -> String = { "" },
) : RestHosts {
    override fun hostsFor(market: MarketType): List<String> = buildList {
        val mirror = when (market) {
            MarketType.SPOT -> spotMirror()
            MarketType.FUTURES -> futuresMirror()
        }
        mirror.split(',').map { it.trim().removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .let { addAll(it) }
        when (market) {
            // 官方公布的主域 + api1~api3 备用 + 仅行情数据的 data-api 镜像
            MarketType.SPOT -> addAll(
                listOf(
                    "https://api.binance.com",
                    "https://api1.binance.com",
                    "https://api2.binance.com",
                    "https://api3.binance.com",
                    "https://data-api.binance.vision",
                ),
            )
            // 合约官方只公布 fapi.binance.com，备用域依赖用户在设置里填写
            MarketType.FUTURES -> add("https://fapi.binance.com")
        }
    }.distinct()
}

/** 接口错误。`binanceCode` 为币安业务错误码（-1121 等），`httpCode` 为传输层状态码。 */
class MarketApiException(
    val httpCode: Int,
    val binanceCode: Int = 0,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object MarketJson {
    val DEFAULT = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}

/**
 * 币安公开行情接口封装（PRD 3.1）。无需鉴权，只读。
 * 权重与域名细节都收在这里，仓库层看不到 URL。
 */
class BinanceMarketApi(
    private val client: OkHttpClient,
    private val hosts: RestHosts = DefaultRestHosts(),
    private val json: Json = MarketJson.DEFAULT,
) {

    private val budgets = mapOf(
        // 留出余量：官方上限 6000 / 2400，避免与同 IP 的其他客户端抢额度
        MarketType.SPOT to RateBudget(5_600),
        MarketType.FUTURES to RateBudget(2_200),
    )

    suspend fun ping(market: MarketType): Boolean = try {
        get(market, "/ping", weight = 1)
        true
    } catch (e: IOException) {
        false
    }

    suspend fun exchangeInfo(market: MarketType, symbol: String? = null): List<InstrumentMeta> {
        val query = symbol?.let { mapOf("symbol" to it) }.orEmpty()
        val element = get(market, "/exchangeInfo", query, weight = 20)
        val dto = json.decodeFromJsonElement(ExchangeInfoDto.serializer(), element)
        return dto.symbols.mapNotNull { it.toDomain(market) }
    }

    /** 全市场 24h 快照。现货/合约均为数组响应，weight 80（全量）。 */
    suspend fun tickers(market: MarketType): List<MarketTicker> {
        val element = get(market, "/ticker/24hr", weight = 80)
        val now = System.currentTimeMillis()
        return array(element).mapNotNull { item ->
            json.decodeFromJsonElement(TickerDto.serializer(), item).toDomain(market, now)
        }
    }

    /** 单个交易对的 24h 快照（详情页/预警轮询用，权重远低于全量）。 */
    suspend fun ticker(market: MarketType, symbol: String): MarketTicker? {
        val element = get(market, "/ticker/24hr", mapOf("symbol" to symbol), weight = 1)
        return json.decodeFromJsonElement(TickerDto.serializer(), element)
            .toDomain(market, System.currentTimeMillis())
    }

    /**
     * K 线。`intervalApiCode` 必须是官方周期码（自定义周期由仓库层聚合，见 FR-2.3）。
     * `startTime` 与 `endTime` 二选一使用：往早期翻页时传 endTime。
     */
    suspend fun klines(
        market: MarketType,
        symbol: String,
        intervalApiCode: String,
        limit: Int,
        startTime: Long? = null,
        endTime: Long? = null,
    ): List<Kline> {
        val query = buildMap {
            put("symbol", symbol)
            put("interval", intervalApiCode)
            put("limit", limit.coerceIn(1, MAX_KLINE_LIMIT).toString())
            startTime?.let { put("startTime", it.toString()) }
            endTime?.let { put("endTime", it.toString()) }
        }
        val element = get(market, "/klines", query, weight = klineWeight(limit))
        return array(element).mapNotNull { row ->
            (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toKline()
        }
    }

    private fun klineWeight(limit: Int): Int = when {
        limit <= 100 -> 1
        limit <= 500 -> 2
        limit <= 1000 -> 5
        else -> 10
    }

    private fun array(element: JsonElement): List<JsonElement> =
        (element as? JsonArray)?.toList() ?: emptyList()

    private suspend fun get(
        market: MarketType,
        path: String,
        query: Map<String, String> = emptyMap(),
        weight: Int,
    ): JsonElement {
        val candidates = hosts.hostsFor(market).ifEmpty {
            throw MarketApiException(0, 0, "未配置 ${market.label} 接口域名")
        }
        var lastError: IOException? = null
        for (host in candidates) {
            // 用户在设置里写错的镜像域不该拖垮整条链路，跳过试下一个
            val url = buildUrl(host, market, path, query) ?: continue
            budgets.getValue(market).await(weight)
            try {
                return execute(url)
            } catch (e: MarketApiException) {
                // 4xx 换域名也没用（参数或标的错误），直接上抛
                if (e.httpCode in 400..499 && e.httpCode != 418 && e.httpCode != 429) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: MarketApiException(0, 0, "${market.label}请求失败")
    }

    private fun buildUrl(host: String, market: MarketType, path: String, query: Map<String, String>): HttpUrl? {
        val base = (host.trimEnd('/') + market.apiPrefix + path).toHttpUrlOrNull() ?: return null
        return base.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    private suspend fun execute(url: HttpUrl): JsonElement {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val text = client.newCall(request).awaitText()
        return try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw MarketApiException(200, 0, "响应解析失败：${e.message}", e)
        }
    }

    companion object {
        /** 币安单次请求上限。 */
        const val MAX_KLINE_LIMIT = 1000
    }
}

/** OkHttp 4 没有内置协程支持，这里做一次取消感知的桥接。 */
private suspend fun Call.awaitText(): String = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull()
                when {
                    resp.isSuccessful -> continuation.resume(body ?: "")
                    else -> continuation.resumeWithException(resp.toApiError(body))
                }
            }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

private fun Response.toApiError(body: String?): MarketApiException {
    val parsed = runCatching {
        MarketJson.DEFAULT.decodeFromString(ErrorResponseDto.serializer(), body.orEmpty())
    }.getOrNull()
    val reason = if (parsed != null) "${parsed.code}: ${parsed.msg}" else "HTTP $code $message"
    return MarketApiException(code, parsed?.code ?: 0, reason)
}
