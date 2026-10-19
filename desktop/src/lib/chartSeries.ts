/** 图表序列：与 App ui/chart/ChartSeries.kt 的 ChartModel.build 同一套规则。 */

import type { Bar, OpenInterestSeries } from './api';
import type { OpenInterestPoint } from './dialects';
import { formatCompact, NO_DATA } from './format';
import { boll, kdj, macd, rsi, sma } from './indicators';
import { padded, rangeOf, type Range } from './chartMath';

/** 线条配色只给语义角色，具体颜色由绘制层解析。
 *  PRIMARY~UNDENARY 是十一个互不相同的色位：MA 取 `[0,1,2,3,4,8,9,10]`（8 色，见 `MA_ROLES`），
 *  BOLL 继续用 `[5,6,7]`（QUINARY/SENARY/OCTONARY），两族不撞色。 */
export type LineRole =
  | 'PRIMARY'
  | 'SECONDARY'
  | 'TERTIARY'
  | 'ACCENT'
  | 'QUATERNARY'
  | 'QUINARY'
  | 'SENARY'
  | 'OCTONARY'
  | 'NONARY'
  | 'DENARY'
  | 'UNDENARY'
  | 'UP'
  | 'DOWN'
  | 'LABEL';

export interface ChartLine {
  label: string;
  role: LineRole;
  values: number[];
}

export interface ReadoutSegment {
  text: string;
  role: LineRole;
}

/** 可选副图：空数组本身就是「不显示」，用枚举值表达「无」会和「支持多选」互相打架。 */
export const SUB_PANE_KINDS = ['VOLUME', 'MACD', 'RSI', 'KDJ', 'OI'] as const;
export type SubPaneKind = (typeof SUB_PANE_KINDS)[number];

export const SUB_PANE_LABEL: Record<SubPaneKind, string> = {
  VOLUME: '成交量',
  MACD: 'MACD',
  RSI: 'RSI',
  KDJ: 'KDJ',
  OI: '持仓量',
};

/** 内置的均线档位 —— 面板「恢复默认」与坏值回落的目录（即用户可编辑前的那五档）。 */
export const MA_CHOICES: number[] = [5, 10, 20, 30, 60];

/** 均线期数的合法区间与条数上限（上限 = 色板给 MA 的 8 个色位）。 */
export const MA_MIN = 1;
export const MA_MAX = 1000;
export const MA_LIMIT = 8;

/**
 * 十一色位对应的十六进制色值，与 App `ui/theme/Color.kt` 的 `ChartLineColors` 同源。
 * 绘制层按角色取色，配置面板直接拿它画色点/色板。
 */
export const LINE_COLORS = [
  '#5C8AC6',
  '#F09A3E',
  '#E8C547',
  '#9D6FC0',
  '#5FBDB2',
  '#C9825E',
  '#D98CA6',
  '#9AA3AE',
  '#6FBF73',
  '#E0716B',
  '#B487E0',
];

/**
 * MA 一族可用的色位（8 个），与 BOLL 的 `[5,6,7]` 不重叠。
 * 顺序即配置面板里色板的顺序，槽位号 0~7 就存在偏好里 —— 期数 → 槽位，
 * 因此拖动排序、增删别的均线都不会改已有均线的颜色。
 */
export const MA_ROLES: LineRole[] = [
  'PRIMARY',
  'SECONDARY',
  'TERTIARY',
  'ACCENT',
  'QUATERNARY',
  'NONARY',
  'DENARY',
  'UNDENARY',
];

/** 色位在 `LINE_COLORS` 里的下标；未列出的（UP/DOWN/LABEL）不参与色板。 */
const LINE_ROLE_COLORS: LineRole[] = [
  'PRIMARY',
  'SECONDARY',
  'TERTIARY',
  'ACCENT',
  'QUATERNARY',
  'QUINARY',
  'SENARY',
  'OCTONARY',
  'NONARY',
  'DENARY',
  'UNDENARY',
];

/** MA 色槽数量（= 色板长度 = `MA_LIMIT`）。 */
export const MA_SLOT_COUNT = MA_ROLES.length;

/** 色槽 → 实际线色（配置面板的色点与色板用）。 */
export const MA_SLOT_COLORS: string[] = MA_ROLES.map(
  (role) => LINE_COLORS[LINE_ROLE_COLORS.indexOf(role)] ?? LINE_COLORS[0],
);

/** 期数 → 颜色槽位。偏好里就存这个映射（`{5:0,10:1}`）。 */
export type MaColors = Record<number, number>;

/** 色槽 → 线条角色；越界钳到 0（正常值已由 `normalizeMaColors` 校验过）。 */
export function maRoleForSlot(slot: number): LineRole {
  const i = Number.isInteger(slot) ? Math.max(0, Math.min(MA_SLOT_COUNT - 1, slot)) : 0;
  return MA_ROLES[i] ?? 'PRIMARY';
}

