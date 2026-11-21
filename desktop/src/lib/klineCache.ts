import type { Bar } from './api';

/**
 * 周期切换的 K 线内存缓存。
 *
 * 切周期要整段重拉几百根（`analysisWindow` 口径 500~1000），一来一回是肉眼可见的等待。
 * 所以这里按「市场|标的|数据源|周期」留一份在内存里：刚取过的那份能直接上屏，
 * 旧一点的当「请求失败 / 回来是空的」的退路，`mergeRefreshedKlines` 还靠它接住翻旧页补回来的历史。
 *
 * 用法是**两段式**：缓存仍在新鲜窗口内（[KLINE_FRESH_MS]）⇒ 直接上屏、零请求；
 * 已过期 ⇒ 图上先出骨架 + 「加载中」，等这一段的**最新数据**回来才落图 ——
 * 不拿过期的缓存垫一帧再偷偷换掉（那会让图自己在用户眼皮底下变一次）。
 * 缓存过期时唯一的用处是兜底：请求失败 / 回来是空的，退回它。
 *
 * 边界：
 * - 键里带 `source`：换盘口 / 换数据源就是另一家的数据，不能混用（与 `useMarketData` 同口径）。
 * - 只留 [MAX_ENTRIES] 条、按最近使用淘汰：翻过旧页的序列可能上万根，留太多白占内存；
 *   而「来回切」实际只涉及手边那几个周期。
 * - 不跨标的复用：标的换了是另一段行情，留着不但是错的，还会把两个盘口的蜡烛接在一起。
 *
 * ❗**这里是全应用唯一的一份周期数据**（2026-11-21 起）：图表的取数、周期条预热、
 * 均线分析面板的扫描，三处都走 `lib/klineStore` 落到这张表上 —— 同一份行情不拉两遍，
 * 面板刷过的那一轮，切周期时直接就能用。
 */

/**
 * 「切过去可以直接上屏」的窗口：这么久之内取过的缓存，末几根由 2s 实时链路追平，
 * 图上看不出差别，于是不必再等一次往返（来回对比两个周期时不闪）。
 *
 * 只给 5s（≈ 两个实时节拍），**不是** 60s：60s 前那段与此刻的最新价差着末几根，
 * 先铺上去再换掉会让图在用户眼皮底下变一次 —— 那正是 2026-11-21 改掉的旧行为。
 * 预热调度有自己的一套新鲜度（`klineWarm.WARM_FRESH_MS`，60s 一轮），两者互不影响。
 */
export const KLINE_FRESH_MS = 5_000;

/**
 * 留几条：**至少覆盖一整个周期条**。原来只留 6 条，而默认周期条就有 8 个 chip ——
 * 一轮扫过去互相挤掉，于是「切回来零等待」对一半以上的周期根本不成立（这就是「切周期慢」的根）。
 * 一条序列几百到上万根，16 条最坏也就十几 MB，桌面端付得起。
 *
 * 32 而不是 16（2026-11-21）：均线分析面板一轮会把整条周期条（8~11 个周期）都写进来，
 * 16 格时它自己就占掉大半、把图表最近用的几个挤出去 —— 统一数据源反而让切周期更容易冷启动。
 * 两个标的各占一整条也还有富余。
 */
const MAX_ENTRIES = 32;

interface Entry {
  bars: Bar[];
  fetchedAt: number;
}

const cache = new Map<string, Entry>();

/** 写回缓存后要通知谁（图表要跟上「面板刚刷新的这一份」）。 */
export type KlineListener = (key: string, bars: Bar[]) => void;
const listeners = new Set<KlineListener>();

/**
 * 订阅「某条缓存被外部写入了」（面板的扫描 / 预热）。
 *
 * 图表用它把当前周期**静默合并**一份最新数据：不盖遮罩、不动翻页历史，只把重叠段换掉 ——
 * 面板一刷新，图上最后一根也就跟着准了，而用户看不到任何「重新加载」。
 * 组件自己写回时默认不通知（见 [rememberKlines] 的 `notify`），否则 setCandles → 写回 →
 * 通知 → setCandles 会自己转起来。
 */
