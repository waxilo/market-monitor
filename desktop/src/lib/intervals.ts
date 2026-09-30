/**
 * K 线周期：**目录**（内置有哪些）+ 用户的**选择、顺序与自定义周期**。
 *
 * 三件事收敛在这里：
 *   1. 内置目录 `INTERVAL_CATALOG`（15 个，各数据源原生能力的并集）；
 *   2. **自定义周期**：面板上是「数值 + 单位（分/时/天/月/年）」两件套，
 *      合成出来的 id 一律**规范化**成 `45m` / `90m` / `3d` / `1y` 这种形式 ——
 *      所以「7 天」会与内置的 `1w` 自动并成同一个（同一段时间只该有一个按钮）；
 *   3. 分钟数映射 `minutesOf` —— 内置与自定义走同一条路，调用方不必区分二者。
 *
 * 存储的只是「id 的有序数组」。读的时候用 `minutesOf` 做校验，认不出的一律丢掉，
 * 所以老版本存下的值、或者手改坏的 localStorage 都不会把界面弄崩。
 *
 * ⚠️ `1m`（1 分钟）与 `1M`（1 个月）**只差一个字母的大小写**，任何比较都不许做大小写归一化。
 */

export interface IntervalOption {
  /** 界面标识，同时也是存储值、`fetchKlines` 的入参、localStorage 里的值。 */
  id: string;
  minutes: number;
  /** 一行说明（面板里给用户看「这是多长一根」）。 */
  note: string;
}

/** 自定义周期的合法区间（分钟）。上限与最粗的自定义单位「年」对齐，再粗的蜡烛没有意义。 */
export const MIN_INTERVAL_MINUTES = 1;

/** 我们口径下的「月」= 30 天 —— 与各家最粗的原生码 `30d` 一致（不是日历月）。 */
export const MINUTES_PER_MONTH = 43_200;

/** 「年」= 12 个月 = 360 天。取 12×月 而不是 365 天，是为了让「12 月」与「1 年」是同一个周期。 */
export const MINUTES_PER_YEAR = MINUTES_PER_MONTH * 12;

export const MAX_INTERVAL_MINUTES = MINUTES_PER_YEAR;

/**
 * 内置周期目录，按自然粒度升序 —— 面板里的「未选」列表就按这个顺序列。
 *
 * 这是**各数据源原生能力的并集**（逐家实测/对过 ladder）。某个周期在当前数据源上
 * 没有原生码时，不再置灰：改由更细的原生周期**聚合**出来（见 `lib/aggregate.ts`），
 * 界面上标一个「聚」字以示区别。
 */
export const INTERVAL_CATALOG: IntervalOption[] = [
  { id: '1m', minutes: 1, note: '1 分钟' },
  { id: '3m', minutes: 3, note: '3 分钟' },
  { id: '5m', minutes: 5, note: '5 分钟' },
  { id: '15m', minutes: 15, note: '15 分钟' },
  { id: '30m', minutes: 30, note: '30 分钟' },
  { id: '1h', minutes: 60, note: '1 小时' },
  { id: '2h', minutes: 120, note: '2 小时' },
  { id: '4h', minutes: 240, note: '4 小时' },
  { id: '6h', minutes: 360, note: '6 小时' },
  { id: '8h', minutes: 480, note: '8 小时' },
  { id: '12h', minutes: 720, note: '12 小时' },
  { id: '1d', minutes: 1_440, note: '1 天' },
  { id: '3d', minutes: 4_320, note: '3 天' },
  { id: '1w', minutes: 10_080, note: '1 周' },
  { id: '1M', minutes: MINUTES_PER_MONTH, note: '1 月' },
];

/** 单位 → 分钟数。`M` 是月、`m` 是分，其余大小写不敏感。 */
const UNIT_MINUTES: Record<string, number> = {
  m: 1,
  min: 1,
  mins: 1,
  minute: 1,
  minutes: 1,
  分: 1,
  分钟: 1,
  h: 60,
  hr: 60,
  hrs: 60,
  hour: 60,
  hours: 60,
  时: 60,
  小时: 60,
  d: 1_440,
  day: 1_440,
  days: 1_440,
  天: 1_440,
  日: 1_440,
  w: 10_080,
  week: 10_080,
  weeks: 10_080,
  周: 10_080,
  M: 43_200,
  mo: 43_200,
  mon: 43_200,
  month: 43_200,
  months: 43_200,
  月: 43_200,
  y: MINUTES_PER_YEAR,
  yr: MINUTES_PER_YEAR,
  year: MINUTES_PER_YEAR,
  years: MINUTES_PER_YEAR,
  年: MINUTES_PER_YEAR,
};

