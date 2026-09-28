/**
 * 永续合约的行情方言适配器 —— 逐条移植 App 的 `data/remote/dialect/FuturesDialects.kt`。
 *
 * 每个方言只负责两件事：「请求怎么发」（URL 与参数）与「响应怎么读」（归一化成
 * Bar / Instrument / TickerSnapshot）。传输统一走 lib/http.ts（宿主侧），
 * 容错口径与 App 一致：数字解析不出来按 0 兜，形状不对的行直接丢，包装体里的业务
 * 错误码（OKX/Bybit/Bitget/MEXC）先查、非 0 就抛。
 *
 * 符号约定：桌面端内部一律用 Gate 形（`BTC_USDT`），进适配器时换算成各家原生写法
 * （`BTCUSDT` / `BTC-USDT-SWAP` / `BTC` …），解析结果再换回来 —— 自选、搜索、图表
 * 这些下游因此完全不感知数据源。与 App 一样只做 USDT 本位永续。
 */

import type { Bar, Instrument } from './api';

export type DialectId =
  | 'BINANCE'
  | 'OKX'
  | 'BYBIT'
  | 'BITGET'
  | 'GATE'
  | 'MEXC'
  | 'HYPERLIQUID';

/** 对外请求规格：带 body 则以 application/json POST 发出（Hyperliquid 的 /info 是唯一 POST 方言）。 */
export interface HttpCall {
  url: string;
  method: 'GET' | 'POST';
  body?: string;
}

/** 24h 快照的归一化中间形；涨跌幅由上层按 (现价 − 开盘) / 开盘 算。 */
export interface TickerSnapshot {
  lastPrice: number;
  openPrice: number;
  highPrice: number;
  lowPrice: number;
  quoteVolume: number;
}

export interface FuturesDialect {
  /** 分钟数 → 该家原生周期码。 */
  intervalLadder: Record<number, string>;
  /** 单次 K 线请求的根数上限（超出会被接口截断）。 */
  maxKlineLimit: number;
  probe(baseUrl: string): HttpCall;
  klines(baseUrl: string, symbol: string, minutes: number, limit: number): HttpCall;
  /** 单标的 24h 快照。 */
  ticker(baseUrl: string, symbol: string): HttpCall;
  /** 全量 24h 快照：搜索结果要按成交额排序、给未自选的标的也显示价格。 */
  allTickers(baseUrl: string): HttpCall;
  exchangeInfo(baseUrl: string): HttpCall;
  parseKlines(text: string, minutes: number): Bar[];
  parseTicker(text: string): TickerSnapshot | null;
  /** 全量快照 → [规范符号, 快照]。 */
  parseAllTickers(text: string): [string, TickerSnapshot][];
  parseExchangeInfo(text: string): Instrument[];
}

/** 界面上的周期 → 分钟数（适配器按它查各家原生周期码）。 */
export const INTERVAL_MINUTES: Record<string, number> = {
  '1m': 1,
  '5m': 5,
  '15m': 15,
  '30m': 30,
  '1h': 60,
  '4h': 240,
  '1d': 1_440,
  '1w': 10_080,
};

// —————————————————————————— 小工具（对齐 App 的 Kotlin 扩展） ——————————————————————————

/** 各家响应字段差异极大，逐字段建接口等于给 7 家抄一遍 DTO；这里按字段取用。 */
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

