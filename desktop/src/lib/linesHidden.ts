/**
 * 画线显隐：按「市场:标的」记一个布尔，藏的是**整张图的全部画线**（水平线 + 两点直线）。
 * 只藏不删——落盘里的线原样保留，告警判定（usePriceAlerts 直读 localStorage）也照常跑；
 * 眼睛开关只管「图上看不看得到」。与锁定（linesLock）同一套键形与「只存 true」的规矩：
 * 隐藏是反默认状态才记键，默认（显示）= 键不存在。
 *
 * 零依赖是硬要求（与 linesLock 同：存储解析不放 hook 里，纯函数可直接单跑）。
 */

export const LINES_HIDDEN_KEY = 'mm.linesHidden';

export type LinesHiddenStore = Record<string, true>;

/** 读一份显隐表：坏数据回空；非 `true` 的值一律当「没藏」。 */
export function parseLinesHidden(raw: string | null): LinesHiddenStore {
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
  const out: LinesHiddenStore = {};
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (value === true) out[key] = true;
  }
  return out;
}

/** 改一个标的的显隐；恢复显示直接删键。没变就原样返回，省一次重渲染。 */
export function withLinesHidden(
  store: LinesHiddenStore,
  key: string,
  hidden: boolean,
): LinesHiddenStore {
  if (!hidden) {
    if (!(key in store)) return store;
    const next = { ...store };
    delete next[key];
    return next;
  }
  return store[key] ? store : { ...store, [key]: true };
}
