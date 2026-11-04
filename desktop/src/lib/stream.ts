/**
 * 币安系（币安合约 / Aster / 币安现货）的实时行情通道。
 *
 * **为什么要有它**：实时价靠 REST 轮询既费权重（标签页一多就顶 IP 额度），也是 429/418
 * 的来源。WS 一条连接就能推全部标签页，权重归零。
 *
 * ⚠️ **WS 不是免封金牌**（实测 2026-10-30，本机代理出口 IP 被币安封 6 小时）：
 * REST 返回 418 的同时，`wss://fstream.binance.com` 会**接受握手（101）却一帧都不推** ——
 * 币安报错里那句 "use the websocket for live updates" 并不成立。同一时刻
 * `wss://fstream.asterdex.com`（Aster，独立后端）推送正常。
 * 所以 WS 用来省权重与常态实时更新；真被封时唯一出路是换独立盘口或等封禁过期。
 *
 * **为什么在 webview 里开而不是宿主侧**：WebSocket 不是 fetch，不受 CORS 约束；
 * WebView2 / WKWebView 自己跟随系统代理，省掉在 Rust 里手写 CONNECT 隧道与
 * 三平台代理探测。Rust 侧只在 REST 上把关（见 lib/http.ts）。
 *
 * 一条连接承载多个流（组合流 `/stream?streams=a/b`）：所有开着的 `@ticker` 合成一条，
 * 图表的 `@kline_<周期>` 另开一条。断线按指数退避重连；15 秒收不到任何帧就当作
 * 假死（币安每秒都有 ticker 推送），主动断开重连。
 *
 * ❗**假死必须由看门狗自己收尾，不能只依赖 onclose**：关掉 VPN 之后隧道里的连接既没有
 * 数据也没有 RST，`close()` 的挥手同样发不出去（浏览器要等自己的关闭握手超时才回调），
 * 这段时间里消费方还停在「WS 活着」的状态上、REST 兜底也被停着，界面就是冻住的。
 * 所以看门狗到点直接按断开处理（排重连 + 通知消费方），再尽力关掉那条 socket。
 *
 * **合约与现货是两套后端**（`fstream.*` / `data-stream.binance.vision`），所以各开各的
 * 连接；重连策略共用下面这一份实现，别让现货退回到「只靠 REST 轮询」——那样一断网，
 * 恢复后要等下一拍才回来，且没有假死重连。
 */

import type { Bar } from './api';
import type { TickerSnapshot } from './dialects';

/** 断线重连的退避上限。 */
const RECONNECT_CAP_MS = 30_000;
/** 多久收不到任何帧就判定连接假死（币安每秒都有推送）。 */
const SILENT_TIMEOUT_MS = 15_000;

/**
 * 币安现货的 WS 主机：与 REST 的 `data-api.binance.vision` 同一套「只读行情」入口。
 * 实测 `wss://stream.binance.com:9443` 在本机代理下连不上，这个镜像能正常推送。
 */
export const BINANCE_SPOT_STREAM_HOST = 'wss://data-stream.binance.vision';

/**
 * ❗**连上不等于有数据**：币安对受限 IP 会接受 WS 握手（101）却一帧都不推（实测
 * 2026-10-30）。所以这里刻意**不**提供 onOpen —— 消费方必须以「收到第一帧」作为
 * 切到 WS、停掉 REST 兜底的判据，否则实时价会冻在旧值上，状态却还是绿的。
 */
export interface StreamHandlers {
  /** 连接断开（含主动重连前的瞬间）。消费方据此回退 REST 轮询。 */
  onClose?: () => void;
  onTicker?: (symbol: string, snapshot: TickerSnapshot) => void;
  onKline?: (symbol: string, bar: Bar, closed: boolean) => void;
}

/**
 * REST 主机 → WS 主机（`fapi.binance.com` → `fstream.binance.com`）。
 * 不是币安同构的域名返回 null —— 调用方据此完全不碰 WS，继续走 REST 轮询。
 */
export function streamHostOf(restBase: string): string | null {
  try {
    const url = new URL(restBase);
    if (!url.hostname.startsWith('fapi.')) return null;
    return `wss://fstream.${url.hostname.slice('fapi.'.length)}`;
  } catch {
    return null;
  }
}

/** 桌面端规范符号（`BTC_USDT` / `BTC_USDC`）→ 币安流名里的原生符号（`btcusdt` / `btcusdc`）。 */
export function streamSymbolOf(symbol: string): string {
  const [base, quote = 'USDT'] = symbol.split('_');
  return `${base}${quote}`.toLowerCase();
}

/** 币安原生符号（`BTCUSDT` / `BTCUSDC`）→ 桌面端规范符号；其它本位返回 null。 */
const QUOTES = ['USDT', 'USDC'];

function canonicalSymbol(native: unknown): string | null {
  if (typeof native !== 'string') return null;
  const quote = QUOTES.find((q) => native.endsWith(q));
  return quote == null ? null : `${native.slice(0, -quote.length)}_${quote}`;
}