/** App 的 `bd()`：解析不出来按 0 兜（Gate 等家给字符串，个别镜像给数字）。 */
function numOf(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** 缺字段要能区别于「值为 0」——开盘价缺失时以最新价代位，靠的就是这个 null。 */
function numOrNull(value: unknown): number | null {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function trimBase(base: string): string {
  return base.replace(/\/+$/, '');
}

/** 桌面端规范符号 `BTC_USDT` → 币名；各家原生写法都从币名拼。 */
function coinOf(symbol: string): string {
  return symbol.split('_')[0];
}

function canonical(coin: string): string {
  return `${coin}_USDT`;
}

/** 各家原生符号（`BTCUSDT`）→ 规范符号；非 USDT 本位返回 null。 */
function usdtSymbol(native: string | undefined): string | null {
  return native != null && native.endsWith('USDT') ? canonical(native.slice(0, -4)) : null;
}

function cap(limit: number, max: number): number {
  return Math.max(1, Math.min(Math.trunc(limit), max));
}

function ladderCode(ladder: Record<number, string>, minutes: number, label: string): string {
  const code = ladder[minutes];
  if (code == null) throw new Error(`${label}不支持该周期`);
  return code;
}

/** 最小价位 → 定点字符串：`1e-7` 这种落成指数形式会让展示层数错小数位。 */
function tickString(value: number | null): string | undefined {
  if (value == null || !Number.isFinite(value) || value <= 0) return undefined;
  const s = value.toFixed(12).replace(/0+$/, '');
  return s.endsWith('.') ? s.slice(0, -1) : s;
}

/** 缺 24h 开盘价时以最新价代位：宁可显示 0.00% 也不让整行消失（与 App 的 tickerOf 同）。 */
function snapshotOf(
  last: number | null,
  open: number | null,
  high: number | null,
  low: number | null,
  quote: number | null,
): TickerSnapshot | null {
  if (last == null) return null;
  return {
    lastPrice: last,
    openPrice: open ?? last,
    highPrice: high ?? last,
    lowPrice: low ?? last,
    quoteVolume: quote ?? 0,
  };
}

function candle(
  timestamp: number,
  open: number | null,
  high: number | null,
  low: number | null,
  close: number | null,
  volume: number | null,
): Bar | null {
  if (open == null || high == null || low == null || close == null) return null;
  return { timestamp, open, high, low, close, volume: volume ?? 0 };
}

/** 只有单侧成交量时按收盘价折算另一侧（展示用近似，与 App 同）。 */
function quoteOf(base: number | null, close: number | null): number | null {
  return base != null && close != null ? base * close : null;
}

function baseOf(quote: number | null, close: number | null): number | null {
  return quote != null && close ? quote / close : null;
}

function compact<T>(rows: (T | null)[]): T[] {
  return rows.filter((row): row is T => row != null);
}

function byTime(a: Bar, b: Bar): number {
  return a.timestamp - b.timestamp;
}

function checkOkx(root: unknown): void {
  const o = obj(root);
  const code = str(o.code);
  if (code != null && code !== '0') throw new Error(`OKX ${code}：${str(o.msg) ?? ''}`);
}

function checkBybit(root: unknown): void {
  const o = obj(root);
  const ret = numOf(o.retCode);
  if (ret !== 0) throw new Error(`Bybit ${ret}：${str(o.retMsg) ?? ''}`);
}

function checkBitget(root: unknown): void {
  const o = obj(root);
  const code = str(o.code);
  if (code != null && code !== '00000') throw new Error(`Bitget ${code}：${str(o.msg) ?? ''}`);
}

function checkMexc(root: unknown): void {
  const o = obj(root);
  if (o.success === false || numOf(o.code) !== 0) {
    throw new Error(`MEXC ${numOf(o.code)}：${str(o.message) ?? ''}`);
  }
}

const get = (url: string): HttpCall => ({ method: 'GET', url });
const post = (url: string, body: unknown): HttpCall => ({
  method: 'POST',
  url,
  body: JSON.stringify(body),
});

// —————————————————————————— 币安同构（Aster / 币安主域与镜像） ——————————————————————————

/** 与 fapi.binance.com `/fapi/v1` 完全同构（Aster 即此列）：路径、参数、K 线行序都照币安。 */
const binance: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h', 720: '12h',
    1_440: '1d', 4_320: '3d', 10_080: '1w', 43_200: '1M',
  };
  const max = 1000;
  const url = (base: string, path: string) => `${trimBase(base)}/fapi/v1${path}`;

  /** 价格精度来自 PRICE_FILTER（App 缺它就丢该标的：兜一个 0 更糟）。 */
  const tickOf = (row: Record<string, unknown>): string | undefined => {
    const filter = arr(row.filters).find((f) => str(obj(f).filterType) === 'PRICE_FILTER');
    return filter ? tickString(numOrNull(obj(filter).tickSize)) : undefined;
  };

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null =>
    snapshotOf(
      numOrNull(row.lastPrice),
      numOrNull(row.openPrice),
      numOrNull(row.highPrice),
      numOrNull(row.lowPrice),
      numOrNull(row.quoteVolume),
    );

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/ping')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/klines?symbol=${coinOf(symbol)}USDT&interval=${ladderCode(ladder, minutes, '币安同构')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(url(base, `/ticker/24hr?symbol=${coinOf(symbol)}USDT`)),
    allTickers: (base) => get(url(base, '/ticker/24hr')),
    exchangeInfo: (base) => get(url(base, '/exchangeInfo')),

    // 官方顺序 [开盘时间, o, h, l, c, volume, 收盘时间, quoteVolume, trades, …]
    parseKlines: (text) =>
      compact(
        arr(parse(text)).map((row) => {
          const a = arr(row);
          if (a.length < 9) return null;
          return candle(numOf(a[0]), numOrNull(a[1]), numOrNull(a[2]), numOrNull(a[3]), numOrNull(a[4]), numOrNull(a[5]));
        }),
      ).sort(byTime),

    parseTicker: (text) => rowTicker(obj(parse(text))),

    parseAllTickers: (text) =>
      compact(
        arr(parse(text)).map((row) => {
          const o = obj(row);
          const symbol = usdtSymbol(str(o.symbol));
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        arr(obj(parse(text)).symbols).map((row) => {
          const o = obj(row);
          const base = str(o.baseAsset);
          const tick = tickOf(o);
          if (str(o.quoteAsset) !== 'USDT' || base == null || tick == null) return null;
          return { symbol: canonical(base), baseAsset: base, quoteAsset: 'USDT', tickSize: tick };
        }),
      ),
  };
})();

