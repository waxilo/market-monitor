/**
 * 主图均线（MA）：**目录**（指标栏上有哪些档位）+ 用户的增删与排序。
 *
 * 与 `lib/intervals.ts` 同职责、同口径的纯函数集合：
 *   1. 内置目录 `DEFAULT_MA_CATALOG` —— 就是用户可编辑之前的那固定五档 `5/10/20/30/60`；
 *   2. 校验：期数必须是 `[MA_MIN, MA_MAX]` 内的正整数，目录最多 `MA_LIMIT` 条（色板只到 8 位）；
 *   3. 增删与排序一律返回新数组，方便沿用 `useDragSort`（它只认 id 的顺序数组）。
 *
 * ⚠️ 配色按**升序序号**分配（见 `chartSeries.maRoleAt`），所以面板里的拖动排序
 * 只改 chip 的先后、不改线色 —— 线色只认期数升序，这一点要写进面板提示。
 */

import { MA_CHOICES, MA_LIMIT, MA_MAX, MA_MIN } from './chartSeries';

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