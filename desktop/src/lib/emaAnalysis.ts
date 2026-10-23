/**
 * EMA 指标分析：「所有已配置的 EMA 档位 × 所有已配置的 K 线周期」的当前 EMA 值
 * 汇总成一张对照最新价的表，按上下分成**上方压力 / 下方支撑**。
 *
 * 两条口径值得写死在这里：
 *
 *  1. **取数根数**与图上的每段历史（`ChartView.HISTORY_BARS = 300`）对齐 ——
 *     同一档 EMA 在表里的值必须与图上那条线右端的值一致，否则用户会看到
 *     「表里 68,900、图上 68,850」这种没法解释的差。所以下限就是 300；
 *     只有用户配了比 300 更长的档位（如 EMA200 之上）才往上加，上限 1000（各家单次取数的硬顶）。
 *
 *  2. **算不出来的一律不进表**（取数不足 / 返回值坏），由调用方另外报「哪几档缺数据」。
 *     把 NaN 混进排序里会让「最近的一道压力」整个失真 —— 排序里 NaN 的比较永远为假，
 *     它会随机停在任意位置。
 */

import type { Bar } from './api';
import type { PriceLine } from './priceLines';
import { ema } from './indicators';

/** 一条 EMA 水位（还没跟现价比较）。 */
export interface EmaLevel {
  /** K 线周期 id（`15m` / `4h` / `1M` …）。 */
  interval: string;
  /** 该周期的分钟数 —— 只用来在「同价」时定序（细周期在前）。 */
  minutes: number;
  /** EMA 期数。 */
  period: number;
  /** 最后一根 K 线上的 EMA 值。 */
  value: number;
}

/** 一条水位 + 与现价的偏离（正 = 在现价上方）。 */
export interface EmaLevelRow extends EmaLevel {
  gapPercent: number;
}

export interface EmaBuckets {
  /** 上方压力：EMA 值 > 现价，**离现价最近的在前**（升序）。 */
  resistance: EmaLevelRow[];
  /** 下方支撑：EMA 值 < 现价，**离现价最近的在前**（降序）。 */
  support: EmaLevelRow[];
}

/** 某个周期整段没取回来（网络 / 数据源不通，值得重试）。 */
export interface EmaFailure {
  interval: string;
  reason: string;
}

/** 某一档算不出来：取回的根数不够（EMA 至少要 period 根）。 */
export interface EmaMissing {
  interval: string;
  period: number;
  bars: number;
}

/**
 * 一次扫描的完整结果 —— 面板那几块状态就是它（表 / 两类异常 / 兜底价）。
 *
 * 打包成一个对象而不是四个 state：静默刷新要求「旧值一直可用，新值一次换到位」，
 * 四个 state 分开 set 会渲染出「新表配旧异常」这种中间态。
 */
export interface EmaScan {
  levels: EmaLevel[];
  failures: EmaFailure[];
  missing: EmaMissing[];
  /** 取数失败但表里**沿用了上一轮数值**的周期（提示语得说清这些不是最新的）。 */
  stale: string[];
  /** 行情没到时的兜底基准：最细周期最后一根的收盘价（取不到给 NaN）。 */
  fallbackPrice: number;
}

/** 空扫描：还没取过数的初始值（`levels` 为空 + `fallbackPrice` 为 NaN）。 */
export const EMPTY_EMA_SCAN: EmaScan = {
  levels: [],
  failures: [],
  missing: [],
  stale: [],
  fallbackPrice: NaN,
};

/**
 * 把这一轮的结果并到上一轮上 —— **静默刷新**的核心：宁可留着旧值，也不把表清空。
 *
 * 两条合并规则，各自对应一种「这次没拿到」：
 *
 *  1. 整段取数失败（`failures`）：该周期上一轮算出来的各档**原样留下**，并把周期记进
 *     `stale`，提示语据此说明「这几列是上次的值」。清掉它们等于让一道压力线在断网时
 *     凭空消失，比显示略旧的值危险得多。
 *  2. 根数不够（`missing`）而上一轮算得出：同样沿用旧值，并从 `missing` 里划掉 ——
 *     这种情况多半是这一轮恰好少回了几根（换源、翻页边界），不代表这一档真的没了。
 *
 * 只认当前配置范围内的周期：配置里删掉的周期/档位不会从上一轮被捡回来（否则会出现
 * 「已经删了 EMA50，表里还挂着一个 EMA50」）。兜底价拿不到时同样沿用上一轮的。
 */
export function mergeEmaScan(prev: EmaScan | null, next: EmaScan): EmaScan {
  if (!prev) return next;
  const failed = new Set(next.failures.map((failure) => failure.interval));
  const missingKey = new Set(next.missing.map((item) => `${item.interval}|${item.period}`));
  const prevKey = new Set(prev.levels.map((level) => `${level.interval}|${level.period}`));

  const kept: EmaLevel[] = [];
  const stale: string[] = [];
  for (const level of prev.levels) {
    if (failed.has(level.interval)) {
      kept.push(level);
      if (!stale.includes(level.interval)) stale.push(level.interval);
      continue;
    }
    if (missingKey.has(`${level.interval}|${level.period}`)) kept.push(level);
  }

  const fallbackPrice =
    Number.isFinite(next.fallbackPrice) && next.fallbackPrice > 0
      ? next.fallbackPrice
      : prev.fallbackPrice;

  return {
    levels: [...next.levels, ...kept],
    failures: next.failures,
    missing: next.missing.filter((item) => !prevKey.has(`${item.interval}|${item.period}`)),
    stale,
    fallbackPrice,
  };
}