// —————————————————————————— OKX v5 ——————————————————————————

/**
 * OKX（www.okx.com）。SWAP 行的 K 线列序为
 * `[开盘ms, o, h, l, c, 张数, volCcy(基础币), volCcyQuote(计价币)]`，响应 newest-first。
 * limit 上限 300，比币安小。
 */
const okx: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1H', 120: '2H', 240: '4H', 360: '6H', 720: '12H',
    1_440: '1D', 4_320: '3D', 10_080: '1W', 43_200: '1M',
  };
  const max = 300;
  const native = (symbol: string) => `${coinOf(symbol)}-USDT-SWAP`;
  const url = (base: string, path: string) => `${trimBase(base)}/api/v5${path}`;
  const data = (text: string): unknown[] => {
    const root = parse(text);
    checkOkx(root);
    return arr(obj(root).data);
  };

  const instSymbol = (instId: string | undefined): string | null =>
    instId != null && instId.endsWith('-USDT-SWAP') ? canonical(instId.slice(0, -'-USDT-SWAP'.length)) : null;

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null => {
    // 衍生品口径无现成计价币量，按最新价折算
    const last = numOrNull(row.last);
    const baseVol = numOrNull(row.volCcy24h);
    return snapshotOf(
      last,
      numOrNull(row.open24h),
      numOrNull(row.high24h),
      numOrNull(row.low24h),
      quoteOf(baseVol, last),
    );
  };

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/public/time')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/market/candles?instId=${native(symbol)}&bar=${ladderCode(ladder, minutes, 'OKX')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(url(base, `/market/ticker?instId=${native(symbol)}`)),
    allTickers: (base) => get(url(base, '/market/tickers?instType=SWAP')),
    exchangeInfo: (base) => get(url(base, '/public/instruments?instType=SWAP')),

    parseKlines: (text) =>
      compact(
        data(text).map((row) => {
          const a = arr(row);
          return candle(numOf(a[0]), numOrNull(a[1]), numOrNull(a[2]), numOrNull(a[3]), numOrNull(a[4]), numOrNull(a[6]));
        }),
      ).sort(byTime),

    parseTicker: (text) => {
      const row = obj(data(text)[0]);
      return rowTicker(row);
    },

    parseAllTickers: (text) =>
      compact(
        data(text).map((row) => {
          const o = obj(row);
          const symbol = instSymbol(str(o.instId));
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        data(text).map((row) => {
          const o = obj(row);
          const instId = str(o.instId);
          const symbol = instSymbol(instId);
          const base = str(o.baseCcy) || (instId != null ? instId.split('-')[0] : '');
          if (symbol == null || !base || str(o.state) !== 'live') return null;
          return {
            symbol,
            baseAsset: base,
            quoteAsset: 'USDT',
            tickSize: tickString(numOrNull(o.tickSz)),
          };
        }),
      ),
  };
})();

