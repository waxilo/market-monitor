/**
 * 现货方言：桌面端现货可选的盘口（Gate / 币安），与合约方言分开一套。
 *
 * 为什么现货也要方言：Gate 与币安的路径、字段名、K 线列序、周期码全不一样
 * （`candlesticks` vs `klines`、`7d/30d` vs `1w/1M`、符号 `BTC_USDT` vs `BTCUSDT`）。
 * 这里的出口与合约层一致：**规范符号一律 Gate 形（`BTC_USDT`）**，币安写法只在本文件里换算，
 * 标签页、搜索、图表这些下游仍然不感知数据源。
 *
 * 现货与合约在币安是**两条独立的限频/封禁业务线**（`/api/v3` 6000 权重/分、`/fapi/v1`
 * 2400 权重/分）：合约 IP 被封时现货照常，反过来也一样。
 */

import type { Bar, Instrument, Ticker24h } from './api';

/** 现货请求：`url` 是**路径**，主机由调用方按域名链补上（见 lib/sources 的 SpotEndpoint）。 */
export interface SpotCall {
  url: string;
  method: 'GET' | 'POST';
  body?: string;
}

export interface SpotDialect {
  /** 分钟 → 该家原生周期码。 */
  intervalLadder: Record<number, string>;
  /** 单次 K 线请求根数上限。 */
  maxKlineLimit: number;
  ticker(symbol: string): SpotCall;
  klines(symbol: string, minutes: number, limit: number, endMs?: number | null): SpotCall;
  exchangeInfo(): SpotCall;
  parseTicker(text: string, symbol: string): Ticker24h | null;
  parseKlines(text: string): Bar[];
  parseExchangeInfo(text: string): Instrument[];
}

// —————————————————————————— 小工具（与 dialects.ts 同一套容错口径） ——————————————————————————

function parse(text: string): unknown {
  return JSON.parse(text) as unknown;
}

