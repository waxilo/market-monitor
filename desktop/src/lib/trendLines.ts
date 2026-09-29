/**
 * 两点直线（趋势线）的领域模型：**纯函数 + 零依赖**。
 *
 * 一条线 = 两个**数据空间**锚点（时间戳 ms + 价格）。屏幕坐标由「当前视窗 + 价格量程」
 * 实时算出来，所以平移/缩放时线跟着数据走，而不是钉在屏幕上。
 * 锚点用时间戳而不是蜡烛下标：下标会随新 K 线追加之类的事漂移（序列是滚动的 300 根窗口），
 * 时间戳是绝对量 —— 重开应用后锚点仍指在同一分钟，且换周期也还落在同一段行情上。
 *
 * 顺序就是**添加顺序**，不排序（与 priceLines.ts 同一条规矩）：拖拽期间下标必须稳定。
 *
 * 零依赖是硬要求：`.mts` 单测靠 Node 直接跑（Node 的 ESM 解析不接受无扩展名相对导入）。
 */

/** 直线的一个锚点：时间戳（ms）+ 价格。 */
export interface TrendAnchor {
  t: number;
  p: number;
}

/** 端点标识。 */
export type TrendEnd = 'a' | 'b';

export interface TrendLine {
  a: TrendAnchor;
  b: TrendAnchor;
}

/** 按标的索引的直线表（键 = `市场:标的`，与水平线同一套键）。 */
export type TrendStore = Record<string, TrendLine[]>;

/**
 * 端点把手的命中半径（像素）。比线身的容差大：把手是「改角度」的唯一入口，
 * 难点中就等于没法编辑（线身抓错了只是平移，把手抓错了会改斜率）。
 */
export const TREND_HANDLE_HIT_PX = 6;
/** 线身命中容差（像素），与水平线的 LINE_HIT_PX 同值。 */
export const TREND_BODY_HIT_PX = 4;

const EPSILON = 1e-9;

function finiteAnchor(v: unknown): TrendAnchor | null {
  if (!v || typeof v !== 'object') return null;
  const { t, p } = v as { t?: unknown; p?: unknown };
  if (typeof t !== 'number' || typeof p !== 'number') return null;
  if (!Number.isFinite(t) || !Number.isFinite(p)) return null;
  return { t, p };
}

function finiteLine(v: unknown): TrendLine | null {
  if (!v || typeof v !== 'object') return null;
  const { a, b } = v as { a?: unknown; b?: unknown };
  const fa = finiteAnchor(a);
  const fb = finiteAnchor(b);
  if (!fa || !fb) return null;
  return { a: fa, b: fb };
}

/** 读一份直线表：坏数据一律丢弃而不是抛错 —— 它是用户点出来的，重建成本极低。 */
export function parseTrendStore(raw: string | null): TrendStore {
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
  const out: TrendStore = {};
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!Array.isArray(value)) continue;
    const lines = value
      .map(finiteLine)
      .filter((l): l is TrendLine => l !== null);
    if (lines.length > 0) out[key] = lines;
  }
  return out;
}

/** 加一条直线。三点共线不判、自交不判 —— 只挡「两个锚点完全重合」这种画不出来的输入。 */
export function addTrendLine(
  lines: readonly TrendLine[],
  a: TrendAnchor,
  b: TrendAnchor,
): TrendLine[] {
  const fa = finiteAnchor(a);
  const fb = finiteAnchor(b);
  if (!fa || !fb) return [...lines];
  if (fa.t === fb.t && fa.p === fb.p) return [...lines];
  return [...lines, { a: fa, b: fb }];
}

/** 删第 index 条。越界返回原样（调用方可能拿着上一帧的下标）。 */
export function removeTrendLine(lines: readonly TrendLine[], index: number): TrendLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  return lines.filter((_, i) => i !== index);
}

/**
 * 线身整体平移（拖拽用；两个锚点同加一个位移）。
 * 拖拽期间下标不变（见文件头），位移由画布层按**当前映射**换算成数据空间的两个增量。
 */
export function moveTrendLineBy(
  lines: readonly TrendLine[],
  index: number,
  deltaTime: number,
  deltaPrice: number,
): TrendLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  if (!Number.isFinite(deltaTime) || !Number.isFinite(deltaPrice)) return [...lines];
  return lines.map((l, i) =>
    i === index
      ? { a: { t: l.a.t + deltaTime, p: l.a.p + deltaPrice }, b: { t: l.b.t + deltaTime, p: l.b.p + deltaPrice } }
      : l,
  );
}