export function subscribeKlines(listener: KlineListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function klineCacheKey(
  market: string,
  symbol: string,
  source: string,
  interval: string,
): string {
  return `${market}|${symbol}|${source}|${interval}`;
}

/** 取一份缓存（命中即视为「最近用过」，淘汰时排最后）。 */
export function recallKlines(key: string): Bar[] | undefined {
  const hit = cache.get(key);
  if (!hit) return undefined;
  cache.delete(key);
  cache.set(key, hit);
  return hit.bars;
}

/** 这条缓存是不是刚取过（[KLINE_FRESH_MS] 之内）。 */
export function isKlineFresh(key: string, now = Date.now()): boolean {
  const hit = cache.get(key);
  return hit != null && now - hit.fetchedAt < KLINE_FRESH_MS;
}

/**
 * 这份缓存能不能直接交给调用方（`lib/klineStore` 的命中判定）。
 *
 * 三个条件缺一不可：有数据、**根数够**、没超过新鲜窗口。
 * 根数这条是「统一数据源」的前提 —— 面板要 500 根，图表那 200 根帮不上忙；
 * 反过来图表要 200 根时，面板那 500 根完全够用（尾部对齐、末尾就是最新一根）。
 * 抽成纯函数是为了能直接断言（`verify-kline-store.mjs`）。
 */
export function canServeFromCache(
  bars: readonly Bar[] | undefined,
  fetchedAt: number | undefined,
  want: number,
  freshMs: number,
  now = Date.now(),
): boolean {
  if (bars == null || bars.length === 0) return false;
  if (bars.length < want) return false;
  if (freshMs <= 0) return false;
  if (fetchedAt == null) return false;
  return now - fetchedAt < freshMs;
}

/**
 * 这条缓存是什么时候取回来的（`undefined` = 没这条）。
 * 预热调度器靠它算「每个周期各饿多久」，所以**不**按访问刷新 LRU（读一次不算用过）。
 */
export function klineFetchedAt(key: string): number | undefined {
  return cache.get(key)?.fetchedAt;
}

/**
 * 写入（或整份换掉）一条缓存；空数组不记 —— 那多半是一次没取到，别让它盖掉好数据。
 *
 * ❗**只增不减**（2026-11-21）：同一份行情现在有三个写者 —— 图表首屏（只要 `fastBars`，
 * 约 200 根）、图表补齐后的翻页历史、以及均线分析面板（要 `analysisWindow`，500~1000 根）。
 * 谁后写谁说话的话，图表切一次周期就会把面板那 500 根砍成 200 根，长档 EMA 立刻变回
 * 「预热未完成」，面板下一轮又得整段重取。所以短的那份进来时只换重叠段，
 * 更老的历史原样留着（[mergeRefreshedKlines] 的语义）。
 *
 * `notify`：写的是**外部数据**（面板扫描 / 预热）时为 true，图表据此静默合并；
 * 图表自己的写回保持 false（它已经有这份数据了）。
 */
export function rememberKlines(
  key: string,
  bars: readonly Bar[],
  now = Date.now(),
  opts?: { notify?: boolean },
): void {
  if (bars.length === 0) return;
  const prev = cache.get(key);
  const next =
    prev != null && prev.bars.length > bars.length
      ? mergeRefreshedKlines(bars, prev.bars)
      : (bars as Bar[]);
  cache.delete(key);
  cache.set(key, { bars: next, fetchedAt: now });
  while (cache.size > MAX_ENTRIES) {
    const oldest = cache.keys().next().value;
    if (oldest == null) break;
    cache.delete(oldest);
  }
  if (opts?.notify) {
    for (const listener of listeners) listener(key, next);
  }
}

/** 只清缓存（测试用；正常流程靠 LRU 淘汰）。 */
export function clearKlineCache(): void {
  cache.clear();
}

/**
 * 后台核对回来的最新一段 + 缓存里的历史。
 *
 * 新窗口右侧是最新一根，所以只需保住**比新窗口更老**的那段（翻旧页补回来的历史），
 * 重叠部分一律以新数据为准（它更权威：含最新价与事后修正）。
 * 缓存是「从这里长出去」的，正常切回来两段严丝合缝；真有空档（缓存放了几分钟、
 * 中间又来过新蜡烛）也只是多留一段连续历史，不会画出断口。
 */
export function mergeRefreshedKlines(
  fresh: readonly Bar[],
  cached: readonly Bar[] | undefined,
): Bar[] {
  if (!cached || cached.length === 0 || fresh.length === 0) return fresh as Bar[];
  const firstFresh = fresh[0].timestamp;
  const older = cached.filter((bar) => bar.timestamp < firstFresh);
  return older.length === 0 ? (fresh as Bar[]) : [...older, ...fresh];
}
