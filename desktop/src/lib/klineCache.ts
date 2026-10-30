import type { Bar } from './api';

/**
 * 周期切换的 K 线内存缓存。
 *
 * 切周期本来要整段重拉几百根（`emaWindow` 口径 500~1000），一来一回就是肉眼可见的等待，
 * 还必然先过一遍「骨架 + 遮罩」。而切回来看到的是**同一段行情**：数据几乎没变、
 * 变的只是最新那一两根。所以这里按「市场|标的|数据源|周期」留一份在内存里，
 * 切回来先同步画上、再按新鲜度决定要不要后台核对。
 *
 * 边界：
 * - 键里带 `source`：换盘口 / 换数据源就是另一家的数据，不能混用（与 `useMarketData` 同口径）。
 * - 只留 [MAX_ENTRIES] 条、按最近使用淘汰：翻过旧页的序列可能上万根，留太多白占内存；
 *   而「来回切」实际只涉及手边那几个周期。
 * - 不跨标的复用：标的换了是另一段行情，留着不但是错的，还会把两个盘口的蜡烛接在一起。
 */

/** 新鲜度：这么久之内取过就不再打接口（切回看过的周期 = 零请求）。 */
export const KLINE_FRESH_MS = 15_000;

const MAX_ENTRIES = 6;

interface Entry {
  bars: Bar[];
  fetchedAt: number;
}

const cache = new Map<string, Entry>();

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

/** 写入（或整份换掉）一条缓存；空数组不记 —— 那多半是一次没取到，别让它盖掉好数据。 */
export function rememberKlines(key: string, bars: readonly Bar[], now = Date.now()): void {
  if (bars.length === 0) return;
  cache.delete(key);
  cache.set(key, { bars: bars as Bar[], fetchedAt: now });
  while (cache.size > MAX_ENTRIES) {
    const oldest = cache.keys().next().value;
    if (oldest == null) break;
    cache.delete(oldest);
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
