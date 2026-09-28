/**
 * 行情接口：Gate 现货 + 永续。
 *
 * App 端永续走 Aster、现货走币安；桌面端统一走 Gate——同一域名覆盖两个市场，
 * 且实测可直连、响应带 Access-Control-Allow-Origin: *，webview 里直接 fetch 即可。
 */

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

/** 两个市场都在同一域名下，仅路径前缀不同。 */
const GATE_ORIGIN = 'https://api.gateio.ws';
const FUTURES = `${GATE_ORIGIN}/api/v4/futures/usdt`;
const SPOT = `${GATE_ORIGIN}/api/v4/spot`;

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} @ ${path}`);
  return (await res.json()) as T;
}

/** Gate 的数字字段都是字符串，空串/null 按 0 兜（与 App 的容错一致）。 */
function num(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** Gate 周期名与币安不同：周线是 7d（界面 chip 仍显示 1w）。 */
function gateInterval(interval: string): string {
  return interval === '1w' ? '7d' : interval;
}

interface GateFuturesTicker {
  contract: string;
  last: string;
  change_percentage: string;
  high_24h: string;
  low_24h: string;
  volume_24h_quote: string;
}

interface GateSpotTicker {
  currency_pair: string;
  last: string;
  change_percentage: string;
  high_24h: string;
  low_24h: string;
  quote_volume: string;
}

function futuresTicker(d: GateFuturesTicker): Ticker24h {
  return {
    symbol: d.contract,
    lastPrice: num(d.last),
    priceChangePercent: num(d.change_percentage),
    highPrice: num(d.high_24h),
    lowPrice: num(d.low_24h),
    quoteVolume: num(d.volume_24h_quote),
  };
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

/** 单个标的的 24h 行情；接口都支持按标的过滤，轮询自选只发这几条。 */
export async function fetchTicker(market: MarketType, symbol: string): Promise<Ticker24h> {
  const q = encodeURIComponent(symbol);
  if (market === 'FUTURES') {
    const rows = await getJson<GateFuturesTicker[]>(`${FUTURES}/tickers?contract=${q}`);
    const d = rows[0];
    if (!d) throw new Error(`无此合约：${symbol}`);
    return futuresTicker(d);
  }
  const rows = await getJson<GateSpotTicker[]>(`${SPOT}/tickers?currency_pair=${q}`);
  const d = rows[0];
  if (!d) throw new Error(`无此交易对：${symbol}`);
  return spotTicker(d);
}

/** 该市场全量行情一次拉齐（搜索结果需要给未自选的标的也显示价格与成交额）。 */
export async function fetchAllTickers(market: MarketType): Promise<Ticker24h[]> {
  if (market === 'FUTURES') {
    const rows = await getJson<GateFuturesTicker[]>(`${FUTURES}/tickers`);
    return rows.map(futuresTicker);
  }
  const rows = await getJson<GateSpotTicker[]>(`${SPOT}/tickers`);
  return rows.map(spotTicker);
}

interface GateFuturesCandle {
  t: number;
  o: string;
  h: string;
  l: string;
  c: string;
  v: number;
}

/**
 * K 线，旧→新。limit 与 App 仓库层一致默认 300。
 *
 * 现货蜡烛是**数组**且字段顺序与币安不同：
 * `[秒级时间, 成交额, 收, 高, 低, 开, 成交量, 是否收线]`。
 */
export async function fetchKlines(
  market: MarketType,
  symbol: string,
  interval: string,
  limit = 300,
): Promise<Bar[]> {
  const q = encodeURIComponent(symbol);
  const iv = gateInterval(interval);
  if (market === 'FUTURES') {
    const rows = await getJson<GateFuturesCandle[]>(
      `${FUTURES}/candlesticks?contract=${q}&interval=${iv}&limit=${limit}`,
    );
    return rows.map((r) => ({
      timestamp: r.t * 1000,
      open: num(r.o),
      high: num(r.h),
      low: num(r.l),
      close: num(r.c),
      volume: num(r.v),
    }));
  }
  const rows = await getJson<string[][]>(
    `${SPOT}/candlesticks?currency_pair=${q}&interval=${iv}&limit=${limit}`,
  );
  return rows.map((r) => ({
    timestamp: Number(r[0]) * 1000,
    open: num(r[5]),
    high: num(r[3]),
    low: num(r[4]),
    close: num(r[2]),
    volume: num(r[6]),
  }));
}

interface GateFuturesContract {
  name: string;
  status: string;
  order_price_round: string;
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

const instrumentCache: Partial<Record<MarketType, Promise<Instrument[]>>> = {};

/**
 * 该市场全部可交易标的（搜索的数据源），只取一次并缓存。
 * 未收线的、下架的、非 tradable 的都滤掉，免得搜出一堆点进去没行情的名字。
 */
export function fetchInstruments(market: MarketType): Promise<Instrument[]> {
  let pending = instrumentCache[market];
  if (!pending) {
    pending =
      market === 'FUTURES'
        ? getJson<GateFuturesContract[]>(`${FUTURES}/contracts`).then((rows) =>
            rows
              .filter((c) => c.status === 'trading')
              .map((c) => {
                const [base, quote] = c.name.split('_');
                return {
                  symbol: c.name,
                  baseAsset: base,
                  quoteAsset: quote ?? 'USDT',
                  tickSize: c.order_price_round,
                };
              }),
          )
        : getJson<GateSpotPair[]>(`${SPOT}/currency_pairs`).then((rows) =>
            rows
              .filter((p) => p.trade_status === 'tradable')
              .map((p) => ({
                symbol: p.id,
                baseAsset: p.base,
                quoteAsset: p.quote,
                tickSize: tickFromPrecision(p.precision),
              })),
          );
    pending = pending.catch((e) => {
      delete instrumentCache[market];
      throw e;
    });
    instrumentCache[market] = pending;
  }
  return pending;
}