/** 从粗到细的换算档，`intervalIdFromMinutes` 与 `minutesLabel` 共用（保证两者永远一致）。 */
const LADDER_UNITS: { minutes: number; suffix: string; noun: string }[] = [
  { minutes: MINUTES_PER_YEAR, suffix: 'y', noun: '年' },
  { minutes: MINUTES_PER_MONTH, suffix: 'M', noun: '个月' },
  { minutes: 10_080, suffix: 'w', noun: '周' },
  { minutes: 1_440, suffix: 'd', noun: '天' },
  { minutes: 60, suffix: 'h', noun: '小时' },
  { minutes: 1, suffix: 'm', noun: '分钟' },
];

/** 出厂默认：桌面端一直用的这 8 个、这个顺序。 */
export const DEFAULT_INTERVALS: string[] = ['1m', '5m', '15m', '30m', '1h', '4h', '1d', '1w'];

/** 周期没被显式指定时的兜底（换源导致当前周期不可用时也回落到它）。 */
export const FALLBACK_INTERVAL = '15m';

export const INTERVALS_KEY = 'mm.intervals';

export function isIntervalIdLegal(minutes: number): boolean {
  return Number.isInteger(minutes) && minutes >= MIN_INTERVAL_MINUTES && minutes <= MAX_INTERVAL_MINUTES;
}

/** 分钟数 → 规范 id：`90 → 90m`、`10080 → 1w`、`43200 → 1M`。越界返回 null。 */
export function intervalIdFromMinutes(minutes: number): string | null {
  if (!isIntervalIdLegal(minutes)) return null;
  for (const unit of LADDER_UNITS) {
    if (minutes % unit.minutes === 0) return `${minutes / unit.minutes}${unit.suffix}`;
  }
  return `${minutes}m`;
}

/** 从粗到细拆开一段分钟数：`90 → ['1 小时', '30 分钟']`。 */
function labelParts(minutes: number): string[] {
  const parts: string[] = [];
  let rest = minutes;
  for (const unit of LADDER_UNITS) {
    const n = Math.floor(rest / unit.minutes);
    if (n > 0) {
      parts.push(`${n} ${unit.noun}`);
      rest -= n * unit.minutes;
    }
  }
  return parts;
}

/** 分钟数的人类读数（与 id 的换算档同源）：`90 → 1 小时 30 分钟`。 */
export function minutesLabel(minutes: number): string {
  return labelParts(minutes).join(' ') || '0 分钟';
}

/** 界面短名的单位后缀：只换单位，数字原样。 */
const SHORT_UNIT: Record<string, string> = {
  m: '分',
  h: '时',
  d: '天',
  w: '周',
  M: '月',
  y: '年',
};

/**
 * 周期 id → 界面上的**短名**：`15m → 15分`、`1h → 1时`、`1w → 1周`、`1M → 1月`。
 *
 * 给周期条 chip、面板行这些窄地方用；要完整读数（`90 → 1 小时 30 分钟`）走 `minutesLabel`。
 * 认不出的形状原样返回，且**不许做大小写归一化**（`1m` 是分、`1M` 是月，只差一个字母）。
 */
export function intervalLabel(id: string): string {
  const m = /^(\d+)(y|M|w|d|h|m)$/.exec(id);
  return m ? `${m[1]}${SHORT_UNIT[m[2] as string]}` : id;
}

/**
 * 行尾/提示里的**完整读数**；短名已经说清同一件事时返回空串
 * （`15分` 旁边再写「15 分钟」是纯占地方，`90分` 才值得写「1 小时 30 分钟」）。
 */
export function intervalNote(id: string): string {
  const minutes = minutesOf(id);
  if (minutes == null) return '';
  const parts = labelParts(minutes);
  return parts.length > 1 ? parts.join(' ') : '';
}

/** id → 分钟数。内置与自定义同一条路；认不出返回 null。 */
export function minutesOf(id: string): number | null {
  const builtin = INTERVAL_CATALOG.find((o) => o.id === id);
  if (builtin) return builtin.minutes;
  const m = /^(\d+)(y|M|w|d|h|m)$/.exec(id);
  if (!m) return null;
  const minutes = Number(m[1]) * (UNIT_MINUTES[m[2] as string] ?? 0);
  return isIntervalIdLegal(minutes) ? minutes : null;
}

