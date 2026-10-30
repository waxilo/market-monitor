/**
 * 行情入口：现货与永续都按用户在「数据源」弹窗里选定的候选分发（见 lib/sources.ts）——
 * 现货出厂币安（可切 Gate），永续在 9 个候选里点选。
 *
 * 传输统一走宿主侧（lib/http.ts）：webview 的 fetch 只有币安现货镜像、Gate 等少数盘口
 * 能读响应体（CORS），而各家的 URL 构造与响应解析在 lib/dialects.ts / spotDialects.ts
 * （对应 App 的 dialect 层）。
 *
 * 符号一律用 Gate 形（`BTC_USDT`）—— 方言层在边界换算成各家原生写法，所以标签页、
 * 搜索、图表这些下游不感知数据源。
 */

import { aggregateCandles } from './aggregate';
import { isSourceFailureStatus, noteFuturesRequest } from './autoSource';
import {
  DIALECTS,
  type DialectId,
  type FuturesDialect,
  type HttpCall,
  type OpenInterestPoint,
  type TickerSnapshot,
} from './dialects';
import { displaySymbol } from './format';
import { httpRequest } from './http';
import { pageBackwards } from './klinePaging';
import { assertFuturesAllowed, noteRateLimited, noteRateOk, noteUsedWeight } from './rateGate';
import { coarsestBaseFor, minutesOf } from './intervals';
import {
  currentFuturesUrl,
  currentSpotDialect,
  currentSpotSource,
  endpointOf,
  onFuturesSourceChange,
  onSpotSourceChange,
  spotEndpointOf,
} from './sources';
import type { SpotCall } from './spotDialects';

export type MarketType = 'SPOT' | 'FUTURES';

/** 展示名与顺序对齐 App 的 MarketType（现货在前）。 */
export const MARKET_LABEL: Record<MarketType, string> = {
  SPOT: '现货',
  FUTURES: '永续合约',
};
export const MARKETS: MarketType[] = ['SPOT', 'FUTURES'];

export interface Ticker24h {
  symbol: string;
  lastPrice: number;
  priceChangePercent: number;
  highPrice: number;
  lowPrice: number;
  quoteVolume: number;
}

