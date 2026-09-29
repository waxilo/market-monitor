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
  | 'HYPERLIQUID'
  | 'HTX'
  | 'BITUNIX';

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

/**
 * HTX 的业务码在 `status` 里、**不在 HTTP 状态里**：非法周期实测回的是
 * `HTTP 200 + {"status":"error","err-code":"invalid-parameter"}`（详见 htx 方言注释）。
 */
function checkHtx(root: unknown): void {
  const o = obj(root);
  const status = str(o.status);
  if (status != null && status !== 'ok') {
    const code = str(o['err-code']);
    throw new Error(`HTX ${code ?? ''}：${str(o['err-msg']) ?? status}`);
  }
}

/** Bitunix 的业务码在 `code`（0 为成功）；缺字段时不误判成 0，故用 numOrNull。 */
function checkBitunix(root: unknown): void {
  const o = obj(root);
  const code = numOrNull(o.code);
  if (code != null && code !== 0) throw new Error(`Bitunix ${code}：${str(o.msg) ?? ''}`);
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
  /**
   * 实测白名单（用 Aster 同构端点打的，`.workbuddy/tmp/probe-intervals.log`）：
   * 表里这 15 个全 200 且时间戳落在各周期边界上；`2m` / `7m` / `10m` / `45m` / `3h` / `2d` /
   * `5d` / `2w` / `2M` 一律 `400 {"code":-1120,"msg":"Invalid interval."}`。
   * ⚠️ `1M` 是**按日历月**走的（相邻间隔实测 28/31/30 天），不是固定 43200 分钟。
   */
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
  /**
   * 实测白名单（`.workbuddy/tmp/probe-intervals*.log`）。⚠️ **OKX 的错法是 HTTP 200 +
   * 业务码 `51000 Parameter bar error` + `data: []`** —— 只看 HTTP 状态会把「不支持」读成「支持」。
   *
   * 支持：`1m 3m 5m 15m 30m 1H 2H 4H 6H 12H 1D 2D 3D 1W 1M`（`2D` 是这次实测补的）；
   * 被拒：`45m` / `3H` / `7H` / `2W` / `2M` / `bogus` —— 都是 51000。
   */
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1H', 120: '2H', 240: '4H', 360: '6H', 720: '12H',
    1_440: '1D', 2_880: '2D', 4_320: '3D', 10_080: '1W', 43_200: '1M',
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
  /**
   * ⚠️ **这张表本轮没能联网复测**（`api.bybit.com` 从本机 `fetch failed`，与
   * Bitget / MEXC 一样需要代理）—— 沿用之前对过的值，条目数少于其余几家。
   * 复测方法见 `.workbuddy/tmp/probe-intervals.mjs`。
   */
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
  /** ⚠️ **本轮没能联网复测**（`api.bitget.com` 从本机 `fetch failed`，需代理）—— 沿用之前对过的值。 */
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
  /**
   * 逐周期实测过的白名单（两份日志：`.workbuddy/tmp/probe-intervals.log` /
   * `probe-intervals-2.log`）。
   *
   * 支持：`1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 2d 3d 5d 7d 30d`
   * （`1w` 也认，与 `7d` 同一段时间）；被拒：`2m` / `45m` / `7h` / `2w` / `1M` / `bogus`
   * —— 一律 `400 INVALID_PARAM_VALUE`，所以 200 是「真的支持」而不是「宽容地向下取整」。
   *
   * ⚠️ `30d` 是**按日历月**走的（相邻间隔实测 28/31/30 天），不是固定 30 天；
   * 界面 id `1M` 映射到它属于既有的近似。
   */
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h', 720: '12h',
    1_440: '1d', 2_880: '2d', 4_320: '3d', 7_200: '5d', 10_080: '7d', 43_200: '30d',
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
  /** ⚠️ **本轮没能联网复测**（`contract.mexc.com` 从本机 `fetch failed`，需代理）—— 沿用之前对过的值。 */
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
  /**
   * 实测白名单（`.workbuddy/tmp/probe-intervals*.log`）：`1m 3m 5m 15m 30m 1h 2h 4h 8h 12h 1d 3d 1w 1M`。
   * ❗**`6h` 曾经在这张表里，但实测返回 `422`（拒绝反序列化该 interval）** ⇒ 删掉。
   * 删掉不会让「6h」这个周期用不了 —— `fetchKlines` 会用 2h 聚合成它（`480 % 120 === 0`），
   * 但留着它就会**以原生身份去请求并必然失败**（图空白），所以必须删。
   * 被拒：`45m` / `3h` / `6h` / `7h` / `2d` / `2w` / `2M` / `bogus`。
   */
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 480: '8h', 720: '12h',
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

// —————————————————————————— HTX（火币）USDT 本位永续 ——————————————————————————

/**
 * HTX / 火币（api.hbdm.com）。两套路径混用，别搞混：
 * - `linear-swap-ex/market/*` 是**行情**（K 线、detail/merged、batch_merged），裸 JSON 带 `status`；
 * - `linear-swap-api/v1/*` 是**合约元数据**（swap_contract_info）与 ping。
 *
 * ⚠️ **可达性不如其他家**：本次实测期间 `api.hbdm.com` / `api.htx.com` 的域名解析会落到
 * Meta 的 IP 段（`157.240.10.32` / `108.160.170.52` = 典型 DNS 污染），表现为间歇性连不上；
 * 同在一条网络下的 Gate / Bitunix / Hyperliquid 解析正常。所以它进清单是因为「接口最完整、
 * 通的时候最好用」，而不是「稳定」——探测弹窗里它时通时不通是真实情况，不是 bug。
 *
 * K 线行 = `{id(秒), open, close, high, low, amount, vol, trade_turnover, count}`，**升序**；
 * `amount` 是基础币数量、`vol` 是张数（`contract_size`）、`trade_turnover` 是计价币金额，
 * 三者的关系实测自洽（`amount × close ≈ trade_turnover`）。24h 快照自带 `open` 字段，
 * 无需像 Gate / Bybit / MEXC 那样还原。
 */
const htx: FuturesDialect = (() => {
  /**
   * 逐周期实测白名单：支持 `1min 3 5 15 30 60min 2 4 6 12hour 1day 3day 1week 1mon`；
   * 被拒的是 `1year`。对照（明显非法值 `bogus`）同样被拒，所以「200」是真支持而不是宽容回落。
   */
  const ladder: Record<number, string> = {
    1: '1min', 3: '3min', 5: '5min', 15: '15min', 30: '30min',
    60: '60min', 120: '2hour', 240: '4hour', 360: '6hour', 720: '12hour',
    1_440: '1day', 4_320: '3day', 10_080: '1week', 43_200: '1mon',
  };
  /** 官方文档给的上限是 2000（本次网络下未能复测到上限，取保守值）。 */
  const max = 2000;
  const native = (symbol: string) => `${coinOf(symbol)}-USDT`;
  const ex = (base: string, path: string) => `${trimBase(base)}/linear-swap-ex${path}`;
  const api = (base: string, path: string) => `${trimBase(base)}/linear-swap-api/v1${path}`;

  /** 先查业务码再取 data（K 线）或 ticks（全量快照）。 */
  const rows = (text: string, key: 'data' | 'ticks'): unknown[] => {
    const root = parse(text);
    checkHtx(root);
    return arr(obj(root)[key]);
  };

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null =>
    snapshotOf(
      numOrNull(row.close),
      numOrNull(row.open),
      numOrNull(row.high),
      numOrNull(row.low),
      numOrNull(row.trade_turnover),
    );

  /** 合约名即规范符号：`BTC-USDT` → `BTC_USDT`。 */
  const rowSymbol = (contractCode: string | undefined): string | null =>
    contractCode != null && contractCode.endsWith('-USDT')
      ? canonical(contractCode.slice(0, -'-USDT'.length))
      : null;

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(api(base, `/swap_contract_info?contract_code=${native('BTC_USDT')}`)),
    klines: (base, symbol, minutes, limit) =>
      get(
        ex(
          base,
          `/market/history/kline?contract_code=${native(symbol)}&period=${ladderCode(ladder, minutes, 'HTX')}&size=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(ex(base, `/market/detail/merged?contract_code=${native(symbol)}`)),
    allTickers: (base) => get(ex(base, '/market/detail/batch_merged')),
    exchangeInfo: (base) => get(api(base, '/swap_contract_info')),

    parseKlines: (text) =>
      compact(
        rows(text, 'data').map((row) => {
          const o = obj(row);
          const seconds = numOrNull(o.id);
          if (seconds == null) return null;
          return candle(
            seconds * 1000,
            numOrNull(o.open),
            numOrNull(o.high),
            numOrNull(o.low),
            numOrNull(o.close),
            numOrNull(o.amount),
          );
        }),
      ).sort(byTime),

    // detail/merged 的规格与 batch_merged 同构；`open` 万一缺失由 snapshotOf 以最新价代位
    parseTicker: (text) => {
      const root = parse(text);
      checkHtx(root);
      return rowTicker(obj(obj(root).tick));
    },

    parseAllTickers: (text) =>
      compact(
        rows(text, 'ticks').map((row) => {
          const o = obj(row);
          const symbol = rowSymbol(str(o.contract_code));
          const snapshot = rowTicker(o);
          return symbol != null && snapshot != null ? ([symbol, snapshot] as [string, TickerSnapshot]) : null;
        }),
      ),

    /** 元数据行的合约名在 `contract_code`，币名另有 `symbol`（如 `BTC`）。 */
    parseExchangeInfo: (text) =>
      compact(
        rows(text, 'data').map((row) => {
          const o = obj(row);
          const code = str(o.contract_code);
          const symbol = rowSymbol(code);
          const tick = tickString(numOrNull(o.price_tick));
          const base = str(o.symbol) ?? (code != null ? code.slice(0, -'-USDT'.length) : undefined);
          if (symbol == null || base == null || tick == null) return null;
          return { symbol, baseAsset: base, quoteAsset: 'USDT', tickSize: tick };
        }),
      ),
  };
})();

// —————————————————————————— Bitunix ——————————————————————————

/**
 * Bitunix（fapi.bitunix.com）。全部走 `/api/v1/futures/market/*`，裸 JSON 带 `code`。
 *
 * 本次实测里最「干净」的一家：直连稳定（走 Cloudflare）、四件套齐全、响应体积小。
 *
 * 两处必须留意：
 * - **K 线是降序返回**（newest-first），要倒过来；时间是毫秒。
 * - **单标的快照的路径是 `tickers?symbols=`（复数）**：`ticker?symbol=` 返回 `code:404`，
 *   `/tickers` 不带参数则是全量。
 * - ❗**非法 interval 返 `code:0` + `data:[]`**——不报错、也不回落，只是空数组。
 *   所以「请求没报错」在这里不等于「周期被支持」，判定必须看 `data` 是否非空；
 *   本适配器靠 `intervalLadder` 白名单把非法周期挡在请求之前。
 * - 价格精度取 `quotePrecision`（价格小数位）：B=10^-quotePrecision，实测 BTCUSDT(1)→0.1、
 *   DOGEUSDT(5)→0.00001、1000PEPEUSDT(7)→0.0000001 对得上。
 */
const bitunix: FuturesDialect = (() => {
  /** 实测 15 个周期全支持（`1m 3 5 15 30m 1 2 4 6 8 12h 1d 3d 1w 1M`）。`1M` 走**日历月**（实测 28~31 天）。 */
  const ladder: Record<number, string> = {
    1: '1m', 3: '3m', 5: '5m', 15: '15m', 30: '30m',
    60: '1h', 120: '2h', 240: '4h', 360: '6h', 480: '8h', 720: '12h',
    1_440: '1d', 4_320: '3d', 10_080: '1w', 43_200: '1M',
  };
  /** 实测上限：请求 500 / 1000 / 1500 一律只回 200 根（服务端截断）。 */
  const max = 200;
  const native = (symbol: string) => `${coinOf(symbol)}USDT`;
  const url = (base: string, path: string) => `${trimBase(base)}/api/v1/futures/market${path}`;
  const data = (text: string): unknown => {
    const root = parse(text);
    checkBitunix(root);
    return obj(root).data;
  };
  const rows = (text: string): unknown[] => arr(data(text));

  const rowTicker = (row: Record<string, unknown>): TickerSnapshot | null =>
    snapshotOf(
      numOrNull(row.lastPrice),
      numOrNull(row.open),
      numOrNull(row.high),
      numOrNull(row.low),
      numOrNull(row.quoteVol),
    );

  return {
    intervalLadder: ladder,
    maxKlineLimit: max,
    probe: (base) => get(url(base, '/time')),
    klines: (base, symbol, minutes, limit) =>
      get(
        url(
          base,
          `/kline?symbol=${native(symbol)}&interval=${ladderCode(ladder, minutes, 'Bitunix')}&limit=${cap(limit, max)}`,
        ),
      ),
    ticker: (base, symbol) => get(url(base, `/tickers?symbols=${native(symbol)}`)),
    allTickers: (base) => get(url(base, '/tickers')),
    exchangeInfo: (base) => get(url(base, '/trading_pairs')),

    parseKlines: (text) =>
      compact(
        rows(text).map((row) => {
          const o = obj(row);
          const ms = numOrNull(o.time);
          if (ms == null) return null;
          return candle(
            ms,
            numOrNull(o.open),
            numOrNull(o.high),
            numOrNull(o.low),
            numOrNull(o.close),
            numOrNull(o.baseVol),
          );
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
          if (str(o.quote) !== 'USDT' || str(o.symbolStatus) !== 'OPEN') return null;
          const base = str(o.base);
          const decimals = numOrNull(o.quotePrecision);
          const tick = decimals != null && decimals >= 0 && decimals <= 12 ? 10 ** -decimals : null;
          if (base == null || tick == null) return null;
          return { symbol: canonical(base), baseAsset: base, quoteAsset: 'USDT', tickSize: tickString(tick) };
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
  HTX: htx,
  BITUNIX: bitunix,
};