// —————————————————————————— Bybit v5 ——————————————————————————

/** Bybit（api.bybit.com）。linear 即 USDT 永续，volume=基础币、turnover=USDT。 */
const bybit: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1', 3: '3', 5: '5', 15: '15', 30: '30',
    60: '60', 120: '120', 240: '240', 360: '360', 720: '720',
    1_440: 'D', 10_080: 'W', 43_200: 'M',
  };
  const max = 1000;
  const url = (base: string, path: string) => `${trimBase(base)}/v5${path}`;
  const rows = (text: string): unknown[] => {
    const root = parse(text);
    checkBybit(root);
    return arr(obj(obj(root).result).list);
  };

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null =>
    snapshotOf(
      numOrNull(row.lastPrice),
      // 没有 24h 开盘价字段：prevPrice24h 是「24h 前的最新价」，行业通用的涨跌幅基准，够用
      numOrNull(row.prevPrice24h),
      numOrNull(row.highPrice24h),
      numOrNull(row.lowPrice24h),
      numOrNull(row.turnover24h),
    );

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/market/time')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/market/kline?category=linear&symbol=${coinOf(symbol)}USDT&interval=${ladderCode(ladder, minutes, 'Bybit')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(url(base, `/market/tickers?category=linear&symbol=${coinOf(symbol)}USDT`)),
    allTickers: (base) => get(url(base, '/market/tickers?category=linear')),
    exchangeInfo: (base) => get(url(base, '/market/instruments-info?category=linear&limit=1000')),

    parseKlines: (text) =>
      compact(
        rows(text).map((row) => {
          const a = arr(row);
          return candle(numOf(a[0]), numOrNull(a[1]), numOrNull(a[2]), numOrNull(a[3]), numOrNull(a[4]), numOrNull(a[5]));
        }),
      ).sort(byTime),

    parseTicker: (text) => rowTicker(obj(rows(text)[0])),

    parseAllTickers: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const symbol = usdtSymbol(str(o.symbol));
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          if (str(o.contractType) !== 'LinearPerpetual' || str(o.quoteCoin) !== 'USDT') return null;
          if (str(o.status) !== 'Trading') return null;
          const scale = numOrNull(o.priceScale);
          const tick = numOrNull(obj(o.priceFilter).tickSize) ?? (scale != null ? 10 ** -scale : null);
          const symbol = usdtSymbol(str(o.symbol));
          const base = str(o.baseCoin);
          if (symbol == null || !base) return null;
          return { symbol, baseAsset: base, quoteAsset: 'USDT', tickSize: tickString(tick) };
        }),
      ),
  };
})();

// —————————————————————————— Bitget v2 mix ——————————————————————————

