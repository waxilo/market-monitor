import { fetchKlines, fetchOpenInterest, type Bar, type MarketType, type OpenInterestSeries } from './api';
import {
  canServeFromCache,
  klineCacheKey,
  klineFetchedAt,
  recallKlines,
  rememberKlines,
} from './klineCache';

/**
 * 周期数据的**唯一取数入口**（2026-11-21）。
 *
 * 在这之前有两个互不相干的取数者：图表（切周期 / 预热，要 `fastBars` ≈ 200 根）
 * 与均线分析面板（每 60s 扫一遍整条周期条，要 `analysisWindow` 500~1000 根）。
 * 同一份 K 线因此被拉了两遍 —— 面板每轮 8 个请求里，有 7 个正是预热刚刚暖过的周期。
 * 现在两者都走这里，落同一张 [klineCache]，于是：
 *
 *  1. **面板刷过的那一轮，切周期直接用**：缓存确实是刚从数据源取回的最新一段，
 *     不是「几十秒前的旧图」，所以不违反「切周期双段式」的口径；
 *  2. **同 key 同一时刻只有一个请求**（`inFlight`）：面板正在飞、图表又切过来时搭同一趟车；
 *  3. **根数各自要**：`want` 声明「我至少要几根」，缓存够长就直接给（长的给短的用永远安全 ——
 *     尾部对齐、末尾就是最新一根），不够才发请求。
 *
 * 新鲜度的两个口径分开传，别混：
 * - 图表 `freshMs = KLINE_FRESH_MS`（5s）：末几根由 2s 实时链路追平，5s 内不许再等一次往返；
 * - 面板 `freshMs = 0`：它自己按 60s 轮询，「刷新」这个动作必须真的去取，命中缓存等于没刷。
 */

export interface LoadKlinesOptions {
  market: MarketType;
  symbol: string;
  /** 当前数据源（`useSourceKey`）。换源 = 换盘口，缓存也按它分家。 */
  source: string;
  interval: string;
  /** 至少要多少根。缓存里比这短就不算命中（面板要 500 根时，图表那 200 根帮不上忙）。 */
  want: number;
  /** 缓存多久之内算「不必再取」，0 = 每次都真取。 */
  freshMs?: number;
  /** 写回后通知订阅者（面板扫描传 true，图表随之静默合并）。 */
  notify?: boolean;
}

interface InFlight {
  /** 这一趟要的根数：比它短的请求可以搭车，比它长的必须自己再取一次。 */
  want: number;
  promise: Promise<Bar[]>;
}

const inFlight = new Map<string, InFlight>();

/** 正在飞的请求数（调试 / 验证用：面板与图表撞上时这里应该是 1，不是 2）。 */
export function inFlightCount(): number {
  return inFlight.size;
}

export async function loadKlines(opts: LoadKlinesOptions): Promise<Bar[]> {
  const { market, symbol, source, interval, want, freshMs = 0, notify = false } = opts;
  const key = klineCacheKey(market, symbol, source, interval);

  if (canServeFromCache(recallKlines(key), klineFetchedAt(key), want, freshMs)) {
    return recallKlines(key) as Bar[];
  }

  const flying = inFlight.get(key);
  if (flying) {
    if (flying.want >= want) return flying.promise;
    // 它要的比自己少（图表 200 根 vs 面板 500 根）：等它落地、写进缓存，再照一次判断走 ——
    // 那时要么直接命中，要么缺口明确、发一次真正够长的。失败就交给下面重发。
    await flying.promise.catch(() => undefined);
    return loadKlines(opts);
  }

  // 已经有更长的一份就按更长的取：拿 500 根的请求去换 200 根，等于自废历史
  const limit = Math.max(want, recallKlines(key)?.length ?? 0);
  let promise: Promise<Bar[]>;
  promise = fetchKlines(market, symbol, interval, limit)
    .then((bars) => {
      if (bars.length > 0) rememberKlines(key, bars, Date.now(), { notify });
      return bars;
    })
    .finally(() => {
      if (inFlight.get(key)?.promise === promise) inFlight.delete(key);
    });
  inFlight.set(key, { want, promise });
  return promise;
}

// —————————————————————————— 持仓量（OI）副图 ——————————————————————————

/**
 * 持仓量是**独立的接口**（不是 K 线的某个字段），所以有自己的一份缓存 ——
 * 但同样走这里：key 口径与 K 线一致（市场|标的|源|周期），换源 / 换周期就是另一段 OI。
 *
 * 它在盘面上的用法是「每 10s 整段重拉」（OI 变化快，不能只追末点）。所以新鲜度就取
 * [OI_FRESH_MS] ≈ 轮询间隔：真正省下来的不是稳态轮询，而是**来回切周期 / 切标的**
 * 那几次（10s 内切回来直接有图，不用等一次完整往返），以及同一时刻的并发去重
 * （两个周期各自挂着一个副图、或面板与主图同时要）。
 */
export const OI_FRESH_MS = 10_000;

const oiCache = new Map<string, { series: OpenInterestSeries; fetchedAt: number }>();
const oiInFlight = new Map<string, Promise<OpenInterestSeries>>();

export interface LoadOiOptions {
  market: MarketType;
  symbol: string;
  source: string;
  interval: string;
  /** 要覆盖多少根 K 线的时间跨度（`fetchOpenInterest` 会按 base 周期折算点数）。 */
  want: number;
  freshMs?: number;
}

export async function loadOpenInterest(opts: LoadOiOptions): Promise<OpenInterestSeries> {
  const { market, symbol, source, interval, want, freshMs = OI_FRESH_MS } = opts;
  const key = `${klineCacheKey(market, symbol, source, interval)}|oi`;

  const hit = oiCache.get(key);
  if (hit != null && freshMs > 0 && Date.now() - hit.fetchedAt < freshMs) return hit.series;

  const flying = oiInFlight.get(key);
  if (flying != null) return flying;

  let promise: Promise<OpenInterestSeries>;
  promise = fetchOpenInterest(market, symbol, interval, want)
    .then((series) => {
      // 空序列不记：那多半是一次没取到，别让它盖掉好数据（与 K 线同口径）
      if (series.points.length > 0) oiCache.set(key, { series, fetchedAt: Date.now() });
      return series;
    })
    .finally(() => {
      if (oiInFlight.get(key) === promise) oiInFlight.delete(key);
    });
  oiInFlight.set(key, promise);
  return promise;
}