function obj(value: unknown): Record<string, unknown> {
  return value != null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function str(value: unknown): string | undefined {
  if (typeof value === 'string') return value;
  if (typeof value === 'number') return String(value);
  return undefined;
}

/** 解析不出来按 0 兜（两家都混着字符串与数字）。 */
function num(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** 最小价位 → 十进制字符串；`1e-8` 这种会被 `format.ts` 的位数推断读错，必须展开。 */
function tickText(value: unknown): string | undefined {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return undefined;
  const text = n.toFixed(12).replace(/0+$/, '').replace(/\.$/, '');
  return text === '' ? undefined : text;
}

// —————————————————————————— Gate 现货 ——————————————————————————

/**
 * Gate **现货**的原生周期表（分钟 → 原生码）。
 *
 * 现货与永续是两张不同的白名单：粗周期上现货只有 `7d`/`30d` 这两个名字，而**没有**
 * `2d`/`5d`。逐周期实测（`.workbuddy/tmp/probe-intervals-2.log`）：
 *   ✓ `1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 7d 30d`（`1w` 也认，与 `7d` 同）
 *   ✗ `2m` `45m` `7h` `2d` `5d` `1M` `bogus` —— 一律报错
 * ⚠️ `30d` 是**按日历月**走的（相邻间隔实测 28/31/30 天），不是固定 30 天；
 * 界面 id `1M` 映射到它就是既有的近似，别拿它当聚合基底去拼更粗的周期。
 */
const GATE_LADDER: Record<number, string> = {
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

const gate: SpotDialect = (() => {
  const tickerOf = (row: Record<string, unknown>, symbol: string): Ticker24h => ({
    symbol,
    lastPrice: num(row.last),
    priceChangePercent: num(row.change_percentage),
    highPrice: num(row.high_24h),
    lowPrice: num(row.low_24h),
    quoteVolume: num(row.quote_volume),
  });

  return {
    intervalLadder: GATE_LADDER,
    maxKlineLimit: 1000,
    ticker: (symbol) => ({
      url: `/api/v4/spot/tickers?currency_pair=${encodeURIComponent(symbol)}`,
      method: 'GET',
    }),
    klines: (symbol, minutes, limit, endMs) => {
      const code = GATE_LADDER[minutes];
      if (code == null) throw new Error(`Gate 现货不支持该周期`);
      const to = endMs == null ? '' : `&to=${Math.floor(endMs / 1000)}`;
      return {
        url: `/api/v4/spot/candlesticks?currency_pair=${encodeURIComponent(symbol)}&interval=${code}&limit=${Math.max(1, Math.min(limit, 1000))}${to}`,
        method: 'GET',
      };
    },
    exchangeInfo: () => ({ url: '/api/v4/spot/currency_pairs', method: 'GET' }),
    parseTicker: (text, symbol) => {
      const row = arr(parse(text))[0];
      return row == null ? null : tickerOf(obj(row), symbol);
    },
    parseKlines: (text) =>
      arr(parse(text)).flatMap((row) => {
        const a = arr(row);
        if (a.length < 7) return [];
        return [
          {
            timestamp: num(a[0]) * 1000,
            open: num(a[5]),
            high: num(a[3]),
            low: num(a[4]),
            close: num(a[2]),
            volume: num(a[6]),
          },
        ];
      }),
    parseExchangeInfo: (text) =>
      arr(parse(text)).flatMap((row) => {
        const o = obj(row);
        const symbol = str(o.id);
        const base = str(o.base);
        const quote = str(o.quote);
        if (symbol == null || base == null || quote == null) return [];
        if (str(o.trade_status) !== 'tradable') return [];
        const precision = num(o.precision);
        const p = Math.max(0, Math.min(12, Number.isFinite(precision) ? precision : 2));
        return [
          {
            symbol,
            baseAsset: base,
            quoteAsset: quote,
            tickSize: p === 0 ? '1' : (10 ** -p).toFixed(p),
          },
        ];
      }),
  };
})();

// —————————————————————————— 币安现货 ——————————————————————————

/**
 * 币安现货周期表。与 Gate 的**分钟集合完全相同**（15 档），只是名字不同
 * （`1w`/`1M` vs `7d`/`30d`），所以界面上的周期条与聚合路径不用分叉。
 */
const BINANCE_LADDER: Record<number, string> = {
  1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
  60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h', 720: '12h',
  1_440: '1d', 4_320: '3d', 10_080: '1w', 43_200: '1M',
};

/**
 * 币安现货的报价币（按长度倒序匹配后缀，长的先试，`BTCFDUSD` 才不会被读成 `BTCFD`）。
 * 不在表里的冷门报价币解析不出来 —— 那只影响**搜索快照**里那几行没有价格，
 * 不影响按符号精确取数（那条路径的符号是已知的，不用拆）。
 */
const BINANCE_QUOTES = [
  'USDT', 'USDC', 'FDUSD', 'TUSD', 'BUSD', 'USDP', 'USDS', 'DAI', 'AEUR',
  'EUR', 'TRY', 'BRL', 'ARS', 'JPY', 'GBP', 'AUD', 'RUB', 'UAH', 'ZAR',
  'PLN', 'RON', 'NGN', 'IDRT', 'BVND', 'BIDR', 'BTC', 'ETH', 'BNB',
].sort((a, b) => b.length - a.length);

/** 规范符号（`BTC_USDT`）→ 币安原生写法（`BTCUSDT`）。 */
function binanceSymbol(symbol: string): string {
  return symbol.replace('_', '');
}

/** 币安原生写法（`BTCUSDT`）→ 规范符号；报价币认不出返回 null。 */
export function canonicalSpotSymbol(native: string): string | null {
  const upper = native.toUpperCase();
  for (const quote of BINANCE_QUOTES) {
    if (upper.length > quote.length && upper.endsWith(quote)) {
      return `${upper.slice(0, -quote.length)}_${quote}`;
    }
  }
  return null;
}

const binance: SpotDialect = (() => {
  const tickerOf = (row: Record<string, unknown>, symbol: string): Ticker24h => ({
    symbol,
    lastPrice: num(row.lastPrice),
    // 币安直接给涨跌幅，不必拿开盘价再算一遍
    priceChangePercent: num(row.priceChangePercent),
    highPrice: num(row.highPrice),
    lowPrice: num(row.lowPrice),
    quoteVolume: num(row.quoteVolume),
  });

  return {
    intervalLadder: BINANCE_LADDER,
    maxKlineLimit: 1000,
    ticker: (symbol) => ({
      url: `/api/v3/ticker/24hr?symbol=${binanceSymbol(symbol)}`,
      method: 'GET',
    }),
    klines: (symbol, minutes, limit, endMs) => {
      const code = BINANCE_LADDER[minutes];
      if (code == null) throw new Error('币安现货不支持该周期');
      const end = endMs == null ? '' : `&endTime=${Math.floor(endMs)}`;
      return {
        url: `/api/v3/klines?symbol=${binanceSymbol(symbol)}&interval=${code}&limit=${Math.max(1, Math.min(limit, 1000))}${end}`,
        method: 'GET',
      };
    },
    exchangeInfo: () => ({ url: '/api/v3/exchangeInfo', method: 'GET' }),
    parseTicker: (text, symbol) => tickerOf(obj(parse(text)), symbol),
    parseKlines: (text) =>
      arr(parse(text)).flatMap((row) => {
        const a = arr(row);
        if (a.length < 6) return [];
        return [
          {
            timestamp: num(a[0]),
            open: num(a[1]),
            high: num(a[2]),
            low: num(a[3]),
            close: num(a[4]),
            volume: num(a[5]),
          },
        ];
      }),
    parseExchangeInfo: (text) =>
      arr(obj(parse(text)).symbols).flatMap((row) => {
        const o = obj(row);
        const base = str(o.baseAsset);
        const quote = str(o.quoteAsset);
        if (base == null || quote == null) return [];
        if (str(o.status) !== 'TRADING') return [];
        const filter = arr(o.filters).find((f) => str(obj(f).filterType) === 'PRICE_FILTER');
        const tick = tickText(filter == null ? undefined : obj(filter).tickSize);
        if (tick == null) return [];
        return [{ symbol: `${base}_${quote}`, baseAsset: base, quoteAsset: quote, tickSize: tick }];
      }),
  };
})();

const SPOT_DIALECTS = { GATE: gate, BINANCE: binance } as const;

export type SpotSourceId = keyof typeof SPOT_DIALECTS;

export function spotDialectOf(id: SpotSourceId): SpotDialect {
  return SPOT_DIALECTS[id];
}