/** 拖某一端改角度（另一端不动，直线绕另一端转）。 */
export function moveTrendAnchor(
  lines: readonly TrendLine[],
  index: number,
  end: TrendEnd,
  anchor: TrendAnchor,
): TrendLine[] {
  const fa = finiteAnchor(anchor);
  if (index < 0 || index >= lines.length || !fa) return [...lines];
  return lines.map((l, i) =>
    i === index ? (end === 'a' ? { a: fa, b: l.b } : { a: l.a, b: fa }) : l,
  );
}

/** 主图内的像素矩形（裁剪与命中都用它，左/上含、右/下含）。 */
export interface PixelRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface PixelSegment {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

function clipAxis(
  d: number,
  a: number,
  lo: number,
  hi: number,
): [number, number] | null {
  if (Math.abs(d) < EPSILON) return a < lo || a > hi ? null : [-Infinity, Infinity];
  let u0 = (lo - a) / d;
  let u1 = (hi - a) / d;
  if (u0 > u1) [u0, u1] = [u1, u0];
  return [u0, u1];
}

/**
 * 无限直线裁剪到矩形：返回矩形内可画的那一段（Liang–Barsky 的两轴参数区间求交）。
 * 两点重合（方向无意义）或整条线在矩形外时返回 null。
 *
 * 直线是「两点定一条线」的语义 —— 画出来的不是 A、B 之间的线段，而是向两端延伸、
 * 到绘图区边界裁掉的那一段（同花顺「直线」工具的口径）。
 */
export function clipLineToRect(
  ax: number,
  ay: number,
  bx: number,
  by: number,
  rect: PixelRect,
): PixelSegment | null {
  const dx = bx - ax;
  const dy = by - ay;
  if (Math.abs(dx) < EPSILON && Math.abs(dy) < EPSILON) return null;
  const xr = clipAxis(dx, ax, rect.left, rect.right);
  if (!xr) return null;
  const yr = clipAxis(dy, ay, rect.top, rect.bottom);
  if (!yr) return null;
  const t0 = Math.max(xr[0], yr[0]);
  const t1 = Math.min(xr[1], yr[1]);
  if (t0 > t1) return null;
  return { x1: ax + t0 * dx, y1: ay + t0 * dy, x2: ax + t1 * dx, y2: ay + t1 * dy };
}

/**
 * 点到**无限直线**的距离（像素空间）。两点重合时退化为到该点的距离 ——
 * 这条线画不出来（`clipLineToRect` 返回 null），但还应该能被右键删掉。
 */
export function distanceToLinePx(
  ax: number,
  ay: number,
  bx: number,
  by: number,
  px: number,
  py: number,
): number {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  if (len2 < EPSILON) return Math.hypot(px - ax, py - ay);
  return Math.abs(dy * (px - ax) - dx * (py - ay)) / Math.sqrt(len2);
}

export interface TrendHandle {
  index: number;
  end: TrendEnd;
  x: number;
  y: number;
}

export interface TrendHit {
  index: number;
  end: TrendEnd;
}

/** 容差内最近的一个端点把手，没有则 null。入参是**已经算好的像素坐标**（画布层才知道映射）。 */
export function nearestTrendHandle(
  handles: readonly TrendHandle[],
  x: number,
  y: number,
  tolerance = TREND_HANDLE_HIT_PX,
): TrendHit | null {
  let best: TrendHit | null = null;
  let bestDistance = tolerance;
  for (const h of handles) {
    const distance = Math.hypot(h.x - x, h.y - y);
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = { index: h.index, end: h.end };
    }
  }
  return best;
}

/** 容差内最近的一条线身（到无限直线的距离），返回下标，没有则 -1。 */
export function nearestTrendBody(
  lines: readonly PixelSegment[],
  x: number,
  y: number,
  tolerance = TREND_BODY_HIT_PX,
): number {
  let best = -1;
  let bestDistance = tolerance;
  for (let i = 0; i < lines.length; i++) {
    const distance = distanceToLinePx(lines[i].x1, lines[i].y1, lines[i].x2, lines[i].y2, x, y);
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = i;
    }
  }
  return best;
}
