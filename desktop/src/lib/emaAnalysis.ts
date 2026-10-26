/**
 * EMA 指标分析：「所有已配置的 EMA 档位 × 所有已配置的 K 线周期」的当前 EMA 值
 * 汇总成一张对照最新价的表，按上下分成**上方压力 / 下方支撑**。
 *
 * 两条口径值得写死在这里：
 *
 *  1. **取数根数**由 `emaWindow` 一处决定（最长档位 × 4，夹在 500~1000），图表首屏也按它取 ——
 *     同一档 EMA 在表里的值必须与图上那条线右端的值一致，否则用户会看到
 *     「表里 68,900、图上 68,850」这种没法解释的差；而根数太少时长档 EMA 的值会被
 *     SMA 种子拖偏（周线 EMA200 取 300 根时种子残留 37%，见 `EMA_WARMUP_FACTOR`）。
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

/** 取数根数下限（短档位也至少取这么多，图上首屏的历史长度也由它兜底）。 */
export const EMA_WINDOW_MIN = 500;
/**
 * 取数根数上限。单次上限更低的源（OKX 300 / Hyperliquid 500）由取数层往前多翻几页凑齐
 * （见 `lib/klinePaging`），所以这里不按各家单次上限夹。
 */
export const EMA_WINDOW_MAX = 1000;
/**
 * 预热倍数：取「最长档位 × 4」根。
 *
 * EMA 以前 N 根的 SMA 作种子，种子在末值里的残留权重 ≈ `(1 − 2/(N+1))^(根数 − N)`：
 * 4 倍长度时约 0.25%，与「从上市第一根算起」的 TradingView 等平台基本对得上；
 * 旧口径「最长档 + 20、下限 300」下周线 EMA200 的残留高达 37%，值随取数起点漂移几百美元。
 * 历史不足 1000 根的粗周期（币安 BTC 周线只有 ~480 根）因此等于取全量历史。
 */
export const EMA_WARMUP_FACTOR = 4;

/** 一次取多少根：`最长档位 × EMA_WARMUP_FACTOR`，夹在 [`EMA_WINDOW_MIN`, `EMA_WINDOW_MAX`]。 */
export function emaWindow(periods: readonly number[]): number {
  let longest = 0;
  for (const period of periods) {
    if (Number.isFinite(period) && period > longest) longest = period;
  }
  const wanted = Math.floor(longest) * EMA_WARMUP_FACTOR;
  return Math.min(EMA_WINDOW_MAX, Math.max(EMA_WINDOW_MIN, wanted));
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

/* ── 系统划线的回显与跟随（面板行 ↔ 图上那条线）────────────────────────
 *
 * 面板里点一行会**代画**一条系统划线（`PriceLine.origin = { kind: 'ema', interval, period }`），
 * 线在图上与手画的线看不出差别，但身份记在存档里。回显就是反过来读这份身份：
 *
 *  - 面板重开时，靠 (周期, 档位) 认出「这一档我已经画过了」→ 行上标「已画」；
 *  - EMA 每分钟都在动，所以**线必须跟着走**：每一轮取数回来（手动「刷新」与每分钟自动那一轮
 *    走的是同一条路）都由面板调一次 `alignEmaLines`，把所有系统划线拉到各自的最新值。
 *    行上仍保留「待对齐」这一态，但它只覆盖两种短暂情形：线上价位刚被用户拖过（下一轮刷新
 *    就拉回），以及这一档这一轮没取到数。点一下那行照样能立刻拉回来。
 *
 * 一切判断都落在「这一档 ↔ 一条线」上，所以**同一档最多只认一条线**（见 `findEmaLine`）。
 */

/** 图上与某一档 EMA 绑定的那条线（面板行据此回显）。 */
export interface EmaLineRef {
  /** 画线表里的下标（`applyEmaLine` 立刻拿它改同一帧的线表，所以别隔着一次渲染用）。 */
  index: number;
  /** 线上的价位 —— **不是**当前 EMA 值：线停在画它的那一刻。 */
  price: number;
  /** 这条线是否开着价格告警（面板点出来的默认开着，可在划线区里选中后关）。 */
  alert: boolean;
  /** 线上的备注（自动备注，或用户改写后的那句话）。 */
  note?: string;
  /** 线上价位与这一档的当前 EMA 值是否**完全一致**（不一致 = 漂了，下一轮刷新会把它拉回）。 */
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
 *    线留在旧价上就成了一条没人认领的横线。（每轮刷新本来就会自动做这件事，见
 *    `alignEmaLines`；这里留着是因为「刚拖过、等不到下一轮」时用户该能立刻拉回。）
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
 * 一轮自动对齐的结果。
 *
 * `moved === 0` 意味着**这一轮一条线都不用动**（绝大多数刷新就是这样：EMA 没走出
 * 一个 double 的差别），调用方据此跳过落盘 —— 每分钟都往 localStorage 里写一份同样的
 * 线表，既没必要，也会把「上次修改」这类将来的审计信息搅浑。
 */
export interface EmaAlignPlan {
  /** 落盘用的新线表（`moved === 0` 时是原表的浅拷贝，内容一字不差）。 */
  lines: PriceLine[];
  /** 被拉动的条数。 */
  moved: number;
}

/**
 * 把图上**所有系统划线**拉到各自最新的 EMA 值上 —— 「线跟着 EMA 走」的唯一执行点。
 *
 * 只认 `origin.kind === 'ema'` 的线：手画的线价位是用户自己的判断，刷新不该去动它
 * （连「价格正好落在某条手画线上」也不动 —— 那是两条各司其职的线，重合就重合）。
 *
 * 只改 `price` 一个字段：备注可能是用户改过的、告警开关可能是用户关掉的，这一下只是
 * 「同一条线挪到新价位」，不是重新生成，也不替用户做决定。
 *
 * 认不出对应档位的线（配置里删了 EMA50、或换了周期）原地不动：它已经不属于面板里任何
 * 一行，没有「当前值」可对齐 —— 面板另有「不在当前配置里」的提示管它们。
 *
 * 判据是**精确相等**：面板与这里用的是同一个 `lastEma` 算出来的同一个 double，
 * 落盘再读回来也还是那个数。不设容差 —— 设了就会出现「差一点点也算对齐」，
 * 于是面板说「已画」、图上的线却明显偏着，两边开始各说各话。
 *
 * 同一档撞上多条线（旧逻辑每点一次漂过的档位就叠一条）时**全部拉到同一个价**：
 * 它们本来就是同一条 EMA 的重复落款，让其中几条留在旧价上反而更难收拾。
 */
export function alignEmaLines(
  lines: readonly PriceLine[],
  levels: readonly EmaLevel[],
): EmaAlignPlan {
  const targets = new Map<string, number>();
  for (const level of levels) {
    if (!Number.isFinite(level.value) || level.value <= 0) continue;
    targets.set(`${level.interval}|${level.period}`, level.value);
  }
  if (targets.size === 0) return { lines: [...lines], moved: 0 };

  let moved = 0;
  const next = lines.map((line) => {
    const origin = line.origin;
    if (!origin || origin.kind !== 'ema') return line;
    const target = targets.get(`${origin.interval}|${origin.period}`);
    if (target === undefined || target === line.price) return line;
    moved += 1;
    return { ...line, price: target };
  });
  return { lines: moved > 0 ? next : [...lines], moved };
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

