/**
 * 主图均线（MA）：**目录**（指标栏上有哪些档位）+ 用户的增删与排序 + 期数到线色的绑定。
 *
 * 与 `lib/intervals.ts` 同职责、同口径的纯函数集合：
 *   1. 内置目录 `DEFAULT_MA_CATALOG` —— 就是用户可编辑之前的那固定五档 `5/10/20/30/60`；
 *   2. 校验：期数必须是 `[MA_MIN, MA_MAX]` 内的正整数，目录最多 `MA_LIMIT` 条（色槽也是 8 个）；
 *   3. 增删与排序一律返回新数组，方便沿用 `useDragSort`（它只认 id 的顺序数组）。
 *
 * 配色**跟着期数走**（`MaColors`：期数 → 色槽 0~7），所以拖动排序只改 chip 的先后、
 * 不改线色；新增一条时归一会自动挑一个还没被占用的色槽。
 */

import { MA_CHOICES, MA_LIMIT, MA_MAX, MA_MIN, MA_SLOT_COUNT, type MaColors } from './chartSeries';

/** 出厂目录：就是用户可编辑之前的那固定五档（面板「恢复默认」也用它）。 */
export const DEFAULT_MA_CATALOG: number[] = [...MA_CHOICES];

/** 期数是否合法：`[MA_MIN, MA_MAX]` 内的正整数。 */
export function isMaPeriodLegal(period: number): boolean {
  return Number.isInteger(period) && period >= MA_MIN && period <= MA_MAX;
}

/** 界面短名：`7 → MA7`。 */
export function maLabel(period: number): string {
  return `MA${period}`;
}

export type MaInput = { ok: true; period: number } | { ok: false; reason: string };

/** 「一个期数」→ 解析结果。空 / 非正整数 / 越界都给出可读原因。 */
export function parseMaPeriod(raw: string): MaInput {
  const text = raw.trim();
  if (text === '') return { ok: false, reason: '先填期数，例：7' };
  if (!/^\d+$/.test(text)) return { ok: false, reason: '只认正整数，例：7、99、200' };
  const period = Number(text);
  if (!isMaPeriodLegal(period)) {
    return { ok: false, reason: `期数要在 ${MA_MIN} ~ ${MA_MAX} 之间` };
  }
  return { ok: true, period };
}

/** 追加到末尾；已存在 / 越界 / 已满 `MA_LIMIT` 条都原样返回。 */
export function addMaPeriod(list: number[], period: number): number[] {
  if (!isMaPeriodLegal(period) || list.includes(period) || list.length >= MA_LIMIT) return list;
  return [...list, period];
}

export function removeMaPeriod(list: number[], period: number): number[] {
  return list.includes(period) ? list.filter((x) => x !== period) : list;
}

/** 把第 `from` 项搬到第 `to` 位（拖拽排序用；越界则原样返回）。 */
export function moveMaPeriodTo(list: number[], from: number, to: number): number[] {
  if (from < 0 || from >= list.length) return list;
  const target = Math.max(0, Math.min(list.length - 1, to));
  if (from === target) return list;
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(target, 0, moved as number);
  return next;
}

/** 色槽是否合法（0 ~ `MA_SLOT_COUNT-1` 的整数）。 */
export function isMaColorSlot(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value < MA_SLOT_COUNT
  );
}

/**
 * 期数 → 色槽的归一化：目录内每条均线恰好一个合法槽位，且互不重复。
 *
 * 三条规则（也是旧存档的迁移路径）：
 *   1. 目录里已有且合法、未撞车的槽位原样保留 —— 增删别的均线不改已有线的颜色；
 *   2. 缺失/非法/撞车的按**期数升序**补第一个空槽 —— 老存档没有颜色时，
 *      5/10/20/30/60 依次拿到 0~4，正是旧版「按期数升序分配」的样子；
 *   3. 目录外的期数一律丢弃（删掉的均线不占着颜色）。
 */
export function normalizeMaColors(catalog: readonly number[], raw: unknown): MaColors {
  const periods = [...new Set(catalog.filter(isMaPeriodLegal))]
    .sort((a, b) => a - b)
    .slice(0, MA_LIMIT);
  const source = raw != null && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};
  const used = new Set<number>();
  const out: MaColors = {};

  for (const period of periods) {
    const slot = source[String(period)];
    if (isMaColorSlot(slot) && !used.has(slot)) {
      out[period] = slot;
      used.add(slot);
    }
  }
  let nextSlot = 0;
  for (const period of periods) {
    if (out[period] != null) continue;
    while (used.has(nextSlot)) nextSlot++;
    if (nextSlot >= MA_SLOT_COUNT) break; // 目录已被 MA_LIMIT 截断，正常走不到
    out[period] = nextSlot;
    used.add(nextSlot);
  }
  return out;
}

/**
 * 给一条均线换色：目标槽位被别的均线占着就**互换**（始终一色一线，不出现同色）。
 * 不在目录里的期数、非法槽位一律原样返回。
 */
export function assignMaColor(
  colors: MaColors,
  catalog: readonly number[],
  period: number,
  slot: number,
): MaColors {
  if (!catalog.includes(period) || !isMaColorSlot(slot)) return colors;
  const current = colors[period];
  const owner = catalog.find((p) => p !== period && colors[p] === slot);
  const next: MaColors = { ...colors };
  next[period] = slot;
  if (owner != null) {
    if (current != null) next[owner] = current;
    else delete next[owner];
  }
  return normalizeMaColors(catalog, next);
}