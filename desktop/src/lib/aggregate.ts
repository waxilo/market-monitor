/**
 * 把**细粒度** K 线合并成**粗粒度**周期 —— `45m` / `7h` / `2d` 这类没有原生码的周期靠它。
 *
 * 为什么需要它：各家的 K 线周期是一张**固定的白名单**（实测：给它们一个表外的取值，
 * 一律 400 或悄悄回落 —— 见 `.workbuddy/tmp/probe-intervals.md`），所以「自定义周期」
 * 不可能原生直取，只能由能整除它的原生周期聚合出来。
 *
 * 正确性依赖一条算术事实：**若 `base` 整除 `target`，则每个 target 边界都是 base 边界**，
 * 于是「按 target 桶分组」得到的每组必定恰好覆盖一个完整的 target 周期 —— 不需要额外对齐。
 * 反过来 base 不整除 target 时（例：只有 30m 却要 45m）聚合**根本得不到正确结果**，
 * 所以这里直接抛错，而不是悄悄按根数切（那会画出一张「标签写着 45m、蜡烛其实是 30m」的图）。
 */

/**
 * 与 `api.ts` 的 `Bar` **结构同形** —— 刻意各自声明：本模块要保持零依赖，
 * 才能用 Node 直接跑单测（`.mts`）。TypeScript 的结构类型会让两者互相赋值。
 */
export interface Candle {
  /** 蜡烛**起始**时间，毫秒。 */
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

function merge<T extends Candle>(bucket: readonly T[], startMs: number): T {
  const first = bucket[0] as T;
  const last = bucket[bucket.length - 1] as T;
  let high = first.high;
  let low = first.low;
  let volume = 0;
  for (const bar of bucket) {
    if (bar.high > high) high = bar.high;
    if (bar.low < low) low = bar.low;
    volume += bar.volume;
  }
  return { ...first, timestamp: startMs, open: first.open, high, low, close: last.close, volume };
}

/**
 * `baseMinutes` 的 K 线 → `targetMinutes` 的 K 线（旧→新）。
 *
 * - `targetMinutes` 不是 `baseMinutes` 的整数倍时**抛错**（理由见文件头）。
 * - 输入顺序无所谓，内部会按时间升序排一次。
 * - **丢掉开头那个不完整的桶**：请求窗口只保证「最新 N 根」，起点不落在桶边界上时，
 *   第一桶缺了前几根 ⇒ 它的开盘价不是真开盘价（会画出一根假的长实体）。桶里根数够
 *   `factor` 根就是完整的（数据本身有空洞的情况除外，那种属于上游缺数，照原样保留）。
 * - **最后一桶一定保留**：那正是「正在走的这一根」，实时价格靠它。
 */
export function aggregateCandles<T extends Candle>(
  bars: readonly T[],
  baseMinutes: number,
  targetMinutes: number,
): T[] {
  if (!Number.isInteger(baseMinutes) || baseMinutes < 1) {
    throw new Error(`聚合基底周期不合法：${baseMinutes}`);
  }
  if (!Number.isInteger(targetMinutes) || targetMinutes < 1) {
    throw new Error(`聚合目标周期不合法：${targetMinutes}`);
  }
  if (targetMinutes % baseMinutes !== 0) {
    throw new Error(`不能把 ${baseMinutes}m 聚合成 ${targetMinutes}m（${targetMinutes} 不是 ${baseMinutes} 的整数倍）`);
  }
  if (targetMinutes === baseMinutes) return [...bars];
  if (bars.length === 0) return [];

  const factor = targetMinutes / baseMinutes;
  const bucketMs = targetMinutes * 60_000;
  const sorted = [...bars].sort((a, b) => a.timestamp - b.timestamp);

  const out: T[] = [];
  let firstBucketCount = 0;
  let key = Number.NaN;
  let bucket: T[] = [];
  const flush = () => {
    if (bucket.length === 0) return;
    if (out.length === 0) firstBucketCount = bucket.length;
    out.push(merge(bucket, key * bucketMs));
    bucket = [];
  };
  for (const bar of sorted) {
    const next = Math.floor(bar.timestamp / bucketMs);
    if (next !== key) {
      flush();
      key = next;
    }
    bucket.push(bar);
  }
  flush();

  if (out.length > 1 && firstBucketCount < factor) out.shift();
  return out;
}
