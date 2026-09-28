/** 图表几何纯函数：与 App ui/chart/ChartViewport.kt（ChartViewport + ValueRange）同一套规则。 */

export interface Range {
  low: number;
  high: number;
}

export function clampNum(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

const EPSILON = 1e-12;
const DEFAULT_PADDING = 0.06;

/** 可见区间（闭区间，含 NaN 则跳过）的极值。 */
export function rangeOf(values: number[], from = 0, to = values.length - 1): Range | null {
  const start = Math.max(0, Math.min(from, values.length));
  const end = Math.min(to, values.length - 1);
  if (start > end) return null;
  let low = NaN;
  let high = NaN;
  for (let i = start; i <= end; i++) {
    const v = values[i];
    if (Number.isNaN(v)) continue;
    if (Number.isNaN(low) || v < low) low = v;
    if (Number.isNaN(high) || v > high) high = v;
  }
  return Number.isNaN(low) ? null : { low, high };
}

/** 上下各留 ratio 余量，否则最长影线会贴着边框。 */
export function padded(range: Range, ratio = DEFAULT_PADDING): Range {
  if (Number.isNaN(range.low) || Number.isNaN(range.high)) return { low: 0, high: 1 };
  if (range.low === range.high) {
    const pad = Math.max(Math.abs(range.low) * ratio, EPSILON);
    return { low: range.low - pad, high: range.high + pad };
  }
  const span = range.high - range.low;
  return { low: range.low - span * ratio, high: range.high + span * ratio };
}

/** 归一化纵向位置：0 顶部、1 底部；结果只夹到 [-0.5, 1.5]，超出的交给裁剪。 */
export function toFraction(range: Range, value: number): number {
  if (Number.isNaN(value)) return NaN;
  const span = range.high - range.low;
  if (span <= EPSILON) return 0.5;
  return clampNum((range.high - value) / span, -0.5, 1.5);
}

export function fromFraction(range: Range, fraction: number): number {
  return range.high - fraction * (range.high - range.low);
}

/** 纵轴刻度：1-2-5 阶梯，任何量级下都是 4~6 条网格线。 */
export function gridLines(range: Range, count = 5): number[] {
  if (Number.isNaN(range.low) || Number.isNaN(range.high) || range.high <= range.low) return [];
  const rough = (range.high - range.low) / Math.max(1, count);
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
  const normalized = rough / magnitude;
  const step =
    (normalized < 1.5 ? 1 : normalized < 3.5 ? 2 : normalized < 7.5 ? 5 : 10) * magnitude;
  if (!(step > 0) || !Number.isFinite(step)) return [range.low, range.high];
  const lines: number[] = [];
  for (let v = Math.ceil(range.low / step) * step; v <= range.high + step * 1e-6; v += step) {
    lines.push(v);
    if (lines.length > 12) break;
  }
  return lines;
}

/* ── 价格量程（与 App ChartViewport.ValueRange + ChartGesture 同规则） ──
   纵向拖拽 = **平移画布**（把 K 线整体上下搬），纵向缩放只留给 Shift 滚轮。 */

/** 量程缩放的边界（相对自动量程的倍数），与 App ValueRange.MIN/MAX_SPAN_RATIO 同值。 */
export const MIN_SPAN_RATIO = 0.08;
export const MAX_SPAN_RATIO = 12;
/** 可视区与数据区的最小重叠比例（等于 App ValueRange.MIN_VISIBLE_SHARE）。 */
const PRICE_MIN_VISIBLE_SHARE = 0.25;

/**
 * 价格量程上的「用户意图」：只存这两个量，实际量程由它们和当帧自动量程（base）算出。
 *
 * 都是**相对未缩放量程**的比值，所以自动量程随可见 K 线每帧变化时，
 * 用户的缩放/平移不会被自动量程带着漂 —— 与 App 的 priceZoom/pricePan 同语义。
 */
export interface PriceView {
  /** 累积缩放因子，1 = 自动量程。 */
  zoom: number;
  /** 中心相对自动量程中心的位移，单位为「未缩放量程跨度」（+ = 看到更高的价）。 */
  pan: number;
}

export function initialPriceView(): PriceView {
  return { zoom: 1, pan: 0 };
}

/** 是否已偏离自动量程（决定要不要显示「刻度已缩放 · 复位」小标）。 */
export function priceAdjusted(view: PriceView): boolean {
  return view.zoom !== 1 || view.pan !== 0;
}

function baseSpanOf(base: Range): number {
  return base.high - base.low;
}

function viewSpan(base: Range, zoom: number): number {
  return baseSpanOf(base) * clampNum(zoom, MIN_SPAN_RATIO, MAX_SPAN_RATIO);
}

/**
 * 平移上限（价格为单位的「中心可偏移量」）。
 *
 * 收口条件是**几何重叠**而不是位移比例：可视区与数据区的重叠必须 >=
 * `min(可视跨度, 数据跨度) * PRICE_MIN_VISIBLE_SHARE`。按位移比例钳（比如「最多移半屏」）
 * 在放大到 12 倍时会退化成「位移上限远小于可视跨度」而几乎拖不动，
 * 反过来缩小时又能把 K 线整屏拖出去。
 *
 * 令 spanB = 数据跨度、s = 可视跨度，重叠恰好等于 `min(s, spanB) * share` 时中心偏移量为
 * `(s + spanB) / 2 - min(s, spanB) * share`（两种情况合并后的闭式解）。
 */
export function pricePanLimit(base: Range, zoom: number): number {
  const baseSpan = baseSpanOf(base);
  const span = viewSpan(base, zoom);
  if (!(baseSpan > 0)) return 0;
  return Math.max(0, (span + baseSpan) / 2 - Math.min(span, baseSpan) * PRICE_MIN_VISIBLE_SHARE);
}

/** 把意图量夹进合法区间（缩放夹倍数、平移夹重叠）；非有限值一律退回自动量程。 */
export function clampPriceView(view: PriceView, base: Range): PriceView {
  const zoom = Number.isFinite(view.zoom)
    ? clampNum(view.zoom, MIN_SPAN_RATIO, MAX_SPAN_RATIO)
    : 1;
  const baseSpan = baseSpanOf(base);
  if (!(baseSpan > 0) || !Number.isFinite(view.pan)) return { zoom, pan: 0 };
  const limit = pricePanLimit(base, zoom) / baseSpan;
  return { zoom, pan: clampNum(view.pan, -limit, limit) };
}

/** 由自动量程 + 用户意图算出真正要画的量程。 */
export function priceRange(base: Range, view: PriceView): Range {
  const baseSpan = baseSpanOf(base);
  if (!(baseSpan > 0)) return base;
  const v = clampPriceView(view, base);
  const span = baseSpan * v.zoom;
  const center = (base.low + base.high) / 2 + v.pan * baseSpan;
  return { low: center - span / 2, high: center + span / 2 };
}

/**
 * 纵向拖拽 → 平移量程：**向下拖（deltaY > 0）= 内容下移 = 可视区上移 = 看到更高的价**。
 *
 * 位移换算成量程单位是 `deltaY / 绘图区高度 * 当前可视跨度`；再除以 base 跨度归一化
 * （pan 的单位是 base 跨度），两者相消后正好是 `zoom`。
 */
export function panPriceView(
  view: PriceView,
  deltaY: number,
  plotHeightPx: number,
  base: Range,
): PriceView {
  if (!(plotHeightPx > 0) || !Number.isFinite(deltaY)) return clampPriceView(view, base);
  const v = clampPriceView(view, base);
  return clampPriceView({ zoom: v.zoom, pan: v.pan + (deltaY / plotHeightPx) * v.zoom }, base);
}

/** 纵向缩放（Shift 滚轮）：以当前可视中心为不动点，只改倍数。 */
export function zoomPriceView(view: PriceView, factor: number, base: Range): PriceView {
  if (!Number.isFinite(factor) || factor <= 0) return clampPriceView(view, base);
  const v = clampPriceView(view, base);
  return clampPriceView({ zoom: v.zoom * factor, pan: v.pan }, base);
}

export type DragAxis = 'H' | 'V';

/**
 * 拖拽的方向锁：累积位移够 threshold 才定性，两个方向都不到时返回 null（还没定）。
 *
 * 定性的那一轴整场不再改 —— 上下与左右不允许叠加：否则斜着拖时两个轴一起动，
 * 用户根本控制不住。到阈值后用**绝对值较大的那个方向**定性，打平判横向
 * （横向平移是更高频的操作）。
 */
export function axisLock(accumX: number, accumY: number, threshold: number): DragAxis | null {
  if (threshold <= 0) return null;
  const ax = Math.abs(accumX);
  const ay = Math.abs(accumY);
  if (ax < threshold && ay < threshold) return null;
  return ax >= ay ? 'H' : 'V';
}

/* ── 时间轴视窗（只看「看多宽 + 右端停哪」两个用户意图量） ────────────── */

export interface Viewport {
  visibleBars: number;
  rightOffset: number;
}

export const MIN_BARS = 15;
export const MAX_BARS = 600;
export const DEFAULT_VISIBLE = 60;
export const DEFAULT_RIGHT_BLANK = 3;
const MIN_VISIBLE_SHARE = 0.25;

export function initialViewport(): Viewport {
  return { visibleBars: DEFAULT_VISIBLE, rightOffset: DEFAULT_RIGHT_BLANK };
}

function minVisible(barCount: number): number {
  return Math.min(MIN_BARS, Math.max(1, barCount));
}

function maxVisible(barCount: number): number {
  return Math.min(Math.max(1, barCount), MAX_BARS);
}

function maxRightBlank(visible: number): number {
  return Math.max(0, visible * (1 - MIN_VISIBLE_SHARE));
}

export function clampViewport(v: Viewport, barCount: number): Viewport {
  const visible = clampNum(v.visibleBars, minVisible(barCount), maxVisible(barCount));
  const minOffset = -Math.max(0, barCount - visible);
  const maxOffset = barCount <= visible ? 0 : maxRightBlank(visible);
  return { visibleBars: visible, rightOffset: clampNum(v.rightOffset, minOffset, maxOffset) };
}

/** 绘图区左端（浮点，可为负、可越出序列末尾）：位置按它算，取数不能。 */
export function plotStart(v: Viewport, barCount: number): number {
  if (barCount <= 0) return 0;
  const c = clampViewport(v, barCount);
  return barCount + c.rightOffset - c.visibleBars;
}

/** 真实数据的可见区间（夹在 0..barCount-1 内），只用于算量程。 */
export function dataWindow(v: Viewport, barCount: number): [number, number] {
  if (barCount <= 0) return [0, -1];
  const c = clampViewport(v, barCount);
  const end = clampNum(Math.floor(barCount - 1 + c.rightOffset), 0, barCount - 1);
  const start = clampNum(Math.floor(barCount + c.rightOffset - c.visibleBars), 0, end);
  return [start, end];
}

/** 完整绘图区区间（含右侧留白，可能越界）：纯几何用，索引一律 getOrNull。 */
export function plotRange(v: Viewport, barCount: number): [number, number] {
  if (barCount <= 0) return [0, -1];
  const c = clampViewport(v, barCount);
  return [
    Math.floor(barCount + c.rightOffset - c.visibleBars),
    Math.floor(barCount - 1 + c.rightOffset),
  ];
}

/** 平移：deltaBars > 0（手指右移）= 看更早的数据。 */
export function panViewport(v: Viewport, deltaBars: number, barCount: number): Viewport {
  return clampViewport({ visibleBars: v.visibleBars, rightOffset: v.rightOffset - deltaBars }, barCount);
}

/** 缩放：anchorRatio 处的蜡烛保持不动（0 左端、1 右端）。 */
export function zoomViewport(
  v: Viewport,
  barFactor: number,
  anchorRatio: number,
  barCount: number,
): Viewport {
  const c = clampViewport(v, barCount);
  if (barCount <= 0 || !Number.isFinite(barFactor) || barFactor <= 0) return c;
  const target = clampNum(
    c.visibleBars * clampNum(barFactor, 0.05, 20),
    minVisible(barCount),
    maxVisible(barCount),
  );
  const start = barCount + c.rightOffset - c.visibleBars;
  const ratio = clampNum(anchorRatio, 0, 1);
  const anchor = start + c.visibleBars * ratio;
  const newStart = anchor - target * ratio;
  return clampViewport(
    { visibleBars: target, rightOffset: newStart + target - barCount },
    barCount,
  );
}

/** 归一化横坐标（0..1）反查蜡烛下标，结果夹回序列内。 */
export function indexAt(v: Viewport, fraction: number, barCount: number): number {
  const [first, last] = plotRange(v, barCount);
  if (first > last || barCount <= 0) return -1;
  const span = clampViewport(v, barCount).visibleBars - 1;
  if (span <= 0) return clampNum(first, 0, barCount - 1);
  const raw = plotStart(v, barCount) + clampNum(fraction, 0, 1) * span;
  return clampNum(Math.round(raw), 0, barCount - 1);
}

/* ── 时间轴标签落点 ───────────────────────────────────── */

export interface TimeLabelPlacement {
  index: number;
  leftPx: number;
}

/**
 * 三条规则缺一不可：按标签宽度折半得到左边缘；左边缘夹进 [0, plotWidth-labelWidth]；
 * 夹完后仍要与上一条已画标签保持 minGap，不够就整条丢掉（否则夹回来的首条会与第二条糊住）。
 */
export function timeLabelPlacements(
  centersPx: number[],
  labelWidthPx: number,
  plotWidthPx: number,
  minGapPx: number,
  step: number,
): TimeLabelPlacement[] {
  if (centersPx.length === 0 || plotWidthPx <= 0) return [];
  const stride = Math.max(1, step);
  const rightLimit = Math.max(0, plotWidthPx - labelWidthPx);
  const placed: TimeLabelPlacement[] = [];
  let lastLeft = -Infinity;
  for (let i = 0; i < centersPx.length; i += stride) {
    const left = clampNum(centersPx[i] - labelWidthPx / 2, 0, rightLimit);
    if (left - lastLeft >= minGapPx) {
      lastLeft = left;
      placed.push({ index: i, leftPx: left });
    }
  }
  return placed;
}