export interface SubPaneData {
  kind: SubPaneKind;
  title: string;
  lines: ChartLine[];
  bars?: number[];
  /** 柱状指标（VOL）从 0 起画。 */
  fromZero?: boolean;
  fixedRange?: Range;
  readoutAt: (index: number, decimals: number) => ReadoutSegment[];
}

export interface ChartSeries {
  candles: Bar[];
  overlayLines: ChartLine[];
  /** 布林带的填充区间（上轨/下轨），关闭时为 null。 */
  bandFill: { upper: number[]; lower: number[] } | null;
  subPanes: SubPaneData[];
}

export const MACD_PARAMS: [number, number, number] = [12, 26, 9];
export const RSI_PERIOD = 14;
/** 布林带默认参数，与 App Indicators.BOLL_PERIOD / BOLL_MULTIPLIER 同值。 */
export const BOLL_PERIOD = 20;
export const BOLL_MULTIPLIER = 2;

function localeNumber(value: number | undefined, decimals: number): string {
  if (value === undefined || Number.isNaN(value)) return NO_DATA;
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 持仓量对齐：每根 K 线取「时间 ≤ 它开盘时间」的最新一个点（前向填充）—— 持仓量的原生
 * 周期通常比图周期粗（5m 对 15m）或点数覆盖不满整屏，前向填充让两种情形都成立。
 * 早于第一个点的 K 线给 NaN（线从有数据的地方才开始画）；`baseCoin` 时按该根收盘价折美元。
 */
export function alignOpenInterest(
  candles: readonly Bar[],
  points: readonly OpenInterestPoint[],
  baseCoin = false,
): number[] {
  const values = new Array<number>(candles.length).fill(NaN);
  let j = 0;
  let current = NaN;
  for (let i = 0; i < candles.length; i++) {
    const t = candles[i].timestamp;
    while (j < points.length && points[j].time <= t) {
      current = points[j].value;
      j++;
    }
    if (!Number.isNaN(current)) values[i] = baseCoin ? current * candles[i].close : current;
  }
  return values;
}

function buildSubPane(
  candles: Bar[],
  closes: number[],
  kind: SubPaneKind,
  oi: OpenInterestSeries | null,
): SubPaneData | null {
  switch (kind) {
    case 'VOLUME': {
      const volumes = candles.map((c) => c.volume);
      return {
        kind,
        title: '成交量',
        lines: [],
        bars: volumes,
        fromZero: true,
        readoutAt: (i) => [
          { text: `成交量: ${formatCompact(candles[i]?.volume)}`, role: 'LABEL' },
        ],
      };
    }
    case 'MACD': {
      const [fast, slow, signalPeriod] = MACD_PARAMS;
      const m = macd(closes, fast, slow, signalPeriod);
      const title = `MACD(${fast},${slow},${signalPeriod})`;
      return {
        kind,
        title,
        lines: [
          { label: 'DIF', role: 'PRIMARY', values: m.dif },
          { label: 'DEA', role: 'SECONDARY', values: m.signal },
        ],
        bars: m.histogram,
        readoutAt: (i, decimals) => {
          const hist = m.histogram[i];
          return [
            { text: title, role: 'LABEL' },
            { text: `DIF: ${localeNumber(m.dif[i], decimals)}`, role: 'PRIMARY' },
            { text: `DEA: ${localeNumber(m.signal[i], decimals)}`, role: 'SECONDARY' },
            {
              text: `MACD: ${localeNumber(hist, decimals)}`,
              role: hist !== undefined && !Number.isNaN(hist) && hist < 0 ? 'DOWN' : 'UP',
            },
          ];
        },
      };
    }
    case 'RSI': {
      const values = rsi(closes, RSI_PERIOD);
      return {
        kind,
        title: `RSI(${RSI_PERIOD})`,
        lines: [{ label: 'RSI', role: 'PRIMARY', values }],
        fixedRange: { low: 0, high: 100 },
        readoutAt: (i) => [
          { text: `RSI(${RSI_PERIOD})`, role: 'LABEL' },
          { text: `RSI: ${localeNumber(values[i], 2)}`, role: 'PRIMARY' },
        ],
      };
    }
    case 'KDJ': {
      const high = candles.map((c) => c.high);
      const low = candles.map((c) => c.low);
      const k = kdj(high, low, closes);
      return {
        kind,
        title: 'KDJ(9,3,3)',
        lines: [
          { label: 'K', role: 'PRIMARY', values: k.k },
          { label: 'D', role: 'SECONDARY', values: k.d },
          { label: 'J', role: 'TERTIARY', values: k.j },
        ],
        readoutAt: (i) => [
          { text: 'KDJ(9,3,3)', role: 'LABEL' },
          { text: `K: ${localeNumber(k.k[i], 2)}`, role: 'PRIMARY' },
          { text: `D: ${localeNumber(k.d[i], 2)}`, role: 'SECONDARY' },
          { text: `J: ${localeNumber(k.j[i], 2)}`, role: 'TERTIARY' },
        ],
      };
    }
    case 'OI': {
      // 取不到（源不支持 / 刚换标的还没回来）就整块不出现，而不是画一块空板
      if (oi == null || oi.points.length === 0) return null;
      const values = alignOpenInterest(candles, oi.points, oi.baseCoin);
      return {
        kind,
        title: '持仓量',
        lines: [{ label: '持仓量', role: 'PRIMARY', values }],
        readoutAt: (i) => [{ text: `持仓量: ${formatCompact(values[i])}`, role: 'PRIMARY' }],
      };
    }
  }
}

export function buildSeries(
  candles: Bar[],
  maPeriods: number[],
  maColors: MaColors,
  showBoll: boolean,
  kinds: SubPaneKind[],
  oi: OpenInterestSeries | null = null,
): ChartSeries {
  const closes = candles.map((c) => c.close);
  const overlayLines: ChartLine[] = [...maPeriods]
    .sort((a, b) => a - b)
    // 颜色跟**期数**走（`maColors` 里存的就是期数 → 槽位）；
    // 缺映射时按升序序号兜底，保证直接调用 buildSeries 也不会画成同色。
    .map((period, i) => ({
      label: `MA${period}`,
      role: maRoleForSlot(maColors[period] ?? i),
      values: sma(closes, period),
    }));
  const bollData = showBoll ? boll(closes, BOLL_PERIOD, BOLL_MULTIPLIER) : null;
  if (bollData) {
    // 三条都要画：只加上下轨的话中轨（SMA）就没了；三线各占一个色位，与均线族不同色
    overlayLines.push(
      { label: 'BOLL.MB', role: 'QUINARY', values: bollData.middle },
      { label: 'BOLL.UP', role: 'SENARY', values: bollData.upper },
      { label: 'BOLL.DN', role: 'OCTONARY', values: bollData.lower },
    );
  }
  return {
    candles,
    overlayLines,
    bandFill: bollData ? { upper: bollData.upper, lower: bollData.lower } : null,
    // 按枚举声明顺序输出，多选后副图自上而下的次序不随点击先后跳；
    // OI 没有数据时 buildSubPane 给 null，就地滤掉（它只是「暂时没得画」）
    subPanes: SUB_PANE_KINDS.filter((k) => kinds.includes(k))
      .map((k) => buildSubPane(candles, closes, k, oi))
      .filter((pane): pane is SubPaneData => pane != null),
  };
}

/**
 * 主图叠加线按「指标族」拆行：MA 一族一行、BOLL 一族一行。
 *
 * 八个数值挤一行会右端溢出（画布不换行，超出的直接看不见），
 * 族内仍然一行到底 —— 与 App 读数带的 partition 规则同一套。
 */
export function overlayFamilies(series: ChartSeries): ChartLine[][] {
  const ma = series.overlayLines.filter((line) => !line.label.startsWith('BOLL'));
  const bollLines = series.overlayLines.filter((line) => line.label.startsWith('BOLL'));
  return [ma, bollLines].filter((family) => family.length > 0);
}

/** 主图纵向范围：影线 + 可见的叠加线，否则均线会顶穿画布。 */
export function mainRange(series: ChartSeries, start: number, end: number): Range {
  const wick = rangeOf(series.candles.map((c) => c.low), start, end);
  const top = rangeOf(series.candles.map((c) => c.high), start, end);
  const parts: Range[] = [];
  if (wick && top) parts.push({ low: wick.low, high: top.high });
  for (const line of series.overlayLines) {
    const r = rangeOf(line.values, start, end);
    if (r) parts.push(r);
  }
  if (parts.length === 0) return { low: 0, high: 1 };
  return padded({
    low: Math.min(...parts.map((p) => p.low)),
    high: Math.max(...parts.map((p) => p.high)),
  });
}

/** 单块副图的纵向范围：固定刻度优先，其余按可见窗口取值，各算各的。 */
export function subRange(pane: SubPaneData, start: number, end: number): Range {
  if (pane.fixedRange) return pane.fixedRange;
  const parts: Range[] = [];
  if (pane.bars) {
    const r = rangeOf(pane.bars, start, end);
    if (r) parts.push(r);
  }
  for (const line of pane.lines) {
    const r = rangeOf(line.values, start, end);
    if (r) parts.push(r);
  }
  if (parts.length === 0) return { low: 0, high: 1 };
  const low = pane.fromZero ? 0 : Math.min(...parts.map((p) => p.low));
  const high = Math.max(...parts.map((p) => p.high));
  if (low >= high) return { low, high: high + 1 };
  return padded({ low, high }, pane.fromZero ? 0 : 0.08);
}

/** 日内周期显示到时分，日线及以上显示日期。 */
export function formatCandleTime(epochMs: number, intervalMinutes: number): string {
  const d = new Date(epochMs);
  const p2 = (n: number) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
  if (intervalMinutes >= 1440) return date;
  return `${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`;
}
