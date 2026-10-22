/**
 * 主图均线（MA / EMA 两族）：**目录**（每族各有哪些档位）+ 用户的增删与排序 + 期数到线色的绑定。
 *
 * 两族结构完全对称，所以这一层的函数一律**与族无关**（只吃一个 `number[]` 目录与一份配色表），
 * 由调用方带上 `MaKind` 去取该族的出厂值、色板与标签：
 *   1. 出厂目录 `DEFAULT_CATALOGS` —— MA 是老的五档 `5/10/20/30/60`，EMA 是 `12/26/50`；
 *   2. 校验：期数必须是 `[MA_MIN, MA_MAX]` 内的正整数，**每族**最多 `MA_LIMIT` 条
 *      （每族各有 8 个色槽，两族互不占位）；
 *   3. 增删与排序一律返回新数组，方便沿用 `useDragSort`（它只认 id 的顺序数组）。
 *
 * 配色**跟着期数走**（`MaColors`：期数 → 该族色槽 0~7），所以拖动排序只改 chip 的先后、
 * 不改线色；新增一条时归一会自动挑一个还没被占用的色槽。
 */

import {
  DEFAULT_CATALOGS,
  MA_LIMIT,
  MA_MAX,
  MA_MIN,
  MA_SLOT_COUNT,
  type MaColors,
  type MaKind,
} from './chartSeries';

/** 某族的出厂目录（拷贝一份，别让调用方改到常量本身）。 */
export function defaultCatalog(kind: MaKind): number[] {
  return [...DEFAULT_CATALOGS[kind]];
}

/**
 * 一族均线的配置：目录（有序）+ 期数 → 色槽 + 整族显隐 + 逐条小眼睛。
 *
 * 放在这里而不是 `indicatorPrefs`：面板、绘图与持久化都要用它，
 * 而「一族均线由哪几样东西描述」是均线自身的事。
 *
 * 显隐分**两级**，互不覆盖：
 *   1. 整族（`hidden`）—— 指标栏那枚 chip，管「这一族参不参与绘图」；
 *   2. 逐条（`muted`）—— 面板里每条行尾的小眼睛，管「族开着的时候这一条画不画」。
 * 关掉族再打开，逐条的状态原样保留（总闸不动分闸），不需要重新点一遍。
 */
export interface MaFamilyPrefs {
  /** 这一族配置了哪几档（顺序即面板里的顺序；空 = 一档都没配）。 */
  catalog: number[];
  /** 期数 → 该族色槽（0~7，见 `chartSeries.MA_ROLES` / `EMA_ROLES`）。 */
  colors: MaColors;
  /** 整族显隐：true = 这一族一条都不画（**只藏不删**，目录与配色原样保留）。 */
  hidden: boolean;
  /**
   * 逐条隐藏：眼睛闭着的那几档（**只藏不删**，必须 ⊆ `catalog`）。
   * 与 `hidden` 正交 —— 族开着，这几条不画；族关着，这个状态也留着重开时用。
   */
  muted: number[];
}

/** 两族的配置（族 → 配置）。 */
export type MaFamilies = Record<MaKind, MaFamilyPrefs>;

/** 期数是否合法：`[MA_MIN, MA_MAX]` 内的正整数。 */
export function isMaPeriodLegal(period: number): boolean {
  return Number.isInteger(period) && period >= MA_MIN && period <= MA_MAX;
}

/** 界面短名：`MA, 7 → MA7` / `EMA, 12 → EMA12`。 */
export function lineLabel(kind: MaKind, period: number): string {
  return `${kind}${period}`;
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

/** 追加到末尾；已存在 / 越界 / 该族已满 `MA_LIMIT` 条都原样返回。 */
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

/**
 * 「逐条隐藏」的归一：只留**目录里真有的**期数、去重、按目录顺序。
 *
 * 目录里删掉的均线必须一并从 `muted` 消失（否则删了又加回来会莫名其妙地不显示 ——
 * 用户以为「加了一条新线」，图上却没有）。老存档没有这个字段 ⇒ 空数组 = 全都睁眼。
 */
export function normalizeMuted(catalog: readonly number[], raw: unknown): number[] {
  const wanted = new Set<number>();
  if (Array.isArray(raw)) {
    for (const v of raw) {
      if (typeof v === 'number' && Number.isInteger(v)) wanted.add(v);
    }
  }
  return catalog.filter((p) => wanted.has(p));
}

/** 这一族此刻**图上真会画**的期数：目录 − 眼睛闭上的（整族的 `hidden` 由调用方折算成空表）。 */
export function drawnPeriods(family: MaFamilyPrefs): number[] {
  if (family.muted.length === 0) return family.catalog;
  const muted = new Set(family.muted);
  return family.catalog.filter((p) => !muted.has(p));
}

/** 翻一条均线的眼睛。不在目录里的期数原样返回（面板只可能点到目录里的行）。 */
export function toggleMuted(family: MaFamilyPrefs, period: number): number[] {
  if (!family.catalog.includes(period)) return family.muted;
  const next = new Set(family.muted);
  if (next.has(period)) next.delete(period);
  else next.add(period);
  return normalizeMuted(family.catalog, [...next]);
}

/** 全睁（`hide = false`）/ 全闭（`hide = true`）：面板标题行那个「全部显示 / 全部隐藏」。 */
export function setAllMuted(catalog: readonly number[], hide: boolean): number[] {
  return hide ? [...catalog] : [];
}
