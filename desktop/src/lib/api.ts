/**
 * 行情入口：现货固定走 Gate（与 App 相同 —— 多盘口只做合约），永续按用户在「数据源」
 * 弹窗里选定的候选分发（见 lib/sources.ts）。
 *
 * 传输统一走宿主侧（lib/http.ts）：webview 的 fetch 只有 Gate 等少数盘口能读响应体
 * （CORS），而各家的 URL 构造与响应解析在 lib/dialects.ts（对应 App 的 dialect 层）。
 *
 * 符号一律用 Gate 形（`BTC_USDT`）—— 方言层在边界换算成各家原生写法，所以自选、
 * 搜索、图表这些下游不感知数据源。
 */

import { aggregateCandles } from './aggregate';
import {
  DIALECTS,
  type FuturesDialect,
  type HttpCall,
  type OpenInterestPoint,
  type TickerSnapshot,
} from './dialects';
import { httpRequest } from './http';
import { coarsestBaseFor, minutesOf } from './intervals';
import { currentFuturesUrl, endpointOf, onFuturesSourceChange } from './sources';

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

/** 发一次盘口请求并取回响应体；非 2xx 一律抛（探测行不走这里，它要按状态码给结论）。 */
async function fetchText(call: HttpCall): Promise<string> {
  const res = await httpRequest(call.method, call.url, call.body);
  if (res.status < 200 || res.status >= 300) throw new Error(`HTTP ${res.status}`);
  return res.body;
}

