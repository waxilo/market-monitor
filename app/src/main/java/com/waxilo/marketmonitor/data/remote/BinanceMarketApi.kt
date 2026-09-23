package com.waxilo.marketmonitor.data.remote

import com.waxilo.marketmonitor.data.remote.dialect.FuturesDialectAdapter
import com.waxilo.marketmonitor.data.remote.dialect.FuturesDialects
import com.waxilo.marketmonitor.data.remote.dto.ErrorResponseDto
import com.waxilo.marketmonitor.data.remote.dto.ExchangeInfoDto
import com.waxilo.marketmonitor.data.remote.dto.TickerDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.data.remote.dto.toKline
import com.waxilo.marketmonitor.domain.kline.OfficialInterval
import com.waxilo.marketmonitor.domain.model.FuturesEndpoint
import com.waxilo.marketmonitor.domain.model.FuturesEndpoints
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** REST 域名来源：现货走镜像+官方回退链；合约只用用户选定的单一接口（PRD 3.2）。 */
interface RestHosts {
    fun hostsFor(market: MarketType): List<String>
}

class DefaultRestHosts(
    private val spotMirror: () -> String = { "" },
    private val futuresHost: () -> String = { FuturesEndpoints.DEFAULT_URL },
) : RestHosts {
    override fun hostsFor(market: MarketType): List<String> = buildList {
        when (market) {
            MarketType.SPOT -> {
                spotMirror().split(',').map { it.trim().removeSuffix("/") }
                    .filter { it.isNotEmpty() }
                    .let { addAll(it) }
                // data-api 是官方行情镜像，大陆网络可直连；放首位避免前面被墙的官方主域
                // 逐个走完 connect/read 超时再兜底（那样会让每次加载都慢到看似“请求不到数据”）。
                addAll(
                    listOf(
                        "https://data-api.binance.vision",
                        "https://api.binance.com",
                        "https://api1.binance.com",
                        "https://api2.binance.com",
                        "https://api3.binance.com",
                    ),
                )
            }
            // 合约行情不再逐域回退试探：候选清单内置在设置页（FuturesEndpoints），
            // 用户在一键检测后点选一个，这里就只发往那一个。
            // 币安合约与 Aster 是独立盘口，混用同一市场会污染 (FUTURES, symbol) 缓存，
            // 切换接口时由设置页负责清合约缓存并重同步。
            MarketType.FUTURES -> add(FuturesEndpoints.normalize(futuresHost()))
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
        if (market.isFutures) {
            probeFutures(hosts.hostsFor(market).first())
        } else {
            get(market, "/ping", weight = 1)
        }
        true
    } catch (e: IOException) {
        false
    }

    /** 当前选定的合约行情接口（含方言）。 */
    private fun futuresEndpoint(): FuturesEndpoint = FuturesEndpoints.of(hosts.hostsFor(MarketType.FUTURES).first())

    /**
     * 当前合约接口支持的 K 线周期分钟数。仓库层聚合时只从这张表里挑基础周期——
     * 各家原生周期不一（Gate 没有 3m，Bitget 没有 8h），缺的档位由上层用更小周期聚合补齐。
     */
    fun supportedIntervalMinutes(market: MarketType): List<Long> =
        if (market.isFutures) {
            FuturesDialects.of(futuresEndpoint().dialect).intervalLadder.keys.sorted()
        } else {
            OfficialInterval.entries.map { it.minutes }
        }

    /** 单次 K 线请求根数上限（各家不同：OKX 300、Hyperliquid 500、币安系 1000）。 */
    fun maxKlineLimit(market: MarketType): Int =
        if (market.isFutures) {
            FuturesDialects.of(futuresEndpoint().dialect).maxKlineLimit
        } else {
            MAX_KLINE_LIMIT
        }

    /**
     * 对**指定候选接口**做一次性连通探测（弹窗「一键检测」并行调用），返回往返毫秒数。
     * 按该候选的方言构造其原生探测请求（GET 或 Hyperliquid 的 POST）。
     * 不经回退链、不计权重：探测的是「这个接口在这台设备上通不通」，
     * 失败时抛 IOException（超时/DNS/HTTP 错误），由调用方转成每行的失败文案。
     */
    suspend fun probeFutures(baseUrl: String): Long {
        val endpoint = FuturesEndpoints.of(baseUrl)
        val request = FuturesDialects.of(endpoint.dialect).probe(endpoint.baseUrl)
        val started = System.currentTimeMillis()
        executeText(request)
        return System.currentTimeMillis() - started
    }

    suspend fun exchangeInfo(market: MarketType, symbol: String? = null): List<InstrumentMeta> {
        if (market.isFutures) {
            val endpoint = futuresEndpoint()
            val dialect = FuturesDialects.of(endpoint.dialect)
            budgets.getValue(market).await(20)
            val text = executeText(dialect.exchangeInfo(endpoint.baseUrl))
            val all = dialect.parseExchangeInfo(text)
            return symbol?.let { s -> all.filter { it.id.symbol == s } } ?: all
        }
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
        if (market.isFutures) {
            val endpoint = futuresEndpoint()
            val dialect = FuturesDialects.of(endpoint.dialect)
            budgets.getValue(market).await(1)
            val text = executeText(dialect.ticker(endpoint.baseUrl, symbol))
            return dialect.parseTicker(text, symbol, System.currentTimeMillis())
        }
        val element = get(market, "/ticker/24hr", mapOf("symbol" to symbol), weight = 1)
        return json.decodeFromJsonElement(TickerDto.serializer(), element)
            .toDomain(market, System.currentTimeMillis())
    }

    /**
     * K 线。`minutes` 是周期分钟数，必须落在 [supportedIntervalMinutes] 里
     * （缺档位的聚合由仓库层负责，见 FR-2.3）。
     * `startTime` 与 `endTime` 二选一使用：往早期翻页时传 endTime。
     */
    suspend fun klines(
        market: MarketType,
        symbol: String,
        minutes: Long,
        limit: Int,
        startTime: Long? = null,
        endTime: Long? = null,
    ): List<Kline> {
        val capped = limit.coerceIn(1, maxKlineLimit(market))
        if (market.isFutures) {
            val endpoint = futuresEndpoint()
            val dialect = FuturesDialects.of(endpoint.dialect)
            budgets.getValue(market).await(klineWeight(capped))
            val text = executeText(dialect.klines(endpoint.baseUrl, symbol, minutes, capped, startTime, endTime))
            return dialect.parseKlines(text, minutes)
        }
        val code = OfficialInterval.entries.firstOrNull { it.minutes == minutes }?.apiCode
            ?: throw MarketApiException(0, 0, "现货不支持 ${minutes} 分钟周期")
        val query = buildMap {
            put("symbol", symbol)
            put("interval", code)
            put("limit", capped.toString())
            startTime?.let { put("startTime", it.toString()) }
            endTime?.let { put("endTime", it.toString()) }
        }
        val element = get(market, "/klines", query, weight = klineWeight(capped))
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
                if (!shouldTryNextHost(e)) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        val tried = candidates.joinToString("、") { host -> host.substringAfter("//").substringBefore("/") }
        throw lastError?.let { IOException("${market.label}行情请求失败：$tried 都不可用（${it.message}）", it) }
            ?: MarketApiException(0, 0, "未配置 ${market.label} 接口域名")
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

    /** 方言请求（GET 或 POST）原文发出、原文收回；解析交给各家适配器。 */
    private suspend fun executeText(request: FuturesDialectAdapter.Request): String {
        val builder = Request.Builder().url(request.url).header("Accept", "application/json")
        request.postBody?.let { builder.post(it.toRequestBody(JSON_MEDIA_TYPE)) }
        return client.newCall(builder.build()).awaitText()
    }

    companion object {
        /** 币安单次请求上限。 */
        const val MAX_KLINE_LIMIT = 1000

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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

/**
 * 这个错误该换下一个域名再试，还是所有域名都会给同样的答案。
 *
 * 只有**币安亲口**拒了这个请求（响应体带业务码，如 -1121 标的不存在）才不必再试。
 * 裸 404/403 多半是「这家不服务这个端点」或镜像侧的 CDN/地域拦截，换下一个域名才是正解。
 * 早先按 HTTP 状态码段判断，于是用户在设置里填一个看着没错的镜像（比如把现货能直连的
 * `data-api.binance.vision` 填进合约镜像 —— 合约端点在那儿就是 404），整条链在第一个域名
 * 就断了，内置可直连域名根本没机会被试，用户看到的就是「不连 VPN 请求不到数据」。
 * 418/429 是限频，换别的域名有机会，继续试。
 */
internal fun shouldTryNextHost(error: MarketApiException): Boolean =
    error.binanceCode == 0 || error.httpCode == 418 || error.httpCode == 429
