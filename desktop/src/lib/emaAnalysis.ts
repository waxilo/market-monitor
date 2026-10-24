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
import {
  addPriceLine,
  bindPriceLine,
  removePriceLine,
  type LineOrigin,
  type PriceLine,
} from './priceLines';
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

/* ── 系统划线的回显（面板行 ↔ 图上那条线）──────────────────────────────
 *
 * 面板里点一行会**代画**一条系统划线（`PriceLine.origin = { kind: 'ema', interval, period }`），
 * 线在图上与手画的线看不出差别，但身份记在存档里。回显就是反过来读这份身份：
 *
 *  - 面板重开时，靠 (周期, 档位) 认出「这一档我已经画过了」→ 行上标「已画」；
 *  - EMA 每分钟都在动，**线不会自己跟着走**。凡线上价位与这一档当前值不一致的，
 *    行上标「待对齐」，点一下就把它拉到当前值（这比让它凭空多一条线体面得多，
 *    也正是旧逻辑的病灶：每点一次漂过的档位就叠一条新线，两条都画着、谁也不准）。
 *
 * 一切判断都落在「这一档 ↔ 一条线」上，所以**同一档最多只认一条线**（见 `findEmaLine`）。
 */

/** 图上与某一档 EMA 绑定的那条线（面板行据此回显）。 */
export interface EmaLineRef {
  /** 画线表里的下标（`applyEmaLine` 立刻拿它改同一帧的线表，所以别隔着一次渲染用）。 */
  index: number;
  /** 线上的价位 —— **不是**当前 EMA 值：线停在画它的那一刻。 */
  price: number;
  /** 这条线是否开着价格告警（面板点出来的默认开着，用户可在右键菜单里关）。 */
  alert: boolean;
  /** 线上的备注（自动备注，或用户改写后的那句话）。 */
  note?: string;
  /** 线上价位与这一档的当前 EMA 值是否**完全一致**（不一致 = 漂了）。 */
  aligned: boolean;
}

/** 一档 EMA 在面板里的身份：匹配键 (周期, 档位) + 算漂移用的当前值。 */
export interface EmaLineKey {
  interval: string;
  period: number;
  value: number;
}

/** 这一档的系统划线身份 —— 落进 `PriceLine.origin` 的就是它。 */
export function emaOrigin(interval: string, period: number): LineOrigin {
  return { kind: 'ema', interval, period };
}

/**
 * 在画线表里找这一档 EMA 对应的线，找不到给 null。
 *
 * 两道匹配，**身份优先、备注兜底**：
 *  1. `origin` 里记的 (周期, 档位) 与这一行相同 —— 带上这个字段之后画出来的线；
 *  2. 没有 `origin`、但备注**正好等于**自动备注（`1时EMA50`）—— 老存档里的线就长这样，
 *     那时候还没有身份字段。自动备注是 (周期, 档位) 的双射，按它认不会串档；
 *     手改过备注的老线认不回来，这与「备注就是线的出处」的既有口径一致。
 *
 * 同身份撞上多条（旧逻辑每点一次漂过的档位就叠一条）时只认**第一条**：
 * 回显回答的是「这一档画过没有」，不是「画了几条」。
 */
export function findEmaLine(
  lines: readonly PriceLine[],
  level: EmaLineKey,
  note: string,
): EmaLineRef | null {
  const refOf = (index: number): EmaLineRef => {
    const line = lines[index];
    const ref: EmaLineRef = {
      index,
      price: line.price,
      alert: line.alert,
      aligned: line.price === level.value,
    };
    if (line.note) ref.note = line.note;
    return ref;
  };
  /* 身份优先：两遍扫而不是一遍 —— 老存档的线一定排在前面（它是先画的），
     一遍扫会先撞上它，于是「已经带身份的那条」永远轮不到。 */
  let legacy = -1;
  for (let index = 0; index < lines.length; index++) {
    const origin = lines[index].origin;
    if (origin) {
      if (origin.kind === 'ema' && origin.interval === level.interval && origin.period === level.period) {
        return refOf(index);
      }
      continue;
    }
    if (legacy < 0 && note !== '' && lines[index].note === note) legacy = index;
  }
  return legacy >= 0 ? refOf(legacy) : null;
}

/** `applyEmaLine` 干了什么（调用方据此决定要不要把画线眼睛自动睁开）。 */
export type EmaLineDid =
  /** 新画了一条。 */
  | 'add'
  /** 已有那条被拉到当前值（顺带补上身份 / 打开告警）。 */
  | 'align'
  /** 同价上本来是手画的线，被收编成系统划线。 */
  | 'adopt'
  /** 已对齐的线被删掉（再点一下即取消）。 */
  | 'remove'
  /** 什么都不用做（价位非法）。 */
  | 'none';

export interface EmaLinePlan {
  did: EmaLineDid;
  /** 落盘用的新线表（`did === 'none'` 时原样返回）。 */
  lines: PriceLine[];
}

/**
 * 面板里点一行 = 对画线表做**一次**完整改动，四种结果：
 *
 *  - 这一档还**没有**线 → 新画一条（带自动备注与身份，开告警）；
 *  - 有、且价位与当前值一致、还开着告警 → 删掉（「再点一下取消」的旧手感不变）；
 *  - 有、但漂了（或告警被关掉）→ **拉到当前值**（`align`）：这一档的位置变了，
 *    线留在旧价上就成了一条没人认领的横线；
 *  - 没有绑定线、但**这个价上正好有一条手画的线** → 收编（`adopt`）而不是新增：
 *    同价再画一条只会重合，删一条时看着像没反应（`addPriceLine` 也这么挡）。
 *
 * 返回改好的整张表而不是「动作」：这一下可能同时改价位 + 告警 + 来源 + 备注，
 * 让调用方逐条调四个接口，中间态就会落盘。
 */
export function applyEmaLine(
  lines: readonly PriceLine[],
  level: EmaLineKey,
  note: string,
): EmaLinePlan {
  if (!Number.isFinite(level.value) || level.value <= 0) return { did: 'none', lines: [...lines] };
  const origin = emaOrigin(level.interval, level.period);
  const hit = findEmaLine(lines, level, note);
  if (hit) {
    if (hit.aligned && hit.alert) {
      return { did: 'remove', lines: removePriceLine(lines, hit.index) };
    }
    return {
      did: 'align',
      lines: bindPriceLine(lines, hit.index, { price: level.value, alert: true, origin, note }),
    };
  }
  // 精确价：与 `addPriceLine` 的去重口径一致（面板里点的就是同一个快照里的同一个数）
  const same = lines.findIndex((line) => line.price === level.value);
  if (same >= 0) {
    return {
      did: 'adopt',
      lines: bindPriceLine(lines, same, { price: level.value, alert: true, origin, note }),
    };
  }
  return { did: 'add', lines: addPriceLine(lines, level.value, true, note, origin) };
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