/** Bitget（api.bitget.com）USDT-FUTURES。K 线行内第 6/7 列即基础/计价币量，升序返回。 */
const bitget: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 720: '12h',
    1_440: '1d', 4_320: '3d', 10_080: '1w', 43_200: '1M',
  };
  const max = 1000;
  const url = (base: string, path: string) => `${trimBase(base)}/api/v2${path}`;
  const data = (text: string): unknown => {
    const root = parse(text);
    checkBitget(root);
    return obj(root).data;
  };
  const rows = (text: string): unknown[] => arr(data(text));
  /** 单标的文档示例是 1 元素数组，历史版本给对象 —— 两种都认（与 App 同）。 */
  const singleRow = (text: string): Record<string, unknown> =>
    obj(arr(data(text))[0] ?? data(text));

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null =>
    snapshotOf(
      numOrNull(row.lastPr),
      numOrNull(row.open24h),
      numOrNull(row.high24h),
      numOrNull(row.low24h),
      numOrNull(row.quoteVolume),
    );

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/public/time')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/mix/market/candles?symbol=${coinOf(symbol)}USDT&productType=usdt-futures&granularity=${ladderCode(ladder, minutes, 'Bitget')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) =>
      get(url(base, `/mix/market/ticker?symbol=${coinOf(symbol)}USDT&productType=usdt-futures`)),
    allTickers: (base) => get(url(base, '/mix/market/tickers?productType=usdt-futures')),
    exchangeInfo: (base) => get(url(base, '/mix/market/contracts?productType=usdt-futures')),

    parseKlines: (text) =>
      compact(
        rows(text).map((row) => {
          const a = arr(row);
          return candle(numOf(a[0]), numOrNull(a[1]), numOrNull(a[2]), numOrNull(a[3]), numOrNull(a[4]), numOrNull(a[5]));
        }),
      ).sort(byTime),

    parseTicker: (text) => rowTicker(singleRow(text)),

    parseAllTickers: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const symbol = usdtSymbol(str(o.symbol));
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          if (str(o.quoteCoin) !== 'USDT') return null;
          if (str(o.symbolStatus) !== 'normal' && str(o.symbolStatus) !== 'listed') return null;
          // 价格精度 = priceEndStep × 10^-pricePlace（如 step=1、place=1 → tick 0.1）
          const place = numOrNull(o.pricePlace);
          const step = numOrNull(o.priceEndStep) ?? 1;
          const tick = place != null && place >= 0 && place <= 12 ? step / 10 ** place : null;
          const symbol = usdtSymbol(str(o.symbol));
          const base = str(o.baseCoin);
          if (symbol == null || !base) return null;
          return { symbol, baseAsset: base, quoteAsset: 'USDT', tickSize: tickString(tick) };
        }),
      ),
  };
})();

// —————————————————————————— Gate v4 futures ——————————————————————————

/**
 * Gate（api.gateio.ws）。K 线是对象数组且时间单位为秒；`v` 以「张」计，
 * 基础币量按 `sum / close` 折算（`sum` 是计价币金额）。无 24h 开盘字段，
 * 用 `last - change_price` 还原。
 */
const gate: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h',
    1_440: '1d', 10_080: '7d',
  };
  const max = 2000;
  const native = (symbol: string) => `${coinOf(symbol)}_USDT`;
  const url = (base: string, path: string) => `${trimBase(base)}/api/v4/futures/usdt${path}`;
  const rows = (text: string): unknown[] => arr(parse(text));

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null => {
    const last = numOrNull(row.last);
    const change = numOrNull(row.change_price);
    return snapshotOf(
      last,
      last != null && change != null ? last - change : null,
      numOrNull(row.high_24h),
      numOrNull(row.low_24h),
      numOrNull(row.volume_24h_quote),
    );
  };

  /** 合约名即规范符号（`BTC_USDT`）。 */
  const rowSymbol = (row: Record<string, unknown>): string | null => {
    const name = str(row.contract) ?? str(row.name);
    return name != null && name.endsWith('_USDT') ? name : null;
  };

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/contracts/BTC_USDT')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/candlesticks?contract=${native(symbol)}&interval=${ladderCode(ladder, minutes, 'Gate')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(url(base, `/tickers?contract=${native(symbol)}`)),
    allTickers: (base) => get(url(base, '/tickers')),
    exchangeInfo: (base) => get(url(base, '/contracts')),

    parseKlines: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const close = numOrNull(o.c);
          return candle(
            numOf(o.t) * 1000,
            numOrNull(o.o),
            numOrNull(o.h),
            numOrNull(o.l),
            close,
            baseOf(numOrNull(o.sum), close),
          );
        }),
      ).sort(byTime),

    parseTicker: (text) => rowTicker(obj(rows(text)[0])),

    parseAllTickers: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const symbol = rowSymbol(o);
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const name = str(o.name);
          if (name == null || !name.endsWith('_USDT') || str(o.in_delisting) === 'true') return null;
          const base = str(o.baseCoin) || name.split('_')[0];
          return {
            symbol: name,
            baseAsset: base,
            quoteAsset: 'USDT',
            tickSize: tickString(numOrNull(o.order_price_round)),
          };
        }),
      ),
  };
})();