/** 这个 id 用得了吗（内置或语法合法的自定义周期）。 */
export function isKnownInterval(id: string): boolean {
  return minutesOf(id) !== null;
}

/** 不在内置目录里的周期 = 用户自定义的。 */
export function isCustomInterval(id: string): boolean {
  return !INTERVAL_CATALOG.some((o) => o.id === id) && isKnownInterval(id);
}

export interface IntervalInfo {
  id: string;
  minutes: number;
  note: string;
  custom: boolean;
}

/** 统一的「周期说明」：内置取目录里的 note，自定义现算。 */
export function intervalInfo(id: string): IntervalInfo | null {
  const builtin = INTERVAL_CATALOG.find((o) => o.id === id);
  if (builtin) return { id, minutes: builtin.minutes, note: builtin.note, custom: false };
  const minutes = minutesOf(id);
  return minutes == null ? null : { id, minutes, note: minutesLabel(minutes), custom: true };
}

/** 目录里分钟数落在给定集合中的周期（保目录顺序）—— 用来把各家的 ladder 翻译成界面 id。 */
export function intervalsInMinutes(minutes: Iterable<number>): string[] {
  const set = new Set(minutes);
  return INTERVAL_CATALOG.filter((o) => set.has(o.minutes)).map((o) => o.id);
}

/**
 * 读存储值：只认得出分钟数的 id，顺带去重。
 * 空/坏值一律回落到出厂默认（首次进来就是这个列表）。
 */
export function parseIntervals(raw: string | null): string[] {
  if (!raw) return [...DEFAULT_INTERVALS];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [...DEFAULT_INTERVALS];
  }
  if (!Array.isArray(parsed)) return [...DEFAULT_INTERVALS];
  const list: string[] = [];
  for (const value of parsed) {
    if (typeof value !== 'string' || !isKnownInterval(value) || list.includes(value)) continue;
    list.push(value);
  }
  return list.length > 0 ? list : [...DEFAULT_INTERVALS];
}

export function serializeIntervals(list: string[]): string {
  return JSON.stringify(list);
}

/** 追加到末尾（已存在则原样返回）。 */
export function addInterval(list: string[], id: string): string[] {
  if (!isKnownInterval(id) || list.includes(id)) return list;
  return [...list, id];
}

export function removeInterval(list: string[], id: string): string[] {
  return list.includes(id) ? list.filter((x) => x !== id) : list;
}

/** 上移/下移 `delta` 位（越界则原样返回）。 */
export function moveInterval(list: string[], id: string, delta: number): string[] {
  return moveIntervalTo(list, list.indexOf(id), list.indexOf(id) + delta);
}

/** 把第 `from` 项搬到第 `to` 位（拖拽排序用；越界则原样返回）。 */
export function moveIntervalTo(list: string[], from: number, to: number): string[] {
  if (from < 0 || from >= list.length) return list;
  const target = Math.max(0, Math.min(list.length - 1, to));
  if (from === target) return list;
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(target, 0, moved as string);
  return next;
}

// ——————————————————————— 自定义周期：数值 + 单位 ———————————————————————

export type IntervalInput =
  | { ok: true; id: string; minutes: number; custom: boolean }
  | { ok: false; reason: string };

/**
 * 自定义周期可选的**单位** —— 面板上那排分段按钮就是它。
 *
 * 刻意**不含周**：周已经是内置目录里的 `1w`，想要就在「未选」里点一下；
 * 而这五个单位覆盖了「一根蜡烛比一天长」的全部诉求（周 = 7 天照样能写出来）。
 */
export interface IntervalUnit {
  /** 规范后缀。`m` 是分钟、`M` 是月 —— 大小写有别。 */
  suffix: 'm' | 'h' | 'd' | 'M' | 'y';
  /** 面板上的单字标签。 */
  label: string;
  minutes: number;
  /** 悬停说明：这个单位到底多长（月/年的口径必须写清楚，不然「1 月」是 28 天还是 30 天要吵）。 */
  hint: string;
}

export const INTERVAL_UNITS: IntervalUnit[] = [
  { suffix: 'm', label: '分', minutes: 1, hint: '分钟' },
  { suffix: 'h', label: '时', minutes: 60, hint: '1 时 = 60 分钟' },
  { suffix: 'd', label: '天', minutes: 1_440, hint: '1 天 = 24 小时' },
  { suffix: 'M', label: '月', minutes: MINUTES_PER_MONTH, hint: '1 月 = 30 天（与各家的 30d 口径一致）' },
  { suffix: 'y', label: '年', minutes: MINUTES_PER_YEAR, hint: '1 年 = 12 月 = 360 天' },
];