/** Gate 的数字字段都是字符串，空串/null 按 0 兜（与 App 的容错一致）。 */
function num(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
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

function currentFutures(): { baseUrl: string; dialect: FuturesDialect; label: string } {
  const endpoint = endpointOf(currentFuturesUrl());
  return { baseUrl: endpoint.baseUrl, dialect: DIALECTS[endpoint.dialect], label: endpoint.label };
}

// —————————————————————————— 单个标的的 24h 行情 ——————————————————————————

export async function fetchTicker(market: MarketType, symbol: string): Promise<Ticker24h> {
  if (market === 'FUTURES') {
    const { baseUrl, dialect } = currentFutures();
    const snapshot = dialect.parseTicker(await fetchText(dialect.ticker(baseUrl, symbol)));
    if (!snapshot) throw new Error(`无此合约：${symbol}`);
    return toTicker(symbol, snapshot);
  }
  const rows = await spotJson<GateSpotTicker[]>(`/tickers?currency_pair=${encodeURIComponent(symbol)}`);
  const d = rows[0];
  if (!d) throw new Error(`无此交易对：${symbol}`);
  return spotTicker(d);
}

/** 该市场全量行情一次拉齐（搜索结果需要给未自选的标的也显示价格与成交额）。 */
export async function fetchAllTickers(market: MarketType): Promise<Ticker24h[]> {
  if (market === 'FUTURES') {
    const { baseUrl, dialect } = currentFutures();
    const rows = dialect.parseAllTickers(await fetchText(dialect.allTickers(baseUrl)));
    return rows.map(([symbol, snapshot]) => toTicker(symbol, snapshot));
  }
  const rows = await spotJson<GateSpotTicker[]>('/tickers');
  return rows.map(spotTicker);
}

// —————————————————————————— K 线 ——————————————————————————

/**
 * 当前数据源的**原生周期表**（分钟 → 原生码）。
 *
 * 现货固定 Gate（唯一盘口）；永续看当前方言的 `intervalLadder` —— 它本来就是各家的原生码表，
 * 所以不另立一份支持清单（两处清单必然会分叉）。**这是唯一的「原生」真出处。**
 */
export function nativeLadder(market: MarketType): Record<number, string> {
  return market === 'FUTURES' ? currentFutures().dialect.intervalLadder : SPOT_LADDER;
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

/** 单一原生周期的取数（`minutes` 必须真的在该源的表里）；`endMs` = 只要此时刻之前的（翻旧页用）。 */
async function fetchNativeKlines(
  market: MarketType,
  symbol: string,
  ladder: Record<number, string>,
  minutes: number,
  limit: number,
  endMs?: number,
): Promise<Bar[]> {
  const code = ladder[minutes];
  if (code == null) throw new Error(`${sourceLabelOf(market)} 不支持 ${minutes} 分钟周期`);
  if (market === 'FUTURES') {
    const { baseUrl, dialect } = currentFutures();
    return dialect.parseKlines(
      await fetchText(dialect.klines(baseUrl, symbol, minutes, limit, null, endMs ?? null)),
      minutes,
    );
  }
  const limitClamped = Math.max(1, Math.min(limit, 1000));
  const toQuery = endMs == null ? '' : `&to=${Math.floor(endMs / 1000)}`;
  const rows = await spotJson<string[][]>(
    `/candlesticks?currency_pair=${encodeURIComponent(symbol)}&interval=${code}&limit=${limitClamped}${toQuery}`,
  );
  // 现货蜡烛是**数组**且字段顺序与币安不同：[秒级时间, 成交额, 收, 高, 低, 开, 成交量, 是否收线]
  return rows.map((r) => ({
    timestamp: Number(r[0]) * 1000,
    open: num(r[5]),
    high: num(r[3]),
    low: num(r[4]),
    close: num(r[2]),
    volume: num(r[6]),
  }));
}

/** 报错文案里点名的数据源。 */
function sourceLabelOf(market: MarketType): string {
  return market === 'FUTURES' ? currentFutures().label : 'Gate 现货';
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
  const { baseUrl, dialect, label } = currentFutures();
  const spec = dialect.openInterest;
  if (!spec) throw new Error(`${label} 没有持仓量历史`);
  const minutes = minutesOf(interval);
  if (minutes == null) throw new Error(`未知周期 ${interval}`);
  const base = oiBaseFor(Object.keys(spec.ladder).map(Number), minutes);
  const needed = Math.max(1, Math.ceil((limit * minutes) / base));
  const points = spec.parse(await fetchText(spec.call(baseUrl, symbol, base, needed)));
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

/** 同步取已缓存的清单，没有就返回 null（调用方自己决定要不要拉）。 */
export function peekInstruments(market: MarketType): Instrument[] | null {
  return instrumentResolved.get(market) ?? null;
}

/**
 * 该市场全部可交易标的，只取一次并缓存；失败作废缓存（下次重试）。
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
        return rows;
      });
    instrumentCache.set(market, pending);
  }
  return pending;
}

async function futuresInstruments(): Promise<Instrument[]> {
  const { baseUrl, dialect } = currentFutures();
  return dialect.parseExchangeInfo(await fetchText(dialect.exchangeInfo(baseUrl)));
}

/**
 * 换源必须清掉：两个盘口的交易对清单混在一起，搜索会搜出「点进去没行情」的名字
 * （与 App 的 clearMarketCache 同）。改选择的入口只有 sources.ts，挂上去就覆盖全部路径。
 */
onFuturesSourceChange(() => {
  instrumentCache.clear();
  instrumentResolved.clear();
  // 合约清单来自选定的那一家，换源后要重拉；现货恒走 Gate，跟选择无关，留着继续用
  if (currentFuturesUrl()) void fetchInstruments('FUTURES').catch(() => {});
});

// —————————————————————————— Gate 现货（唯一固定盘口） ——————————————————————————

const GATE_ORIGIN = 'https://api.gateio.ws';
const SPOT = `${GATE_ORIGIN}/api/v4/spot`;

async function spotJson<T>(path: string): Promise<T> {
  return JSON.parse(await fetchText({ url: `${SPOT}${path}`, method: 'GET' })) as T;
}

/**
 * Gate **现货**的原生周期表（分钟 → 原生码）。
 *
 * 现货与永续是两张不同的白名单，所以必须分开列：粗周期上现货只有 `7d`/`30d` 这两个名字，
 * 而**没有** `2d`/`5d`。逐周期实测（`.workbuddy/tmp/probe-intervals-2.log`）：
 *   ✓ `1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 7d 30d`（`1w` 也认，与 `7d` 同）
 *   ✗ `2m` `45m` `7h` `2d` `5d` `1M` `bogus` —— 一律报错
 * ⚠️ `30d` 是**按日历月**走的（相邻间隔实测 28/31/30 天），不是固定 30 天；
 * 界面 id `1M` 映射到它就是既有的近似，别拿它当聚合基底去拼更粗的周期。
 */
const SPOT_LADDER: Record<number, string> = {
  1: '1m',
  3: '3m',
  5: '5m',
  15: '15m',
  30: '30m',
  60: '1h',
  120: '2h',
  240: '4h',
  360: '6h',
  480: '8h',
  720: '12h',
  1_440: '1d',
  4_320: '3d',
  10_080: '7d',
  43_200: '30d',
};

interface GateSpotTicker {
  currency_pair: string;
  last: string;
  change_percentage: string;
  high_24h: string;
  low_24h: string;
  quote_volume: string;
}

function spotTicker(d: GateSpotTicker): Ticker24h {
  return {
    symbol: d.currency_pair,
    lastPrice: num(d.last),
    priceChangePercent: num(d.change_percentage),
    highPrice: num(d.high_24h),
    lowPrice: num(d.low_24h),
    quoteVolume: num(d.quote_volume),
  };
}

interface GateSpotPair {
  id: string;
  base: string;
  quote: string;
  precision: number;
  trade_status: string;
}

/** 现货的 precision 是小数位数；tickSize 换算成 App 那套「最小价位字符串」。 */
function tickFromPrecision(precision: number): string {
  const p = Number.isFinite(precision) ? Math.max(0, Math.min(12, precision)) : 2;
  return p === 0 ? '1' : (10 ** -p).toFixed(p);
}

async function spotInstruments(): Promise<Instrument[]> {
  const rows = await spotJson<GateSpotPair[]>('/currency_pairs');
  return rows
    .filter((p) => p.trade_status === 'tradable')
    .map((p) => ({
      symbol: p.id,
      baseAsset: p.base,
      quoteAsset: p.quote,
      tickSize: tickFromPrecision(p.precision),
    }));
}