export interface Bar {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface Instrument {
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
  tickSize?: string;
}

/**
 * 发一次盘口请求并取回响应体；非 2xx 一律抛（探测行不走这里，它要按状态码给结论）。
 *
 * 合约请求额外上报耗时/成败，供 `autoSource` 在超时或失败时重新选源。只报「源的锅」：
 * 业务错误（参数不对、币种不存在）不触发换源，否则一个填错的代码就能把源换掉。
 */
async function fetchText(call: HttpCall, market?: MarketType): Promise<string> {
  if (market !== 'FUTURES') {
    const res = await httpRequest(call.method, call.url, call.body);
    if (res.status < 200 || res.status >= 300) throw new Error(`HTTP ${res.status}`);
    return res.body;
  }
  const origin = currentFutures().baseUrl;
  const started = performance.now();
  let res;
  try {
    // 冷却期不再碰网络：被封着还继续发，币安会把封禁一路往后推（见 rateGate）
    assertFuturesAllowed(origin);
    res = await httpRequest(call.method, call.url, call.body);
  } catch (error) {
    // 冷却拦截也算「这个源现在用不了」：自动选源开着时才可能因此换到独立盘口
    noteFuturesRequest(performance.now() - started, true);
    throw error;
  }
  noteUsedWeight(origin, res.used_weight_1m);
  if (res.status < 200 || res.status >= 300) {
    if (res.status === 418 || res.status === 429) {
      noteRateLimited(origin, res.status, res.retry_after_secs);
    }
    if (isSourceFailureStatus(res.status)) {
      noteFuturesRequest(performance.now() - started, true);
    }
    throw new Error(`HTTP ${res.status}`);
  }
  noteRateOk(origin);
  noteFuturesRequest(performance.now() - started, false);
  return res.body;
}

/** 快照 → 展示形：涨跌幅按 (现价 − 24h 开盘) / 开盘 算，各路盘口统一在这个出口换算。 */
function toTicker(symbol: string, snapshot: TickerSnapshot): Ticker24h {
  const { lastPrice, openPrice } = snapshot;
  return {
    symbol,
    lastPrice,
    priceChangePercent: openPrice > 0 ? ((lastPrice - openPrice) / openPrice) * 100 : 0,
    highPrice: snapshot.highPrice,
    lowPrice: snapshot.lowPrice,
    quoteVolume: snapshot.quoteVolume,
  };
}

function currentFutures(): {
  baseUrl: string;
  dialectId: DialectId;
  dialect: FuturesDialect;
  label: string;
} {
  const endpoint = endpointOf(currentFuturesUrl());
  return {
    baseUrl: endpoint.baseUrl,
    dialectId: endpoint.dialect,
    dialect: DIALECTS[endpoint.dialect],
    label: endpoint.label,
  };
}

/**
 * 只有币安系（含 Aster）的方言按符号自带计价币拼请求；其余几家是「币名 + 固定 USDT」
 * （`BTC-USDT-SWAP` / `BTCUSDT` / `BTC_USDT`）。拿 USDC 标的一问，它们会把 `BTC_USDC`
 * 悄悄读成 BTC 的 USDT 合约 —— **价格看起来正常，盘口却是错的**。宁可明确报错。
 */
function assertQuoteSupported(market: MarketType, symbol: string): void {
  if (market !== 'FUTURES') return;
  const { dialectId, label } = currentFutures();
  if (dialectId !== 'BINANCE' && !symbol.endsWith('_USDT')) {
    throw new Error(`${label} 只提供 USDT 交易对；${displaySymbol(symbol)} 请切到币安系接口`);
  }
}

// —————————————————————————— 单个标的的 24h 行情 ——————————————————————————

export async function fetchTicker(market: MarketType, symbol: string): Promise<Ticker24h> {
  if (market === 'FUTURES') {
    assertQuoteSupported(market, symbol);
    const { baseUrl, dialect } = currentFutures();
    const snapshot = dialect.parseTicker(await fetchText(dialect.ticker(baseUrl, symbol), 'FUTURES'));
    if (!snapshot) throw new Error(`无此合约：${symbol}`);
    return toTicker(symbol, snapshot);
  }
  const dialect = currentSpotDialect();
  const ticker = dialect.parseTicker(await spotFetch(dialect.ticker(symbol)), symbol);
  if (!ticker) throw new Error(`无此交易对：${symbol}`);
  return ticker;
}

// —————————————————————————— K 线 ——————————————————————————

/**
 * 当前数据源的**原生周期表**（分钟 → 原生码）。
 *
 * 两个市场都看**当前源的方言**：`intervalLadder` 本来就是各家的原生码表，
 * 所以不另立一份支持清单（两处清单必然会分叉）。**这是唯一的「原生」真出处。**
 */
export function nativeLadder(market: MarketType): Record<number, string> {
  return market === 'FUTURES'
    ? currentFutures().dialect.intervalLadder
    : currentSpotDialect().intervalLadder;
}

/** 原生支持的周期分钟数（表外的周期不是不能用，而是要**聚合**出来 —— 见 `fetchKlines`）。 */
export function nativeMinutes(market: MarketType): Set<number> {
  return new Set(Object.keys(nativeLadder(market)).map(Number));
}

/**
 * K 线，旧→新。limit 与 App 仓库层一致默认 300；
 * MEXC / Hyperliquid 没有 limit 参数，按根数与周期倒推时间窗（见 dialects.ts）。
 *
 * **表外周期靠聚合**：各家的周期是一张固定白名单（实测：`45m`/`2m`/`7h`/`2M` 一律被拒，
 * 见 `.workbuddy/tmp/probe-intervals*.log`），所以「自定义周期」只能由能整除它的原生周期拼出来。
 * 基底挑**能整除的最粗**那个（因子最小 ⇒ 要请求的细粒度根数最少），
 * 证据与算法见 `lib/aggregate.ts`。
 */
export async function fetchKlines(
  market: MarketType,
  symbol: string,
  interval: string,
  limit = 300,
): Promise<Bar[]> {
  const minutes = minutesOf(interval);
  if (minutes == null) throw new Error(`未知周期 ${interval}`);
  const ladder = nativeLadder(market);
  const base = coarsestBaseFor(Object.keys(ladder).map(Number), minutes);
  if (base == null) throw new Error(`${sourceLabelOf(market)} 找不到能拼出 ${interval} 的原生周期`);
  if (base === minutes) return fetchNativeKlines(market, symbol, ladder, minutes, limit);

  const factor = minutes / base;
  // 多要一桶：请求窗口只保证「最新 N 根根」，起点多半不落在桶边界上 ⇒ 第一桶是残的会被丢掉
  const raw = await fetchNativeKlines(market, symbol, ladder, base, (limit + 1) * factor);
  const merged = aggregateCandles(raw, base, minutes);
  return merged.length > limit ? merged.slice(merged.length - limit) : merged;
}

/**
 * 「往更早翻一页」：只取 `beforeTs` 之前的 `limit` 根（`beforeTs` = 当前最早那根的时间）。
 *
 * 出口口径与 `fetchKlines` 完全一致：升序、至多 `limit` 根、最后一根紧挨 `beforeTs` 前一根。
 * 不支持时间窗的来源（HTX）返回空数组 —— 调用方据此把「还有更早」标记为耗尽、不再请求。
 *
 * 各家 `endMs` 的边界口径在方言层（见 dialects.ts）；这里出口再滤一道 `< beforeTs`：
 * 有的来源的 `end` 是**闭区间**（会把边界那根也带回来），松紧各家不统一，收口收在这里。
 */
export async function fetchKlinesBefore(
  market: MarketType,
  symbol: string,
  interval: string,
  beforeTs: number,
  limit = 300,
): Promise<Bar[]> {
  if (!supportsOlderKlines(market)) return [];
  const minutes = minutesOf(interval);
  if (minutes == null) throw new Error(`未知周期 ${interval}`);
  const ladder = nativeLadder(market);
  const base = coarsestBaseFor(Object.keys(ladder).map(Number), minutes);
  if (base == null) throw new Error(`${sourceLabelOf(market)} 找不到能拼出 ${interval} 的原生周期`);
  // 毫秒差一：`endMs` 是「取到这个时刻**之前**」，与各家的半开/闭区间口径在方言层对齐
  const endMs = beforeTs - 1;

  if (base === minutes) {
    const rows = await fetchNativeKlines(market, symbol, ladder, minutes, limit, endMs);
    return trimOlder(rows, beforeTs, limit);
  }

  const factor = minutes / base;
  // 与正向同款多要一桶：窗口起点不落在桶边界上 ⇒ 最老那桶残、被 aggregateCandles 丢掉
  const raw = await fetchNativeKlines(market, symbol, ladder, base, (limit + 1) * factor, endMs);
  return trimOlder(aggregateCandles(raw, base, minutes), beforeTs, limit);
}

/** 翻旧页的出口收口：只要 `< beforeTs` 的、至多 `limit` 根（最老的截掉是防上面的多要）。 */
function trimOlder(bars: Bar[], beforeTs: number, limit: number): Bar[] {
  const cut = bars.filter((b) => b.timestamp < beforeTs);
  return cut.length > limit ? cut.slice(cut.length - limit) : cut;
}

/** 当前来源能不能按时间窗翻旧页（HTX 服务端无视时间参数，见 dialects.ts 的 supportsTimeWindow）。 */
export function supportsOlderKlines(market: MarketType): boolean {
  return market !== 'FUTURES' || currentFutures().dialect.supportsTimeWindow !== false;
}

/**
 * 单一原生周期的取数（`minutes` 必须真的在该源的表里）；`endMs` = 只要此时刻之前的（翻旧页用）。
 *
 * `limit` 超过该源单次上限时**往前多翻几页**拼起来（见 `lib/klinePaging`）；
 * 不支持时间窗的来源（HTX）翻不了页，只取一页。
 */
async function fetchNativeKlines(
  market: MarketType,
  symbol: string,
  ladder: Record<number, string>,
  minutes: number,
  limit: number,
  endMs?: number,
): Promise<Bar[]> {
  if (ladder[minutes] == null) throw new Error(`${sourceLabelOf(market)} 不支持 ${minutes} 分钟周期`);
  const pageSize =
    market === 'FUTURES' ? currentFutures().dialect.maxKlineLimit : currentSpotDialect().maxKlineLimit;
  if (limit <= pageSize || !supportsOlderKlines(market)) {
    return fetchNativePage(market, symbol, ladder, minutes, limit, endMs);
  }
  return pageBackwards(limit, pageSize, endMs, (size, end) =>
    fetchNativePage(market, symbol, ladder, minutes, size, end),
  );
}

/** 一次 HTTP 请求取一页（根数由方言自己夹到单次上限）。 */
async function fetchNativePage(
  market: MarketType,
  symbol: string,
  ladder: Record<number, string>,
  minutes: number,
  limit: number,
  endMs?: number,
): Promise<Bar[]> {
  if (ladder[minutes] == null) throw new Error(`${sourceLabelOf(market)} 不支持 ${minutes} 分钟周期`);
  if (market === 'FUTURES') {
    assertQuoteSupported(market, symbol);
    const { baseUrl, dialect } = currentFutures();
    return dialect.parseKlines(
      await fetchText(dialect.klines(baseUrl, symbol, minutes, limit, null, endMs ?? null), 'FUTURES'),
      minutes,
    );
  }
  const dialect = currentSpotDialect();
  return dialect.parseKlines(await spotFetch(dialect.klines(symbol, minutes, limit, endMs ?? null)));
}

/** 报错文案里点名的数据源。 */
function sourceLabelOf(market: MarketType): string {
  return market === 'FUTURES'
    ? currentFutures().label
    : spotEndpointOf(currentSpotSource()).label;
}

// —————————————————————————— 持仓量（永续副图） ——————————————————————————

/** 历史持仓量 + 口径标记：`baseCoin` 为 true 时对齐层按 K 线收盘价折美元。 */
export interface OpenInterestSeries {
  points: OpenInterestPoint[];
  baseCoin: boolean;
}

/** 当前数据源支不支持历史持仓量（副图 chip 的可用性）。现货恒 false。 */
export function supportsOpenInterest(market: MarketType): boolean {
  return market === 'FUTURES' && currentFutures().dialect.openInterest != null;
}

/**
 * 持仓量取数周期：**≤ 图周期的最大原生周期** —— 比图粗会丢分辨率、比图细又要多请求；
 * 原生周期全都比图周期粗时（1m 图碰上 OKX 那张 5m 起的表）取最细的那个。
 */
export function oiBaseFor(periods: number[], minutes: number): number {
  const atMost = periods.filter((p) => p <= minutes);
  return atMost.length > 0 ? Math.max(...atMost) : Math.min(...periods);
}

/**
 * 历史持仓量，升序。点数按 `limit` 根 K 线倒推（周期选择见 `oiBaseFor`），
 * 各家上限由方言的 `call` 自己夹（币安 500 / OKX 固定窗口 / Bybit 200 / Gate 1000）
 * —— 所以持仓量线可能覆盖不满整屏，从有数据的 K 线才开始画。
 */
export async function fetchOpenInterest(
  market: MarketType,
  symbol: string,
  interval: string,
  limit = 300,
): Promise<OpenInterestSeries> {
  if (market !== 'FUTURES') throw new Error('现货没有持仓量');
  assertQuoteSupported(market, symbol);
  const { baseUrl, dialect, label } = currentFutures();
  const spec = dialect.openInterest;
  if (!spec) throw new Error(`${label} 没有持仓量历史`);
  const minutes = minutesOf(interval);
  if (minutes == null) throw new Error(`未知周期 ${interval}`);
  const base = oiBaseFor(Object.keys(spec.ladder).map(Number), minutes);
  const needed = Math.max(1, Math.ceil((limit * minutes) / base));
  const points = spec.parse(await fetchText(spec.call(baseUrl, symbol, base, needed), 'FUTURES'));
  points.sort((a, b) => a.time - b.time);
  return { points, baseCoin: spec.baseCoinValue === true };
}

// —————————————————————————— 标的清单（搜索的数据源） ——————————————————————————

const instrumentCache = new Map<MarketType, Promise<Instrument[]>>();
/**
 * 已经落到手里的清单（`instrumentCache` 存的是 Promise，量不出「已到」）。
 * 给 hook 当初始值用：换市场时先渲染这一份，而不是先渲染一帧空清单 ——
 * 空清单那几帧里币名只能走 symbol 回退，看起来就是「闪过一下 ETHUSDT」。
 */
const instrumentResolved = new Map<MarketType, Instrument[]>();

/** 清单落盘键（按市场）。启动时先拿它渲染，网络那份在后台刷新。 */
function instrumentsKey(market: MarketType): string {
  return `mm.instruments.${market}`;
}

/** 这份清单属于哪个源 —— 存下来的清单只对同一个源有效（两家的标的名完全不同）。 */
function instrumentsSource(market: MarketType): string {
  return market === 'FUTURES' ? currentFutures().baseUrl : currentSpotSource();
}

/**
 * 读上次成功落盘的那份。**只在这一份的源与当前源一致时才认**：换过源就该当没存过
 * （否则搜索会列出另一家的标的名）。坏数据 / 配额异常一律当没存过，不影响启动。
 */
function readStoredInstruments(market: MarketType): Instrument[] | null {
  let raw: string | null;
  try {
    raw = localStorage.getItem(instrumentsKey(market));
  } catch {
    return null;
  }
  if (raw == null) return null;
  try {
    const parsed = JSON.parse(raw) as { source?: unknown; rows?: unknown };
    if (parsed.source !== instrumentsSource(market) || !Array.isArray(parsed.rows)) return null;
    const rows = parsed.rows.filter(
      (r): r is Instrument =>
        r != null &&
        typeof r === 'object' &&
        typeof (r as Instrument).symbol === 'string' &&
        typeof (r as Instrument).baseAsset === 'string',
    );
    return rows.length > 0 ? rows : null;
  } catch {
    return null;
  }
}

function writeStoredInstruments(market: MarketType, rows: Instrument[]): void {
  if (rows.length === 0) return;
  try {
    localStorage.setItem(
      instrumentsKey(market),
      JSON.stringify({ source: instrumentsSource(market), rows }),
    );
  } catch {
    /* 配额满 / 隐私模式：只影响下次启动的首屏速度，不影响本次 */
  }
}

function clearStoredInstruments(market: MarketType): void {
  try {
    localStorage.removeItem(instrumentsKey(market));
  } catch {
    /* 拿不到 storage 就没什么可清的 */
  }
}

/**
 * 同步取已缓存的清单，没有就返回 null（调用方自己决定要不要拉）。
 * 内存里没有再读一次落盘的那份 —— 启动后的第一批渲染就能直接有清单，不用等网络。
 */
export function peekInstruments(market: MarketType): Instrument[] | null {
  const memo = instrumentResolved.get(market);
  if (memo != null) return memo;
  const stored = readStoredInstruments(market);
  if (stored != null) instrumentResolved.set(market, stored);
  return stored;
}

/**
 * 该市场全部可交易标的，只取一次并缓存；失败作废缓存（下次重试）。
 * 成功一次就落盘：下次启动先拿它渲染，网络那份只用来刷新。
 * 未收线的、下架的、非 tradable 的都滤掉，免得搜出一堆点进去没行情的名字。
 */
export function fetchInstruments(market: MarketType): Promise<Instrument[]> {
  let pending = instrumentCache.get(market);
  if (!pending) {
    pending = (market === 'FUTURES' ? futuresInstruments() : spotInstruments())
      .catch((e) => {
        instrumentCache.delete(market);
        throw e;
      })
      .then((rows) => {
        instrumentResolved.set(market, rows);
        writeStoredInstruments(market, rows);
        return rows;
      });
    instrumentCache.set(market, pending);
  }
  return pending;
}

async function futuresInstruments(): Promise<Instrument[]> {
  const { baseUrl, dialect } = currentFutures();
  return dialect.parseExchangeInfo(await fetchText(dialect.exchangeInfo(baseUrl), 'FUTURES'));
}

/**
 * 换源必须清掉：两个盘口的交易对清单混在一起，搜索会搜出「点进去没行情」的名字
 * （与 App 的 clearMarketCache 同）。改选择的入口只有 sources.ts，挂上去就覆盖全部路径。
 */
onFuturesSourceChange(() => {
  // 只动作合约那一份：现货清单属于另一个源，换合约源不该让它失效
  instrumentCache.delete('FUTURES');
  instrumentResolved.delete('FUTURES');
  clearStoredInstruments('FUTURES');
  if (currentFuturesUrl()) void fetchInstruments('FUTURES').catch(() => {});
});

// —————————————————————————— 现货（Gate / 币安，可选） ——————————————————————————

/**
 * 现货**单个域名**的时限。宿主默认 20s，而主机链是串行试的 —— 一个黑洞域名就能把
 * 「拉清单」拖成几十秒。8s 足够把 300KB 的清单或一页 K 线拿回来，超时直接切下一个。
 */
const SPOT_HOST_TIMEOUT_MS = 8_000;

/**
 * 现货请求：按当前源的**主机链**依次试，前一个失败（网络层 / 超时 / 5xx / 网关类状态）
 * 才试下一个。链见 `sources.ts` 的 `SPOT_ENDPOINTS` —— 币安那几条共享同一个 IP 限频池，
 * 这层只解决可达性（`data-api.binance.vision` 大陆通常可直连）。
 *
 * 业务性 4xx（参数/标的错误）换域也是同样的答案，直接上抛。
 */
async function spotFetch(call: SpotCall): Promise<string> {
  const endpoint = spotEndpointOf(currentSpotSource());
  let last: unknown = null;
  for (const host of endpoint.hosts) {
    try {
      const res = await httpRequest(call.method, `${host}${call.url}`, call.body, SPOT_HOST_TIMEOUT_MS);
      if (res.status >= 200 && res.status < 300) return res.body;
      last = new Error(`HTTP ${res.status}`);
      if (
        !(
          res.status >= 500 ||
          res.status === 403 ||
          res.status === 404 ||
          res.status === 418 ||
          res.status === 429
        )
      ) {
        throw last;
      }
    } catch (error) {
      last = error;
    }
  }
  throw last instanceof Error ? last : new Error('现货接口不可用');
}

/** 换现货源必须清掉清单缓存：两家可选标的完全不同，混在一起会搜出「点进去没行情」的名字。 */
onSpotSourceChange(() => {
  instrumentCache.delete('SPOT');
  instrumentResolved.delete('SPOT');
  clearStoredInstruments('SPOT');
  void fetchInstruments('SPOT').catch(() => {});
});

async function spotInstruments(): Promise<Instrument[]> {
  const dialect = currentSpotDialect();
  return dialect.parseExchangeInfo(await spotFetch(dialect.exchangeInfo()));
}
