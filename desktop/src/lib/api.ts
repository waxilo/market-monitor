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

import { DIALECTS, INTERVAL_MINUTES, type FuturesDialect, type HttpCall, type TickerSnapshot } from './dialects';
import { httpRequest } from './http';
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
 * K 线，旧→新。limit 与 App 仓库层一致默认 300；
 * MEXC / Hyperliquid 没有 limit 参数，按根数与周期倒推时间窗（见 dialects.ts）。
 */
export async function fetchKlines(
  market: MarketType,
  symbol: string,
  interval: string,
  limit = 300,
): Promise<Bar[]> {
  if (market === 'FUTURES') {
    const { baseUrl, dialect, label } = currentFutures();
    const minutes = INTERVAL_MINUTES[interval];
    if (minutes == null || dialect.intervalLadder[minutes] == null) {
      throw new Error(`${label} 不支持 ${interval} 周期`);
    }
    return dialect.parseKlines(await fetchText(dialect.klines(baseUrl, symbol, minutes, limit)), minutes);
  }
  const limitClamped = Math.max(1, Math.min(limit, 1000));
  const rows = await spotJson<string[][]>(
    `/candlesticks?currency_pair=${encodeURIComponent(symbol)}&interval=${gateInterval(interval)}&limit=${limitClamped}`,
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

// —————————————————————————— 标的清单（搜索的数据源） ——————————————————————————

const instrumentCache = new Map<MarketType, Promise<Instrument[]>>();

/**
 * 该市场全部可交易标的，只取一次并缓存；失败作废缓存（下次重试）。
 * 未收线的、下架的、非 tradable 的都滤掉，免得搜出一堆点进去没行情的名字。
 */
export function fetchInstruments(market: MarketType): Promise<Instrument[]> {
  let pending = instrumentCache.get(market);
  if (!pending) {
    pending = (market === 'FUTURES' ? futuresInstruments() : spotInstruments()).catch((e) => {
      instrumentCache.delete(market);
      throw e;
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
onFuturesSourceChange(() => instrumentCache.clear());

// —————————————————————————— Gate 现货（唯一固定盘口） ——————————————————————————

const GATE_ORIGIN = 'https://api.gateio.ws';
const SPOT = `${GATE_ORIGIN}/api/v4/spot`;

async function spotJson<T>(path: string): Promise<T> {
  return JSON.parse(await fetchText({ url: `${SPOT}${path}`, method: 'GET' })) as T;
}

/** Gate 周期名与币安不同：周线是 7d（界面 chip 仍显示 1w）。 */
function gateInterval(interval: string): string {
  return interval === '1w' ? '7d' : interval;
}

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