// —————————————————————————— MEXC contract v1 ——————————————————————————

/**
 * MEXC（contract.mexc.com）。K 线是「列存」平行数组，时间单位为秒、**没有 limit 参数**
 * （固定返回窗口内至多 2000 根，窗口按 limit×周期倒推）。量以张计，
 * 基础币量按 `amount / close` 折算。24h 开盘用 `lastPrice - riseFallValue` 还原。
 */
const mexc: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: 'Min1', 5: 'Min5', 15: 'Min15', 30: 'Min30', 60: 'Min60',
    240: 'Hour4', 480: 'Hour8', 1_440: 'Day1', 10_080: 'Week1', 43_200: 'Month1',
  };
  const max = 2000;
  const native = (symbol: string) => `${coinOf(symbol)}_USDT`;
  const url = (base: string, path: string) => `${trimBase(base)}/api/v1/contract${path}`;
  const data = (text: string): unknown => {
    const root = parse(text);
    checkMexc(root);
    return obj(root).data;
  };
  const rows = (text: string): unknown[] => arr(data(text));
  /** 带 symbol 时是对象，个别版本给单元素数组 —— 两种都认（与 App 同）。 */
  const singleRow = (text: string): Record<string, unknown> => obj(arr(data(text))[0] ?? data(text));

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null => {
    const last = numOrNull(row.lastPrice);
    const rise = numOrNull(row.riseFallValue);
    return snapshotOf(
      last,
      last != null && rise != null ? last - rise : null,
      numOrNull(row.high24Price),
      numOrNull(row.lower24Price),
      numOrNull(row.amount24),
    );
  };

  const rowSymbol = (row: Record<string, unknown>): string | null => {
    const symbol = str(row.symbol);
    if (symbol == null || !symbol.endsWith('_USDT') || str(row.settleCoin) !== 'USDT') return null;
    return symbol;
  };

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/ping')),
    klines: (base, symbol, minutes, limit) => {
      const capped = cap(limit, max);
      const endSec = Math.floor(Date.now() / 1000);
      const startSec = endSec - capped * minutes * 60;
      return get(
        url(
          base,
          `/kline/${native(symbol)}?interval=${ladderCode(ladder, minutes, 'MEXC')}&start=${startSec}&end=${endSec}`,
        ),
      );
    },
    ticker: (base, symbol) => get(url(base, `/ticker?symbol=${native(symbol)}`)),
    allTickers: (base) => get(url(base, '/ticker')),
    exchangeInfo: (base) => get(url(base, '/detail')),

    parseKlines: (text) => {
      const d = obj(data(text));
      const times = arr(d.time);
      const open = arr(d.open);
      const high = arr(d.high);
      const low = arr(d.low);
      const close = arr(d.close);
      const amount = arr(d.amount);
      return compact(
        times.map((t, i) => {
          const c = numOrNull(close[i]);
          return candle(
            numOf(t) * 1000,
            numOrNull(open[i]),
            numOrNull(high[i]),
            numOrNull(low[i]),
            c,
            baseOf(numOrNull(amount[i]), c),
          );
        }),
      ).sort(byTime);
    },

    parseTicker: (text) => rowTicker(singleRow(text)),

    parseAllTickers: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const symbol = rowSymbol(o);
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    parseExchangeInfo: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const symbol = rowSymbol(o);
          const base = str(o.baseCoin);
          if (symbol == null || !base || str(o.state) !== '0') return null;
          return {
            symbol,
            baseAsset: base,
            quoteAsset: 'USDT',
            tickSize: tickString(numOrNull(o.priceUnit)),
          };
        }),
      ),
  };
})();

// —————————————————————————— Hyperliquid ——————————————————————————

/**
 * Hyperliquid（api.hyperliquid.xyz）：唯一的 POST 方言，一切走 `/info`。
 * 无 24h ticker —— 单标的快照用 1h×26 根蜡烛折叠（open 取最早一根、量逐根累加），
 * 与「每标的一次请求」的轮询节奏一致；蜡烛只有基础币量，计价币量按收盘价折算。
 */