/** 按规范后缀取单位；面板状态恢复、测试都走它。 */
export function intervalUnit(suffix: string): IntervalUnit | null {
  return INTERVAL_UNITS.find((u) => u.suffix === suffix) ?? null;
}

/**
 * 「数值 + 单位」→ 周期 id。
 *
 * 数值接受小数，但只在小数位能落成**整分钟**时接受（`1.5 时 → 90m` 可以，`1.5 分` 会被拒）。
 * 结果一律走 `intervalIdFromMinutes` 规范化，于是「12 月」与「1 年」必然合成同一个 `1y`、
 * 「7 天」与内置 `1w` 合成同一个 —— 用户怎么填都不会在周期条上出现两个等长的按钮。
 */
export function composeInterval(raw: string, suffix: string): IntervalInput {
  const text = raw.trim();
  if (text === '') return { ok: false, reason: '先填数字，例：45' };
  if (!/^\d+(?:\.\d+)?$/.test(text)) return { ok: false, reason: '只认数字，例：45、1.5' };
  const unit = intervalUnit(suffix);
  if (unit == null) return { ok: false, reason: `认不出单位「${suffix}」` };
  const minutes = Number(text) * unit.minutes;
  if (!Number.isInteger(minutes)) {
    return { ok: false, reason: `${text} ${unit.label} 落不到整分钟，换个数字试试` };
  }
  if (!isIntervalIdLegal(minutes)) {
    return { ok: false, reason: `周期要在 1 分钟 ~ ${minutesLabel(MAX_INTERVAL_MINUTES)} 之间` };
  }
  const id = intervalIdFromMinutes(minutes);
  if (id == null) return { ok: false, reason: '这个周期不合法' };
  return { ok: true, id, minutes, custom: isCustomInterval(id) };
}

/** 列表里第一个认得出的周期；一个都没有时回落 `FALLBACK_INTERVAL`。 */
export function firstSupported(list: string[]): string {
  return list.find((id) => isKnownInterval(id)) ?? FALLBACK_INTERVAL;
}

/**
 * 从一组**原生**周期分钟数里挑出「能整除 `minutes` 的最粗的那一个」，作为聚合的基底。
 *
 * 为什么挑最粗的：因子 = `minutes / base` 越小，要请求的细粒度根数越少
 * （`8h` 用 4h 打底要 2 倍根数，用 1m 打底要 480 倍，直接顶穿各家的 limit 上限）。
 *
 * 返回 `minutes` 本身 = 该源原生就有，直接取、不聚合。
 * 返回 null = 没有任何原生周期能整除它 —— 正常不会发生（各家都有 1m，而 1 整除一切）。
 */
export function coarsestBaseFor(natives: Iterable<number>, minutes: number): number | null {
  let best: number | null = null;
  for (const n of natives) {
    if (!Number.isFinite(n) || n < 1 || minutes % n !== 0) continue;
    if (best == null || n > best) best = n;
  }
  return best;
}

export interface IntervalSynthesis {
  /** 聚合基底（`15m`）；`null` = 拼不出来（正常不发生，各家都有 1m）。 */
  baseId: string | null;
  factor: number;
  /** 一行短标签，面板与周期条上直接用。 */
  tag: string;
  /** 完整说明，进 `title` 或反馈行。 */
  title: string;
}

/**
 * 这个周期在当前数据源上是不是**拼出来的**：
 * 原生直取 → `null`；要靠聚合 → 给出基底与因子。
 *
 * 面板与周期条都要用同一份判断（两处各写一遍必然分叉），所以放在这里。
 */
export function synthesisOf(id: string, natives: ReadonlySet<number>): IntervalSynthesis | null {
  const info = intervalInfo(id);
  if (!info || natives.has(info.minutes)) return null;
  const base = coarsestBaseFor(natives, info.minutes);
  if (base == null) {
    return {
      baseId: null,
      factor: 0,
      tag: '不可用',
      title: `${intervalLabel(id)} 在当前数据源上既没有原生周期，也拼不出来`,
    };
  }
  const factor = info.minutes / base;
  const baseId = intervalIdFromMinutes(base) ?? `${base}m`;
  return {
    baseId,
    factor,
    tag: `聚合 ${intervalLabel(baseId)} × ${factor}`,
    title: `当前数据源没有 ${intervalLabel(id)}，用 ${intervalLabel(baseId)} 每 ${factor} 根合成 1 根`,
  };
}
