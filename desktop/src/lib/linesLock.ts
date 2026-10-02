/**
 * 画线锁定：按「市场:标的」记一个布尔，锁的是**整张图的全部画线**（水平线 + 两点直线），
 * 不是逐条锁 —— 右键菜单里只有一条「锁定全部画线 / 解锁全部画线」。
 *
 * 解锁（默认）= 画布可以直接拖已有的线（水平线上下拖、直线整体拖 / 端点转角度）；
 * 锁定 = 画布不抓任何线、也不画端点把手，拖动落点一律还给「拖画布」。
 * 右上角菜单是唯一开关，其余增删仍走右键菜单。
 *
 * 只存 `true` 的键（解锁 = 键不存在）：「没存过」与「存了未锁」是同一种状态，
 * 默认解锁落到 parse 的第一行。零依赖是硬要求（`.mts` 单测靠 Node 直接跑）。
 */

export const LINES_LOCK_KEY = 'mm.linesLock';

export type LinesLockStore = Record<string, true>;

/** 读一份锁表：坏数据回空；非 `true` 的值一律当「没锁」。 */
export function parseLinesLock(raw: string | null): LinesLockStore {
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
  const out: LinesLockStore = {};
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (value === true) out[key] = true;
  }
  return out;
}

/** 改一条标的的锁；解锁直接删键（见文件头）。没变就原样返回，省一次重渲染。 */
export function withLinesLock(
  store: LinesLockStore,
  key: string,
  locked: boolean,
): LinesLockStore {
  if (!locked) {
    if (!(key in store)) return store;
    const next = { ...store };
    delete next[key];
    return next;
  }
  return store[key] ? store : { ...store, [key]: true };
}
