/**
 * 单次请求取不满时「往前多翻几页」拼成一段（纯函数，不碰网络，便于单测）。
 *
 * 为什么需要：各家单次 K 线根数上限不同（OKX 300、Hyperliquid 500、币安 / Bybit 1000），
 * 而长档 EMA 要「最长档位 × 4」根预热（见 `emaAnalysis.EMA_WARMUP_FACTOR`）——
 * 上限低的源只取一页，长档 EMA 会被 SMA 种子拖偏。
 *
 * 口径：
 * - 第一页取「`endMs` 之前（缺省 = 最新）的 `pageSize` 根」，之后每页的 `endMs` = 已拿到的最老一根 − 1ms；
 * - 按时间戳去重（各家 `end` 有闭区间的，边界那根会重复回来），出口升序、至多 `wanted` 根；
 * - **停止条件**：凑够了 / 一页里没有新数据（到了上市第一根，或服务端无视时间参数）/ 页数到顶；
 *   不按「短页」判到头 —— MEXC、Hyperliquid 这种按时间窗倒推的方言，一页差一两根是常态；
 * - 第一页失败照常抛错；**后面的页失败就收手**，用已经拿到的（Bitget 这类历史深度有限的源，
 *   越界会直接报参数错，这时手上的数据仍是可用的最长一段）。
 */

import type { Bar } from './api';

/** 一次取数最多翻几页：OKX 300 根/页时 1000 根要 4 页；自定义周期按倍率放大后会更多，封顶防拖死。 */
export const MAX_KLINE_PAGES = 10;

export async function pageBackwards(
  wanted: number,
  pageSize: number,
  endMs: number | undefined,
  fetchPage: (limit: number, endMs: number | undefined) => Promise<Bar[]>,
  maxPages = MAX_KLINE_PAGES,
): Promise<Bar[]> {
  const byTime = new Map<number, Bar>();
  let end = endMs;
  for (let page = 0; page < maxPages && byTime.size < wanted; page++) {
    let rows: Bar[];
    try {
      rows = await fetchPage(pageSize, end);
    } catch (error) {
      if (page === 0) throw error;
      break;
    }
    const before = byTime.size;
    let oldest = Infinity;
    for (const bar of rows) {
      if (!byTime.has(bar.timestamp)) byTime.set(bar.timestamp, bar);
      if (bar.timestamp < oldest) oldest = bar.timestamp;
    }
    if (byTime.size === before || !Number.isFinite(oldest)) break;
    end = oldest - 1;
  }
  const bars = [...byTime.values()].sort((a, b) => a.timestamp - b.timestamp);
  return bars.length > wanted ? bars.slice(bars.length - wanted) : bars;
}