/** 取数根数下限：与图上一段历史同口径（见文件头注）。 */
export const EMA_WINDOW_MIN = 300;
/** 取数根数上限：各家单次 K 线请求的硬顶（超过会被方言层夹掉，不如自己先夹）。 */
export const EMA_WINDOW_MAX = 1000;

/**
 * 一次取多少根：至少 `EMA_WINDOW_MIN`，最长的那一档再留 20 根余量。
 * 300 根时 EMA50 已经跑到 6 倍长度，收敛得很干净；EMA200 这类长档才会往上加。
 */
export function emaWindow(periods: readonly number[]): number {
  let longest = 0;
  for (const period of periods) {
    if (Number.isFinite(period) && period > longest) longest = period;
  }
  return Math.min(EMA_WINDOW_MAX, Math.max(EMA_WINDOW_MIN, Math.floor(longest) + 20));
}

/** 一段收盘价在最后一根上的 EMA 值；根数不足 `period`（或参数非法）给 NaN。 */
export function lastEma(closes: readonly number[], period: number): number {
  if (closes.length === 0) return NaN;
  const values = ema([...closes], period);
  return values[values.length - 1] ?? NaN;
}

/** 一批 K 线 → 指定各档的末值（顺序与 `periods` 一致，算不出的给 NaN）。 */
export function lastEmaOf(bars: readonly Bar[], periods: readonly number[]): number[] {
  const closes = bars.map((bar) => bar.close);
  return periods.map((period) => lastEma(closes, period));
}

/**
 * 与现价比较并分档。
 *
 * - 恰好等于现价的水位两边都不进（它既不是压力也不是支撑，算进任何一边都会让「最近一道」
 *   多出一条零距离的假信号）；
 * - 同价的水位按「周期更细、档位更短」在前，列表顺序因此是确定的，不随取数完成先后跳动；
 * - `price` 非正/非法时整表为空 —— 宁可空着让调用方提示「还没拿到最新价」，也不拿 0 当基准。
 */
export function splitLevels(levels: readonly EmaLevel[], price: number): EmaBuckets {
  const buckets: EmaBuckets = { resistance: [], support: [] };
  if (!Number.isFinite(price) || price <= 0) return buckets;

  const rows: EmaLevelRow[] = [];
  for (const level of levels) {
    if (!Number.isFinite(level.value) || level.value <= 0) continue;
    rows.push({ ...level, gapPercent: ((level.value - price) / price) * 100 });
  }

  const tieBreak = (a: EmaLevelRow, b: EmaLevelRow) =>
    a.minutes - b.minutes || a.period - b.period || a.interval.localeCompare(b.interval);

  buckets.resistance = rows
    .filter((row) => row.value > price)
    .sort((a, b) => a.value - b.value || tieBreak(a, b));
  buckets.support = rows
    .filter((row) => row.value < price)
    .sort((a, b) => b.value - a.value || tieBreak(a, b));
  return buckets;
}

/** 偏离读数的正负号来源：正 = 现价上方（压力），负 = 下方（支撑）。 */
export function sideOf(gapPercent: number): 'resistance' | 'support' | 'flat' {
  if (!Number.isFinite(gapPercent) || gapPercent === 0) return 'flat';
  return gapPercent > 0 ? 'resistance' : 'support';
}

/** 点一条水位时对画线表该做什么（见 `emaAlertAction`）。 */
export type EmaAlertAction =
  /** 这个价还没有线：新增一条告警线。 */
  | { kind: 'add' }
  /** 已经有一条普通水平线：只把它升级成告警线（不再新增，否则图上会重合两根）。 */
  | { kind: 'promote'; index: number }
  /** 已经是一条告警线：删掉它（同一个条目再点一下即取消）。 */
  | { kind: 'remove'; index: number };

/**
 * 判断「在 EMA 分析面板里点这一档」对当前画线表意味着什么。
 *
 * 为什么按**精确价**找线（`===` 而不是带容差）：线是「这个 EMA 值」的化身，
 * 面板里再点一次是同一个快照里的同一个数，必然精确相等；用容差反而会把
 * 「用户刚拖到 81,636.00 的那条线」误认成 81,636.80 的 EMA —— 拖过的线已经
 * 不属于这一档了。这也与 `addPriceLine` 的去重口径一致（同价不重复加）。
 */
export function emaAlertAction(lines: readonly PriceLine[], price: number): EmaAlertAction {
  if (!Number.isFinite(price)) return { kind: 'add' };
  const index = lines.findIndex((line) => line.price === price);
  if (index < 0) return { kind: 'add' };
  return lines[index].alert ? { kind: 'remove', index } : { kind: 'promote', index };
}

/** 这个价位上是否已经有一条**告警线**（面板行的红条与文案都看它）。 */
export function hasEmaAlert(lines: readonly PriceLine[], price: number): boolean {
  return lines.some((line) => line.price === price && line.alert);
}

/**
 * 从面板画一条线时自动写上的备注，如 `1时EMA50`、`30分EMA21`、`15天EMA200`。
 *
 * 周期那一半**用面板行里那枚小方框的字**（`intervalLabel` 的短名，由调用方传入）：
 * 用户是照着面板点的，图上写同样的字才对得上号；改成「1 小时」这类全称就得在两处
 * 维护同一套单位表，早晚会有一处写漏。
 *
 * 备注只有几十像素宽，但信息密度很高：一条孤零零的红线过两天就没人记得它为什么在那儿。
 */
export function emaAlertNote(intervalText: string, period: number): string {
  const name = intervalText.trim();
  return `${name}EMA${period}`;
}