function number(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** `@ticker`（24h 滚动窗口）的字段与 REST `/ticker/24hr` 同名同义。 */
function snapshotOf(data: Record<string, unknown>): TickerSnapshot | null {
  const lastPrice = Number(data.c);
  const openPrice = Number(data.o);
  if (!Number.isFinite(lastPrice) || !Number.isFinite(openPrice)) return null;
  return {
    lastPrice,
    openPrice,
    highPrice: number(data.h),
    lowPrice: number(data.l),
    quoteVolume: number(data.q),
  };
}

/** `@kline_<周期>` 里的 `k` 对象 → Bar。 */
function barOf(k: Record<string, unknown>): Bar | null {
  const timestamp = Number(k.t);
  const close = Number(k.c);
  if (!Number.isFinite(timestamp) || !Number.isFinite(close)) return null;
  return {
    timestamp,
    open: number(k.o),
    high: number(k.h),
    low: number(k.l),
    close,
    volume: number(k.v),
  };
}

/**
 * 订阅币安**合约系**（合约 / Aster）的一组组合流。返回取消函数
 * （组件卸载、换源、换标的都调用它）。
 *
 * `streams` 用币安原生写法，如 `btcusdt@ticker`、`btcusdt@kline_1m`；
 * 传空数组或 REST 不是币安同构域名时直接返回空操作。
 */
export function openBinanceStream(
  restBase: string,
  streams: string[],
  handlers: StreamHandlers,
): () => void {
  const host = streamHostOf(restBase);
  if (host == null || streams.length === 0) return () => {};
  return openStream(host, streams, handlers);
}

/**
 * 订阅币安**现货**的一组组合流：重连、退避、假死看门狗与合约完全同一套，
 * 只是主机换成现货行情入口（`data-stream.binance.vision`）。
 */
export function openBinanceSpotStream(
  streams: string[],
  handlers: StreamHandlers,
): () => void {
  if (streams.length === 0) return () => {};
  return openStream(BINANCE_SPOT_STREAM_HOST, streams, handlers);
}

/** 合约与现货共用：按给定 WS 主机订阅组合流，断线指数退避重连、静默超时主动重连。 */
function openStream(host: string, streams: string[], handlers: StreamHandlers): () => void {
  const url = `${host}/stream?streams=${streams.join('/')}`;
  let socket: WebSocket | null = null;
  let stopped = false;
  let attempt = 0;
  let reconnectTimer = 0;
  let silentTimer = 0;

  const dispatch = (payload: unknown) => {
    if (payload == null || typeof payload !== 'object') return;
    // 组合流是 `{stream, data}`；单流是裸的消息体，这里两种都认
    const envelope = payload as Record<string, unknown>;
    const data = (envelope.data != null ? envelope.data : envelope) as Record<string, unknown>;
    const event = data.e;
    if (event === '24hrTicker') {
      const symbol = canonicalSymbol(data.s);
      const snapshot = symbol == null ? null : snapshotOf(data);
      if (symbol != null && snapshot != null) handlers.onTicker?.(symbol, snapshot);
      return;
    }
    if (event === 'kline') {
      const symbol = canonicalSymbol(data.s);
      const k = data.k;
      const bar = k != null && typeof k === 'object' ? barOf(k as Record<string, unknown>) : null;
      if (symbol != null && bar != null) {
        handlers.onKline?.(symbol, bar, (k as Record<string, unknown>).x === true);
      }
    }
  };

  const scheduleReconnect = () => {
    if (stopped) return;
    const base = Math.min(1_000 * 2 ** attempt, RECONNECT_CAP_MS);
    // ±20% 抖动：两个窗口同时断线时别卡在同一个节拍上一起重连
    const delay = Math.round(base * (0.8 + Math.random() * 0.4));
    attempt += 1;
    reconnectTimer = window.setTimeout(connect, delay);
  };

  function connect(): void {
    if (stopped) return;
    const ws = new WebSocket(url);
    socket = ws;
    /** 一条连接只许「结束」一次：看门狗与 onclose 都会走到这里。 */
    let closed = false;

    const settle = () => {
      if (closed) return;
      closed = true;
      window.clearTimeout(silentTimer);
      if (socket === ws) socket = null;
      handlers.onClose?.();
      scheduleReconnect();
    };

    const armSilentWatchdog = () => {
      window.clearTimeout(silentTimer);
      silentTimer = window.setTimeout(() => {
        // 假死连接上 close() 的挥手也发不出去，onclose 可能迟迟不来：
        // 先按断开处理（消费方切回 REST、排上重连），再尽力关掉它
        settle();
        ws.close();
      }, SILENT_TIMEOUT_MS);
    };

    ws.onopen = () => {
      attempt = 0;
      armSilentWatchdog();
    };
    ws.onmessage = (event) => {
      if (closed) return;
      armSilentWatchdog();
      try {
        dispatch(JSON.parse(String(event.data)));
      } catch {
        /* 非 JSON 帧（服务端公告等）直接丢，不该打断行情 */
      }
    };
    ws.onerror = () => {
      // 具体原因浏览器不给；统一按断开处理（close 事件随后会到）
    };
    ws.onclose = settle;
    // 握手阶段也上表：黑洞路由上 new WebSocket 可能既不报错也不超时，
    // 只靠 onopen 之后才计时会把「一直连不上」拖到系统 TCP 超时（分钟级）。
    armSilentWatchdog();
  }

  connect();

  return () => {
    stopped = true;
    window.clearTimeout(reconnectTimer);
    window.clearTimeout(silentTimer);
    socket?.close();
    socket = null;
  };
}