const hyperliquid: FuturesDialect = (() => {
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h', 720: '12h',
    1_440: '1d', 4_320: '3d', 10_080: '1w', 43_200: '1M',
  };
  const max = 500;
  const info = (base: string, body: unknown): HttpCall => post(`${trimBase(base)}/info`, body);

  const candlesOf = (text: string): Record<string, unknown>[] => arr(parse(text)).map(obj);
  const barsOf = (text: string): Bar[] =>
    compact(
      candlesOf(text).map((row) =>
        candle(
          numOf(row.t),
          numOrNull(row.o),
          numOrNull(row.h),
          numOrNull(row.l),
          numOrNull(row.c),
          numOrNull(row.v),
        ),
      ),
    ).sort(byTime);

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => info(base, { type: 'meta' }),
    klines: (base, symbol, minutes, limit) => {
      const capped = cap(limit, max);
      const end = Date.now();
      // candleSnapshot 只认时间窗：按根数倒推起点，多给 20% 容忍缺数
      const start = end - Math.round((capped * minutes * 60_000 * 6) / 5);
      return info(base, {
        type: 'candleSnapshot',
        req: { coin: coinOf(symbol), interval: ladderCode(ladder, minutes, 'Hyperliquid'), startTime: start, endTime: end },
      });
    },
    ticker: (base, symbol) => {
      const end = Date.now();
      return info(base, {
        type: 'candleSnapshot',
        req: { coin: coinOf(symbol), interval: '1h', startTime: end - 26 * 3_600_000, endTime: end },
      });
    },
    allTickers: (base) => info(base, { type: 'metaAndAssetCtxs' }),
    exchangeInfo: (base) => info(base, { type: 'meta' }),

    parseKlines: (text) => barsOf(text),

    parseTicker: (text) => {
      const bars = barsOf(text);
      if (bars.length === 0) return null;
      const last = bars[bars.length - 1];
      let high = bars[0].high;
      let low = bars[0].low;
      let quote = 0;
      for (const bar of bars) {
        if (bar.high > high) high = bar.high;
        if (bar.low < low) low = bar.low;
        quote += quoteOf(bar.volume, bar.close) ?? 0;
      }
      return snapshotOf(last.close, bars[0].open, high, low, quote);
    },

    parseAllTickers: (text) => {
      // [meta, ctxs]：ctxs 与 universe 同序，markPx 当现价、dayNtlVlm 当 24h 成交额；
      // 无 24h 高低价，用现价代位（搜索结果只用到价格与成交额）
      const root = arr(parse(text));
      const universe = arr(obj(root[0]).universe);
      const ctxs = arr(root[1]);
      return compact(
        universe.map((row, i) => {
          const name = str(obj(row).name);
          const ctx = ctxs[i];
          if (!name || ctx == null) return null;
          const c = obj(ctx);
          const last = numOrNull(c.markPx) ?? numOrNull(c.midPx) ?? numOrNull(c.oraclePx);
          const snapshot = snapshotOf(last, numOrNull(c.prevDayPx), null, null, numOrNull(c.dayNtlVlm));
          return snapshot != null ? ([canonical(name), snapshot] as [string, TickerSnapshot]) : null;
        }),
      );
    },

    parseExchangeInfo: (text) =>
      compact(
        arr(obj(parse(text)).universe).map((row) => {
          const o = obj(row);
          const name = str(o.name);
          if (!name || str(o.isDelisted) === 'true') return null;
          // 永续价格规则：最多 5 位有效数字且不超过 6 - szDecimals 位小数；
          // 拿不到现价，按小数位上限给 tick（只影响展示位数，宁多勿少）
          const decimals = Math.max(1, Math.min(8, 6 - (numOrNull(o.szDecimals) ?? 4)));
          return {
            symbol: canonical(name),
            baseAsset: name,
            quoteAsset: 'USDT',
            tickSize: tickString(10 ** -decimals),
          };
        }),
      ),
  };
})();

export const DIALECTS: Record<DialectId, FuturesDialect> = {
  BINANCE: binance,
  OKX: okx,
  BYBIT: bybit,
  BITGET: bitget,
  GATE: gate,
  MEXC: mexc,
  HYPERLIQUID: hyperliquid,
};
