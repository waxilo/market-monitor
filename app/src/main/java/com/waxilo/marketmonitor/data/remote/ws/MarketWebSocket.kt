package com.waxilo.marketmonitor.data.remote.ws

import android.util.Log
import com.waxilo.marketmonitor.data.remote.MarketJson
import com.waxilo.marketmonitor.data.remote.dto.TickerDto
import com.waxilo.marketmonitor.data.remote.dto.WsKlineEventDto
import com.waxilo.marketmonitor.data.remote.dto.toDomain
import com.waxilo.marketmonitor.domain.model.Kline
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.MarketType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.math.BigDecimal
import kotlin.random.Random

/** WS 地址来源，与 REST 一样支持用户镜像（PRD 3.2 多域名容灾）。 */
interface WsHosts {
    fun urlsFor(market: MarketType): List<String>
}

class DefaultWsHosts(
    private val spotMirror: () -> String = { "" },
    private val futuresMirror: () -> String = { "" },
) : WsHosts {
    override fun urlsFor(market: MarketType): List<String> {
        val mirror = when (market) {
            MarketType.SPOT -> spotMirror()
            MarketType.FUTURES -> futuresMirror()
        }
        return (mirror.split(',') + market.defaultWsHost)
            .map { it.trim().removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

/** 解析后的推送事件。市场归属由构造时的 [MarketType] 决定。 */
sealed class WsEvent {
    data class Ticker(val ticker: MarketTicker) : WsEvent()
    data class KlineUpdate(val symbol: String, val kline: Kline) : WsEvent()

    /** 连接断开、等待重连：仓库层借此标记「离线数据」并在恢复后回读 REST。 */
    data object Reconnecting : WsEvent()
}

/**
 * 币安行情 WebSocket（PRD 3.2：同一时刻只维持当前市场的一条连接，断线指数退避重连）。
 * 订阅集合由 [streams] 下发，内容变化时自动换流；解析失败的帧静默丢弃，
 * 因为单帧异常不应导致整条连接重建。
 */
class MarketWebSocket(
    private val client: OkHttpClient,
    private val hosts: WsHosts = DefaultWsHosts(),
    private val timeMs: () -> Long = System::currentTimeMillis,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(market: MarketType, streams: Flow<List<String>>): Flow<WsEvent> = streams
        .map { names -> names.filter { it.isNotBlank() }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { names -> if (names.isEmpty()) emptyFlow() else reconnectLoop(market, names) }

    private fun reconnectLoop(market: MarketType, streams: List<String>): Flow<WsEvent> = flow {
        val urls = hosts.urlsFor(market).ifEmpty { listOf(market.defaultWsHost) }
        var attempt = 0
        while (true) {
            val base = urls[attempt % urls.size].ifEmpty { market.defaultWsHost }
            val url = buildUrl(base, streams)
            if (url == null) {
                // 构造失败只跳过本轮重连：向上抛会经无兜底的协程作用域杀死整个进程
                Log.w(TAG, "无法构造 WS 地址（${market.label}），base=$base，退避后重试")
                emit(WsEvent.Reconnecting)
                attempt++
                delay(backoffMs(attempt))
                continue
            }
            try {
                connection(url, market).collect { emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 连接失败不向上冒泡：仓库层靠 Reconnecting 事件标记离线并在恢复后回读 REST
            }
            emit(WsEvent.Reconnecting)
            attempt++
            delay(backoffMs(attempt))
        }
    }

    private fun connection(url: String, market: MarketType): Flow<WsEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                WsParser.parse(text, market, timeMs()).forEach { channel.trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close(t)
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        awaitClose { socket.cancel() }
    }

    /** 指数退避 + ±20% 抖动，避免大量设备同时重连打满限频。 */
    private fun backoffMs(attempt: Int): Long {
        val base = (BASE_BACKOFF_MS shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        return (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
    }

    private fun buildUrl(base: String, streams: List<String>): String? {
        if (base.isBlank() || streams.isEmpty()) return null
        // 流名只允许官方字符集，含 `&`/`?` 的名字会改写查询串，直接整条拒绝
        if (streams.any { name -> name.any { it !in ALLOWED_STREAM_CHARS } }) return null
        return "$base/stream?streams=${streams.joinToString("/")}"
    }

    companion object {
        private const val TAG = "MarketWebSocket"
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 5
        // 必须用 listOf 而不是 charArrayOf：List<Char> + CharArray 会把数组整体当单个元素追加，
        // 特殊字符全部丢失，导致所有流名被判非法（0.2.0 启动即崩的根因）
        private val ALLOWED_STREAM_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '@', '!', '.', '-')
    }
}

/** 组合流流名（PRD 3.1）。列表页用 `!miniTicker@arr`，详情页用单 symbol 流。 */
object Streams {
    const val ALL_MINI_TICKER = "!miniTicker@arr"
    fun miniTicker(symbol: String): String = "${symbol.lowercase()}@miniTicker"
    fun kline(symbol: String, intervalApiCode: String): String =
        "${symbol.lowercase()}@kline_$intervalApiCode"
}

/** 把一帧 WS 文本解析为事件列表；`!miniTicker@arr` 一帧含全部交易对。 */
object WsParser {

    fun parse(text: String, market: MarketType, now: Long): List<WsEvent> {
        val root = runCatching { MarketJson.DEFAULT.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        // 组合流会包一层 {"stream":..., "data":...}；裸流直接就是事件体
        val payload = if (root is JsonObject && root.containsKey("data")) root.getValue("data") else root
        val frames = when (payload) {
            is JsonArray -> payload
            else -> buildJsonArray { add(payload) }
        }
        return frames.mapNotNull { decodeEvent(it, market, now) }.flatten()
    }

    private fun decodeEvent(data: JsonElement, market: MarketType, now: Long): List<WsEvent> {
        val obj = data as? JsonObject ?: return emptyList()
        return when (obj.string("e")) {
            "24hrMiniTicker" -> decodeTicker(obj, market, now)?.let { listOf(WsEvent.Ticker(it)) } ?: emptyList()
            "kline" -> decodeKline(obj)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun decodeTicker(element: JsonElement, market: MarketType, now: Long): MarketTicker? {
        val dto = runCatching { MarketJson.DEFAULT.decodeFromJsonElement(TickerDto.serializer(), element) }.getOrNull()
            ?: return null
        return dto.toDomain(market, now)
    }

    private fun decodeKline(obj: JsonObject): WsEvent.KlineUpdate? {
        val event = runCatching { MarketJson.DEFAULT.decodeFromJsonElement(WsKlineEventDto.serializer(), obj) }.getOrNull()
            ?: return null
        val k = event.k
        val open = k.o.toBigDecimalOrNull() ?: return null
        val high = k.h.toBigDecimalOrNull() ?: return null
        val low = k.l.toBigDecimalOrNull() ?: return null
        val close = k.c.toBigDecimalOrNull() ?: return null
        val kline = Kline(
            openTime = k.openTimeMs,
            closeTime = k.closeTimeMs,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = k.v.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            quoteVolume = k.q.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            trades = k.n,
            closed = k.x,
        )
        return WsEvent.KlineUpdate(event.s, kline)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
