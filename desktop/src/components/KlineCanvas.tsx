import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Bar } from '../lib/api';
import {
  axisLock,
  clampNum,
  clampViewport,
  dataWindow,
  fromFraction,
  gridLines,
  indexAt,
  initialPriceView,
  initialViewport,
  overDataAt,
  panPriceView,
  panViewport,
  plotRange,
  plotStart,
  priceAdjusted,
  priceRange,
  timeAtIndex,
  timeIndexOf,
  timeLabelPlacements,
  toFraction,
  zoomPriceView,
  zoomViewport,
  type DragAxis,
  type PriceView,
  type Range,
  type Viewport,
} from '../lib/chartMath';
import {
  buildSeries,
  formatCandleTime,
  mainRange,
  overlayFamilies,
  subRange,
  type LineRole,
  type ReadoutSegment,
  type SubPaneKind,
} from '../lib/chartSeries';
import {
  AXIS_W,
  chartHeights,
  READOUT_LINE,
  READOUT_PAD,
  SUB_PANE_H,
  SUB_READOUT_H,
  TIME_AXIS_H,
} from '../lib/chartLayout';
import { decimalsFor, formatCompact } from '../lib/format';
import { minutesOf } from '../lib/intervals';
import { nearestLineWithin } from '../lib/priceLines';
import {
  clipLineToRect,
  nearestTrendBody,
  nearestTrendHandle,
  type TrendAnchor,
  type TrendEnd,
  type TrendHandle,
  type TrendLine,
} from '../lib/trendLines';

/**
 * 纵向几何（各段高度分配、刻度带宽度）在 `lib/chartLayout.ts` —— 那里是唯一出处，
 * `chartHeights()` 负责把可视区高度分给 读数带 / 主图 / 副图 / 时间轴。这里只留纯**绘制**用的。
 *
 * 与 App ui/chart/KlineChart.kt 的 ChartGeo 对齐（dp → px）：价格刻度固定右侧、时间刻度固定底部、
 * 副图每块固定高。**唯一不同的是主图会把剩余空间吃满**（有副图则让位、没副图则自己占满）
 * —— App 在手机上靠页面滚动，桌面端窗口高度固定、滚动条又被隐藏，见 chartLayout.ts 里 chartHeights 的注释。
 */
const READOUT_X = 8;
const BODY_SHARE = 0.66;
const MAX_TIME_LABELS = 6;
const TIME_LABEL_GAP = 12;
const AXIS_MIN_GAP = 13;
const FONT = '10px ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace';

/** 拖拽方向定性所需的累积位移（App 用触摸的 touchSlop，网页鼠标取固定值）。 */
const DRAG_LOCK_PX = 6;
/** 价格刻度偏离自动量程时的复位小标（缩放或平移都会出现）。 */
const RESET_BADGE_TEXT = '刻度已调 · 复位';
const RESET_BADGE_H = 16;
/** 两点直线：两次落点（或按住拖）的最短距离。太近就当这一下还没落定，别画出退化的线。 */
const TREND_MIN_DRAW_PX = 8;
/** 直线端点把手的半径（画出来的大小；命中半径在 trendLines.ts）。 */
const TREND_HANDLE_R = 3.5;

/** 与 App ui/theme/Color.kt 的 ChartLineColors 同一套八色位。 */
const LINE_COLORS = [
  '#5C8AC6',
  '#F09A3E',
  '#E8C547',
  '#9D6FC0',
  '#5FBDB2',
  '#C9825E',
  '#D98CA6',
  '#9AA3AE',
];

interface Palette {
  up: string;
  down: string;
  grid: string;
  label: string;
  ink: string;
  paper: string;
  wash: string;
  edge: string;
  /** 主色：布林带的填充色（App 用 scheme.primary 8% 透明度）。 */
  accent: string;
  /** 用户画的水平线：刻意不取涨跌色，免得被读成行情信号。 */
  line: string;
}

/** 颜色从 CSS 变量读，主题切换只需重画。 */
function readPalette(): Palette {
  const cs = getComputedStyle(document.documentElement);
  const v = (name: string) => cs.getPropertyValue(name).trim();
  return {
    up: v('--up') || '#00a96e',
    down: v('--down') || '#e5384b',
    grid: v('--hairline') || '#2a2e34',
    label: v('--muted') || '#8c939e',
    ink: v('--ink') || '#ecedef',
    paper: v('--paper') || '#0c0d0f',
    wash: v('--wash') || '#1b1e22',
    edge: v('--hairline-strong') || '#3a3f47',
    accent: v('--accent') || '#f0b90b',
    line: v('--line-mark') || '#8b9bff',
  };
}

function roleColor(role: LineRole, p: Palette): string {
  switch (role) {
    case 'PRIMARY':
      return LINE_COLORS[0];
    case 'SECONDARY':
      return LINE_COLORS[1];
    case 'TERTIARY':
      return LINE_COLORS[2];
    case 'ACCENT':
      return LINE_COLORS[3];
    case 'QUATERNARY':
      return LINE_COLORS[4];
    case 'QUINARY':
      return LINE_COLORS[5];
    case 'SENARY':
      return LINE_COLORS[6];
    case 'OCTONARY':
      return LINE_COLORS[7];
    case 'UP':
      return p.up;
    case 'DOWN':
      return p.down;
    default:
      return p.label;
  }
}

function hline(ctx: CanvasRenderingContext2D, x0: number, x1: number, y: number) {
  ctx.beginPath();
  ctx.moveTo(x0, Math.round(y) + 0.5);
  ctx.lineTo(x1, Math.round(y) + 0.5);
  ctx.stroke();
}

function vline(ctx: CanvasRenderingContext2D, x: number, y0: number, y1: number) {
  ctx.beginPath();
  ctx.moveTo(Math.round(x) + 0.5, y0);
  ctx.lineTo(Math.round(x) + 0.5, y1);
  ctx.stroke();
}

function strokeLine(
  ctx: CanvasRenderingContext2D,
  values: number[],
  from: number,
  to: number,
  xOf: (i: number) => number,
  yOfValue: (i: number) => number,
): boolean {
  let started = false;
  ctx.beginPath();
  for (let i = from; i <= to; i++) {
    const value = values[i];
    if (value === undefined || Number.isNaN(value)) continue;
    const x = xOf(i);
    const y = yOfValue(i);
    if (!started) {
      ctx.moveTo(x, y);
      started = true;
    } else {
      ctx.lineTo(x, y);
    }
  }
  if (started) ctx.stroke();
  return started;
}

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
  outline?: string,
) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
  ctx.fill();
  if (outline) {
    ctx.strokeStyle = outline;
    ctx.lineWidth = 1;
    ctx.stroke();
  }
}

function drawSegments(
  ctx: CanvasRenderingContext2D,
  segments: ReadoutSegment[],
  x: number,
  y: number,
  p: Palette,
) {
  let cursor = x;
  for (const seg of segments) {
    ctx.fillStyle = roleColor(seg.role, p);
    ctx.fillText(seg.text, cursor, y);
    cursor += ctx.measureText(seg.text).width + 10;
  }
}

/** 两点直线的端点把手：实心圆点 + 纸色描边（压在 K 线上也看得清）。出界的不画。 */
function drawTrendHandle(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  p: Palette,
  top: number,
  bottom: number,
  plotW: number,
) {
  if (x < -TREND_HANDLE_R || x > plotW + TREND_HANDLE_R) return;
  if (y < top - TREND_HANDLE_R || y > bottom + TREND_HANDLE_R) return;
  ctx.beginPath();
  ctx.arc(x, y, TREND_HANDLE_R, 0, Math.PI * 2);
  ctx.fillStyle = p.line;
  ctx.fill();
  ctx.strokeStyle = p.paper;
  ctx.lineWidth = 1;
  ctx.stroke();
}

/**
 * 数据空间锚点 → 主图像素（两点直线的绘制与命中判定**共用这一处**换算）。
 * 与 draw 里的 `xOf`/`yOf` 严格同式：x 走 `(i - start + 0.5) * slot`、y 走
 * `top + toFraction(量程, 价格) * 高` —— 两处各写一份必然会分叉（曾经手写过就分叉）。
 */
function anchorPx(
  anchor: TrendAnchor,
  candles: Bar[],
  intervalMs: number,
  vp: Viewport,
  mainR: Range,
  top: number,
  bottom: number,
  plotW: number,
): { x: number; y: number } {
  const n = candles.length;
  const slot = plotW / Math.max(1, clampViewport(vp, n).visibleBars);
  const x = (timeIndexOf(candles, anchor.t, intervalMs) - plotStart(vp, n) + 0.5) * slot;
  const y = top + toFraction(mainR, anchor.p) * (bottom - top);
  return { x, y };
}

/** 「画图」那一格里的两种画线工具（**单选**，选择器在 ChartView 的工具条）。 */
export type DrawTool = 'hline' | 'trend';

interface Props {
  candles: Bar[];
  maPeriods: number[];
  showBoll: boolean;
  subPanes: SubPaneKind[];
  interval: string;
  tickSize?: string;
  theme: string;
  /** 换标的或换周期时视窗复位（App 的 remember(symbolKey, interval) 同理）。 */
  resetKey: string;
  /**
   * 用户画的水平线（价格）与两点直线。状态与落盘都在 ChartView 侧（`useDrawings`），
   * 这里只负责画和手势 —— 画布不做持久化。
   */
  priceLines: number[];
  trendLines: TrendLine[];
  /**
   * 当前选中的画线工具；`null` = 工具条没停在「画图」这一格，画布对画线手势完全免疫
   * （线照旧显示，右键也删不动 —— 看图的时候不该有任何手滑改图的机会）。
   *
   * 两种工具的手势分工：
   * - `hline`：左键**单击**空白落一条水平线（按住拖仍是平移），已有的线可拖；
   * - `trend`：左键**按住拖**拉出直线，或**点两下**定两点，已有的线可拖。
   *
   * 相同的是：抓取已有线（把手 → 直线线身 → 水平线）在两种工具下都优先于起笔画新线，
   * 右键统一删线。
   */
  drawTool: DrawTool | null;
  onAddLine: (price: number) => void;
  onRemoveLine: (index: number) => void;
  onMoveLine: (index: number, price: number) => void;
  onAddTrend: (a: TrendAnchor, b: TrendAnchor) => void;
  onRemoveTrend: (index: number) => void;
  onMoveTrendBy: (index: number, deltaTime: number, deltaPrice: number) => void;
  onMoveTrendAnchor: (index: number, end: TrendEnd, anchor: TrendAnchor) => void;
}

export function KlineCanvas({
  candles,
  maPeriods,
  showBoll,
  subPanes,
  interval,
  tickSize,
  theme,
  resetKey,
  priceLines,
  trendLines,
  drawTool,
  onAddLine,
  onRemoveLine,
  onMoveLine,
  onAddTrend,
  onRemoveTrend,
  onMoveTrendBy,
  onMoveTrendAnchor,
}: Props) {
  const series = useMemo(
    () => buildSeries(candles, maPeriods, showBoll, subPanes),
    [candles, maPeriods, showBoll, subPanes],
  );
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const vpRef = useRef<Viewport>(initialViewport());
  /**
   * 价格量程的用户意图（缩放倍数 + 平移量），1 / 0 = 自动量程。
   * 与时间轴视窗同为「用户意图」，换标的/周期一律复位。
   */
  const priceRef = useRef<PriceView>(initialPriceView());
  /**
   * 十字光标。`index` 是**夹回序列内**的下标（读数带、横线与价格标都按它走），
   * `onBar` 则表示指针是不是真的压在某根蜡烛的格子上 —— 竖线只看这一个开关，
   * 因为视窗右侧还有一段没有蜡烛的留白（默认 3 根）。
   */
  const crossRef = useRef<{ index: number; y: number; onBar: boolean } | null>(null);
  /** 复位小标上一帧的落点，供点击命中判定；不在图上时为 null。 */
  const badgeRef = useRef<{ x: number; y: number; w: number; h: number } | null>(null);
  /**
   * 「画直线」模式的待定锚点：第一下落点（或按住拖的起点）与预览的当前点。
   * `to` 为空 = 还没动过（只有起点把手），移动后才出现预览线。
   * 内容一律是**数据空间**锚点，所以预览期间自动量程变化也不会甩开鼠标。
   */
  const pendingRef = useRef<{ a: TrendAnchor; to: TrendAnchor | null } | null>(null);
  /**
   * 画布容器（`.chart-scroll`）的**实测**尺寸。
   *
   * 高度决定纵向分配；宽度只用于「宽度变了也要重画」—— 收起/展开侧栏只改宽度、
   * 高度不变，只存高度的话 setState 会因新旧值相同而 bail out，画布不会重画，
   * 于是 canvas 的位图被 CSS 拉伸（糊）。
   */
  const [box, setBox] = useState({ w: 0, h: 0 });

  // 读数带：K 线一条 + 每个叠加线族（MA / BOLL）各一行
  const readoutRows = 1 + overlayFamilies(series).length;
  /**
   * 纵向几何一律由 chartLayout 分配 —— 副图占的高度在那里被一起减掉了。
   * 之前这里手写 `clamp(avail - readout - TIME_AXIS_H)` 漏掉副图，导致第三块副图
   * 被挤出可视区（见 chartLayout.ts 的 chartHeights 注释）。
   */
  const heights = chartHeights(box.h, readoutRows, series.subPanes.length);
  const { totalH } = heights;

  /**
   * 绘制与手势共用的一份「最新值」。
   *
   * 事件监听只在挂载时绑一次（见下面手势 effect 的依赖），闭包里拿不到新 props，
   * 所以一律从这里读；同步 effect **不写依赖数组**，保证每轮渲染后都是最新的，
   * 且声明在绘制 effect 之前（effect 按声明顺序跑，先同步再画）。
   */
  const modelRef = useRef({
    series,
    heights,
    interval,
    tickSize,
    priceLines,
    trendLines,
    drawTool,
    onAddLine,
    onRemoveLine,
    onMoveLine,
    onAddTrend,
    onRemoveTrend,
    onMoveTrendBy,
    onMoveTrendAnchor,
  });
  useEffect(() => {
    modelRef.current = {
      series,
      heights,
      interval,
      tickSize,
      priceLines,
      trendLines,
      drawTool,
      onAddLine,
      onRemoveLine,
      onMoveLine,
      onAddTrend,
      onRemoveTrend,
      onMoveTrendBy,
      onMoveTrendAnchor,
    };
  });

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const {
      series: s,
      heights: gh,
      interval: iv,
      tickSize: ts,
      priceLines: pl,
      trendLines: tl,
      drawTool: tool,
    } = modelRef.current;
    const drawOn = tool !== null;
    const mh = gh.mainH;
    const list = s.candles;
    const n = list.length;
    const cssW = canvas.clientWidth;
    const cssH = canvas.clientHeight;
    if (n === 0 || cssW <= 0 || cssH <= 0) return;

    const dpr = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(cssW * dpr) || canvas.height !== Math.round(cssH * dpr)) {
      canvas.width = Math.round(cssW * dpr);
      canvas.height = Math.round(cssH * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);
    ctx.font = FONT;
    ctx.textBaseline = 'middle';
    const p = readPalette();

    const plotW = Math.max(1, cssW - AXIS_W);
    const families = overlayFamilies(s);
    // 各段的纵向落点统一取自 heights：主图与副图的边界只有一处定义
    const mainTop = gh.mainTop;
    const mainBottom = gh.mainBottom;
    const plotBottom = mainBottom + gh.subPanesH;
    const rowY = (row: number) => READOUT_PAD / 2 + READOUT_LINE * row + READOUT_LINE / 2;

    const vp = clampViewport(vpRef.current, n);
    const [ws, we] = dataWindow(vpRef.current, n);
    // 自动量程只由可见窗口决定，用户的纵向缩放/平移是叠在它之上的用户意图
    const rangeBase = mainRange(s, ws, we);
    const mainR = priceRange(rangeBase, priceRef.current);
    const subRs = s.subPanes.map((pane) => subRange(pane, ws, we));
    const [rs, re] = plotRange(vpRef.current, n);
    const start = plotStart(vpRef.current, n);
    const slot = plotW / Math.max(1, vp.visibleBars);
    const xOf = (i: number) => (i - start + 0.5) * slot;
    const yOf = (fraction: number, top: number, height: number) => top + fraction * height;
    const bodyW = clampNum(slot * BODY_SHARE, 1, 26);
    const dec = decimalsFor(ts);
    /** 一根 K 线的时长（直线锚点的时间 ⇄ 下标换算用）。 */
    const ivMs = (minutesOf(iv) ?? 15) * 60_000;
    /**
     * 过两点画**无限直线**并裁到主图（dash 非空则虚线）。
     * 直线是「两点定一条线」的语义：向两端延伸、到绘图区边界才断，不是 A-B 线段。
     */
    const mainRect = { left: 0, top: mainTop, right: plotW, bottom: mainBottom };
    const infiniteLine = (fromX: number, fromY: number, toX: number, toY: number, dash: number[]) => {
      const seg = clipLineToRect(fromX, fromY, toX, toY, mainRect);
      if (!seg) return;
      ctx.beginPath();
      ctx.moveTo(seg.x1, seg.y1);
      ctx.lineTo(seg.x2, seg.y2);
      ctx.setLineDash(dash);
      ctx.stroke();
      ctx.setLineDash([]);
    };

    // ── 主图：网格 + 蜡烛 + 均线 + 最新价虚线 ──
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, mainTop, plotW, mh);
    ctx.clip();

    ctx.strokeStyle = p.grid;
    ctx.lineWidth = 1;
    ctx.setLineDash([]);
    for (const value of gridLines(mainR)) {
      const y = yOf(toFraction(mainR, value), mainTop, mh);
      if (y >= mainTop - 0.5 && y <= mainBottom + 0.5) hline(ctx, 0, plotW, y);
    }

    ctx.lineWidth = 1;
    for (let i = rs; i <= re; i++) {
      const c = list[i];
      if (!c) continue;
      const x = xOf(i);
      if (x < -1 || x > plotW + 1) continue;
      const color = c.close >= c.open ? p.up : p.down;
      ctx.strokeStyle = color;
      const highY = yOf(toFraction(mainR, c.high), mainTop, mh);
      const lowY = yOf(toFraction(mainR, c.low), mainTop, mh);
      if (slot < 1.5) {
        // 密度太高时实体只会糊成一片，退化成一根影线
        vline(ctx, x, Math.min(highY, lowY), Math.max(highY, lowY));
        continue;
      }
      vline(ctx, x, highY, lowY);
      const openY = yOf(toFraction(mainR, c.open), mainTop, mh);
      const closeY = yOf(toFraction(mainR, c.close), mainTop, mh);
      ctx.fillStyle = color;
      ctx.fillRect(
        Math.round(x - bodyW / 2),
        Math.round(Math.min(openY, closeY)),
        Math.max(1, Math.round(bodyW)),
        Math.max(1, Math.abs(closeY - openY)),
      );
    }

    // 布林带填充：上轨正走、下轨倒走闭合成带（与 App bandPath 同），
    // 压在三条轨线下面先画，否则 8% 的浅色会把线蒙上一层。
    if (s.bandFill) {
      const { upper, lower } = s.bandFill;
      const bandIdx: number[] = [];
      for (let i = rs; i <= re; i++) {
        if (Number.isFinite(upper[i]) && Number.isFinite(lower[i])) bandIdx.push(i);
      }
      if (bandIdx.length >= 2) {
        ctx.beginPath();
        bandIdx.forEach((i, order) => {
          const x = xOf(i);
          const y = yOf(toFraction(mainR, upper[i]), mainTop, mh);
          if (order === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        for (let k = bandIdx.length - 1; k >= 0; k--) {
          const i = bandIdx[k];
          ctx.lineTo(xOf(i), yOf(toFraction(mainR, lower[i]), mainTop, mh));
        }
        ctx.closePath();
        ctx.globalAlpha = 0.08;
        ctx.fillStyle = p.accent;
        ctx.fill();
        ctx.globalAlpha = 1;
      }
    }

    ctx.lineWidth = 1.5;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    for (const line of s.overlayLines) {
      ctx.strokeStyle = roleColor(line.role, p);
      strokeLine(ctx, line.values, rs, re, xOf, (i) =>
        yOf(toFraction(mainR, line.values[i]), mainTop, mh),
      );
    }

    // 用户画的水平线：虚线横贯主图，压在 K 线/均线之上、最新价虚线之下
    // （最新价是行情本身，任何时候都不该被遮）。价格标在下面与最新价标一起画。
    if (pl.length > 0) {
      ctx.strokeStyle = p.line;
      ctx.lineWidth = 1;
      ctx.setLineDash([5, 3]);
      for (const price of pl) {
        const y = yOf(toFraction(mainR, price), mainTop, mh);
        if (y < mainTop || y > mainBottom) continue;
        hline(ctx, 0, plotW, y);
      }
      ctx.setLineDash([]);
    }

    // 用户画的两点直线：**实线**（与水平线的虚线同色系、不同线型 —— 虚线读作「一个价位」、
    // 实线读作「一条斜率」）。端点的小圆点只在选中「画图」时画：不选就是「别看错，现在动不了」。
    ctx.strokeStyle = p.line;
    ctx.lineWidth = 1;
    for (const line of tl) {
      const a = anchorPx(line.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      const b = anchorPx(line.b, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      infiniteLine(a.x, a.y, b.x, b.y, []);
    }
    if (drawOn) {
      for (const line of tl) {
        for (const anchor of [line.a, line.b]) {
          const pt = anchorPx(anchor, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
          drawTrendHandle(ctx, pt.x, pt.y, p, mainTop, mainBottom, plotW);
        }
      }
    }

    // 画线模式的预览：起点把手 + 过起点与鼠标的**虚线**（预览的就是成品的样子：无限直线）
    const pending = pendingRef.current;
    if (pending) {
      const a = anchorPx(pending.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      if (pending.to) {
        const b = anchorPx(pending.to, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
        infiniteLine(a.x, a.y, b.x, b.y, [5, 3]);
      }
      drawTrendHandle(ctx, a.x, a.y, p, mainTop, mainBottom, plotW);
    }

    const last = list[n - 1];
    const lastY = yOf(toFraction(mainR, last.close), mainTop, mh);
    const lastUp = last.close >= last.open;
    if (lastY >= mainTop && lastY <= mainBottom) {
      ctx.strokeStyle = lastUp ? p.up : p.down;
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      hline(ctx, 0, plotW, lastY);
      ctx.setLineDash([]);
    }
    ctx.restore();

    // ── 副图：分隔线 + 零轴 + 柱 + 线 ──
    s.subPanes.forEach((pane, k) => {
      const paneTop = mainBottom + k * SUB_PANE_H + SUB_READOUT_H;
      const paneH = SUB_PANE_H - SUB_READOUT_H;
      const range = subRs[k];
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, paneTop, plotW, paneH);
      ctx.clip();
      ctx.strokeStyle = p.grid;
      ctx.lineWidth = 1;
      ctx.setLineDash([]);
      hline(ctx, 0, plotW, paneTop);
      if (range.low < 0 && range.high > 0) {
        hline(ctx, 0, plotW, yOf(toFraction(range, 0), paneTop, paneH));
      }
      if (pane.bars) {
        const zero = yOf(toFraction(range, clampNum(0, range.low, range.high)), paneTop, paneH);
        ctx.globalAlpha = 0.8;
        for (let i = rs; i <= re; i++) {
          const value = pane.bars[i];
          if (value === undefined || Number.isNaN(value)) continue;
          const c = list[i];
          const x = xOf(i);
          if (x < -1 || x > plotW + 1) continue;
          const y = yOf(toFraction(range, value), paneTop, paneH);
          ctx.fillStyle = !c || c.close >= c.open ? p.up : p.down;
          ctx.fillRect(
            Math.round(x - bodyW / 2),
            Math.round(Math.min(y, zero)),
            Math.max(1, Math.round(bodyW)),
            Math.max(1, Math.abs(zero - y)),
          );
        }
        ctx.globalAlpha = 1;
      }
      ctx.lineWidth = 1.2;
      for (const line of pane.lines) {
        ctx.strokeStyle = roleColor(line.role, p);
        strokeLine(ctx, line.values, rs, re, xOf, (i) =>
          yOf(toFraction(range, line.values[i]), paneTop, paneH),
        );
      }
      ctx.restore();
    });

    // ── 十字光标（悬停）：竖线贯穿全部窗格、横线只在主图 ──
    // 竖线**只画在真蜡烛上**：视窗右侧的留白里没有蜡烛，而 `indexAt` 会把那里
    // 夹回最后一根 ⇒ 不判 `onBar` 的话，鼠标停在最新 K 线右边也会冒出一条竖线，
    // 看着像选中了最新那根（横线与价格标照旧 —— 那段留白里读价是合理的需求）。
    const cross = crossRef.current;
    if (cross && cross.index >= rs && cross.index <= re) {
      const x = xOf(cross.index);
      ctx.globalAlpha = 0.7;
      ctx.strokeStyle = p.ink;
      ctx.lineWidth = 1.5;
      ctx.setLineDash([6, 6]);
      if (cross.onBar) vline(ctx, x, mainTop, plotBottom);
      if (cross.y <= mainBottom) {
        const y = clampNum(cross.y, mainTop, mainBottom);
        hline(ctx, 0, plotW, y);
      }
      ctx.setLineDash([]);
      ctx.globalAlpha = 1;
    }

    // ── 价格刻度 ──
    ctx.fillStyle = p.label;
    let lastAxisY = -Infinity;
    for (const value of gridLines(mainR)) {
      const y = yOf(toFraction(mainR, value), mainTop, mh);
      if (y < mainTop || y > mainBottom) continue;
      if (Math.abs(y - lastAxisY) < AXIS_MIN_GAP) continue;
      lastAxisY = y;
      ctx.fillText(value.toLocaleString('en-US', {
        minimumFractionDigits: dec,
        maximumFractionDigits: dec,
      }), plotW + 4, y);
    }

    // 副图刻度：每块只取首尾两条，块本来就矮
    s.subPanes.forEach((pane, k) => {
      const range = subRs[k];
      const paneTop = mainBottom + k * SUB_PANE_H + SUB_READOUT_H;
      const paneH = SUB_PANE_H - SUB_READOUT_H;
      const ticks = gridLines(range, 2);
      let lastTickY = -Infinity;
      for (const value of ticks.length ? [ticks[0], ticks[ticks.length - 1]] : []) {
        const y = yOf(toFraction(range, value), paneTop, paneH);
        if (Math.abs(y - lastTickY) < AXIS_MIN_GAP) continue;
        lastTickY = y;
        ctx.fillText(
          pane.kind === 'VOLUME' ? formatCompact(value) : value.toFixed(2),
          plotW + 4,
          y,
        );
      }
    });

    // ── 价格标（最新价 / 十字光标）：贴在右轴上，与线同高；排在校度之后，盖住同高刻度 ──
    const drawBadge = (text: string, centerY: number, bg: string, fg: string) => {
      const w = ctx.measureText(text).width + 8;
      const h = 14;
      const y = clampNum(centerY - h / 2, mainTop, Math.max(mainTop, mainBottom - h));
      ctx.fillStyle = bg;
      roundRect(ctx, plotW + 1, y, w, h, 3);
      ctx.fillStyle = fg;
      ctx.fillText(text, plotW + 5, y + h / 2 + 0.5);
    };
    const priceText = (price: number) =>
      price.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
    // 水平线的价格标先画：最新价标后画，同高时由它盖住（行情比画线重要）。
    // **不在可视区里的线不画标** —— drawBadge 会把标夹到上下边缘，那会让一条屏外的线
    // 看着像就在那个价上。
    for (const price of pl) {
      const y = yOf(toFraction(mainR, price), mainTop, mh);
      if (y < mainTop || y > mainBottom) continue;
      drawBadge(priceText(price), y, p.line, p.paper);
    }
    if (lastY >= mainTop && lastY <= mainBottom) {
      drawBadge(priceText(last.close), lastY, lastUp ? p.up : p.down, p.paper);
    }
    if (cross && cross.index >= rs && cross.index <= re && cross.y <= mainBottom) {
      const y = clampNum(cross.y, mainTop, mainBottom);
      const fraction = mh > 0 ? (y - mainTop) / mh : 0;
      const price = fromFraction(mainR, fraction);
      if (Number.isFinite(price)) drawBadge(priceText(price), y, p.ink, p.paper);
    }

    // ── 时间轴 ──
    const minutes = minutesOf(iv) ?? 15;
    const labelW = ctx.measureText(formatCandleTime(0, minutes)).width;
    const minGap = labelW + TIME_LABEL_GAP;
    const count = re - rs + 1;
    const capacity = Math.max(1, Math.floor(plotW / minGap));
    const step = Math.max(1, Math.ceil(count / Math.min(capacity, MAX_TIME_LABELS)));
    const centers: number[] = [];
    const times: number[] = [];
    for (let i = rs; i <= re; i += step) {
      const c = list[i];
      if (!c) break;
      centers.push(xOf(i));
      times.push(c.timestamp);
    }
    ctx.fillStyle = p.label;
    for (const placement of timeLabelPlacements(centers, labelW, plotW, minGap, 1)) {
      ctx.fillText(
        formatCandleTime(times[placement.index], minutes),
        placement.leftPx,
        plotBottom + TIME_AXIS_H / 2,
      );
    }

    // ── 指标读数带（币安式：数值与它所属的线同色）──
    const idx = cross ? clampNum(cross.index, 0, n - 1) : n - 1;
    drawCandleRow(ctx, list, idx, dec, minutes, p, rowY(0));
    families.forEach((family, k) => {
      drawSegments(
        ctx,
        family.map((line) => ({
          text: `${line.label}: ${readoutNumber(line.values[idx], dec)}`,
          role: line.role,
        })),
        READOUT_X,
        rowY(1 + k),
        p,
      );
    });
    s.subPanes.forEach((pane, k) => {
      drawSegments(
        ctx,
        pane.readoutAt(idx, dec),
        READOUT_X,
        mainBottom + k * SUB_PANE_H + SUB_READOUT_H / 2,
        p,
      );
    });

    // ── 「刻度已缩放 · 复位」小标 ──
    // 刻度一旦被拖离自动量程就没法从图上看出来，也没有别的路回去（双击复位不显眼）。
    // 放绘图区**左下角**：右侧压着最新一根蜡烛、顶部压着读数带，左下角信息密度最低。
    if (priceAdjusted(priceRef.current)) {
      const w = ctx.measureText(RESET_BADGE_TEXT).width + 12;
      const x = 6;
      const y = mainBottom - RESET_BADGE_H - 6;
      ctx.fillStyle = p.wash;
      roundRect(ctx, x, y, w, RESET_BADGE_H, RESET_BADGE_H / 2, p.edge);
      ctx.fillStyle = p.label;
      ctx.fillText(RESET_BADGE_TEXT, x + 6, y + RESET_BADGE_H / 2 + 0.5);
      badgeRef.current = { x, y, w, h: RESET_BADGE_H };
    } else {
      badgeRef.current = null;
    }
  }, []);

  // 数据 / 尺寸 / 主题任一变化都重画；无依赖写法保证「每次渲染后」都会同步一次
  useEffect(() => {
    draw();
  });

  // 主题属性由 App 的 effect 写入（子 effect 先跑），下一帧再取一次色才准
  useEffect(() => {
    const id = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(id);
  }, [theme, draw]);

  useEffect(() => {
    vpRef.current = initialViewport();
    priceRef.current = initialPriceView();
    crossRef.current = null;
    // 换标的/周期时画一半的直线整体作废：锚点虽是时间戳、换周期也画得出来，但「正在画一半」
    // 的状态跨标的是没有意义的（视窗已经复位，待定点会跳到别处）
    pendingRef.current = null;
    draw();
  }, [resetKey, draw]);

  /**
   * 换画线工具时补一次光标（点工具条不经过画布的 pointermove），并撤掉画了一半的待定点 ——
   * 待定只对「直线」有意义，切去「水平线」还留着它，下一点会莫名其妙地把那条线补完。
   */
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    pendingRef.current = null;
    if (drawTool === 'trend') {
      canvas.style.cursor = 'crosshair';
      return;
    }
    if (canvas.style.cursor === 'crosshair') canvas.style.cursor = '';
  }, [drawTool]);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const measure = () => {
      const next = { w: el.clientWidth, h: el.clientHeight };
      // 没变就返回同一个对象：否则每轮观察都触发一次重渲染（取消侧栏的列宽动画时尤其频繁）
      setBox((prev) => (prev.w === next.w && prev.h === next.h ? prev : next));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    measure();
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    /**
     * 本场手势的种类。`line` = 正拖着某条水平线；`trend-body` / `trend-anchor` =
     * 正拖着直线（线身平移 / 端点改角度）；`draw` = 直线工具里按住起笔（松手成线）。
     * 种类在**按下那一刻**由落点决定：副图（以及它下面的时间轴）不参与任何手势。
     */
    type Mode = 'none' | 'pan' | 'line' | 'trend-body' | 'trend-anchor' | 'draw';
    let mode: Mode = 'none';
    /**
     * 水平线工具的「单击落线」候选价：按下时落在主图空白处就记下这一笔的价格，
     * 松手时**整场没拖出定性位移**才真的画线（拖了就是平移，一图一义）。
     * 非 null = 这一场有资格落线。
     */
    let clickPrice: number | null = null;
    /**
     * 本场拖拽锁定的轴；`null` = 还没定性。
     * 定性后整场只动这一个轴 —— 上下（平移价格刻度）与左右（平移时间轴）不允许叠加。
     */
    let axis: DragAxis | null = null;
    /**
     * 正在拖的水平线下标。拖拽期间下标不变（`movePriceLine` 按下标改、不排序，
     * 见 priceLines.ts 的文件头），松手后下一次命中判定重新算。
     */
    let lineIndex = -1;
    /** 正在拖的直线下标与端点（与水平线同一条规矩：拖拽期间下标不变）。 */
    let trendIndex = -1;
    let trendEnd: TrendEnd = 'a';
    /** 直线拖拽上一帧的**画布局部**坐标（时间位移要用像素反算，见 pointermove）。 */
    let trendLastX = 0;
    let trendLastY = 0;
    let accumX = 0;
    let accumY = 0;
    let lastX = 0;
    let lastY = 0;
    let pointerId: number | null = null;

    const geom = () => {
      const rect = canvas.getBoundingClientRect();
      return { rect, plotW: Math.max(1, rect.width - AXIS_W) };
    };

    /** 主图上下边界 —— 直接取 heights，别再手写一遍（手写过就会跟 draw 分叉）。 */
    const mainBounds = () => {
      const gh = modelRef.current.heights;
      return { top: gh.mainTop, bottom: gh.mainBottom };
    };

    /**
     * 当帧的自动量程（水平视窗算出来的那个基准量程）。
     * 纵向手势改的都是**相对它**的意图量，所以不必知道上一帧画到了哪。
     */
    const currentBase = () => {
      const s = modelRef.current.series;
      const n = s.candles.length;
      if (n <= 0) return { low: 0, high: 1 };
      const [ws, we] = dataWindow(vpRef.current, n);
      return mainRange(s, ws, we);
    };

    /** 当帧**真正画出来**的价格量程：自动量程 + 用户的缩放/平移意图（与 draw 同源）。 */
    const currentMainRange = () => priceRange(currentBase(), priceRef.current);

    /** 价格 → 主图内的 y（与 draw 里 `yOf(toFraction(mainR, price), mainTop, mh)` 同式）。 */
    const yOfPrice = (price: number) => {
      const { top, bottom } = mainBounds();
      return top + toFraction(currentMainRange(), price) * (bottom - top);
    };

    /** 主图内的 y → 价格（右键落点的反算）。 */
    const priceOfY = (y: number) => {
      const { top, bottom } = mainBounds();
      const height = bottom - top;
      return fromFraction(currentMainRange(), height > 0 ? (y - top) / height : 0);
    };

    /** 离落点最近的那条水平线的下标（4px 容差），没有则 -1。 */
    const hitLine = (y: number) =>
      nearestLineWithin(modelRef.current.priceLines.map(yOfPrice), y);

    /** 落点在主图**下方**（副图与时间轴）—— 只读区，手势与缩放都不响应。 */
    const belowMain = (y: number) => y > mainBounds().bottom;

    /* ── 两点直线的换算与命中（与 draw 共用 anchorPx，见那个函数的注释） ── */

    /** 当帧一根 K 线的时长。 */
    const intervalMs = () => (minutesOf(modelRef.current.interval) ?? 15) * 60_000;

    /** 绘图区 x → 浮点下标（`anchorPx` 里 xOf 的严格反式）。 */
    const indexOfX = (x: number, plotW: number) => {
      const n = modelRef.current.series.candles.length;
      const vp = clampViewport(vpRef.current, n);
      const slot = plotW / Math.max(1, vp.visibleBars);
      return x / slot + plotStart(vpRef.current, n) - 0.5;
    };

    /** 落点 → 数据空间锚点（时间戳 + 价格）。没数据或换算出界时给 null。 */
    const anchorAt = (x: number, y: number, plotW: number): TrendAnchor | null => {
      const list = modelRef.current.series.candles;
      if (list.length === 0) return null;
      const t = timeAtIndex(list, indexOfX(x, plotW), intervalMs());
      const p = priceOfY(y);
      return Number.isFinite(t) && Number.isFinite(p) ? { t, p } : null;
    };

    /** 锚点当前的像素位置。 */
    const pxOfAnchor = (anchor: TrendAnchor, plotW: number) => {
      const { top, bottom } = mainBounds();
      return anchorPx(
        anchor,
        modelRef.current.series.candles,
        intervalMs(),
        vpRef.current,
        currentMainRange(),
        top,
        bottom,
        plotW,
      );
    };

    /** 所有端点把手的像素位置（抓取判定用）。 */
    const trendHandles = (plotW: number): TrendHandle[] => {
      const out: TrendHandle[] = [];
      modelRef.current.trendLines.forEach((line, index) => {
        for (const end of ['a', 'b'] as const) {
          out.push({ index, end, ...pxOfAnchor(line[end], plotW) });
        }
      });
      return out;
    };

    /** 所有直线的两点像素（线身命中用；无限直线的距离只由这两点定）。 */
    const trendBodies = (plotW: number) =>
      modelRef.current.trendLines.map((line) => {
        const a = pxOfAnchor(line.a, plotW);
        const b = pxOfAnchor(line.b, plotW);
        return { x1: a.x, y1: a.y, x2: b.x, y2: b.y };
      });

    /**
     * 滚轮缩放：默认横轴（时间窗），Shift 改成纵轴（价格量程）。
     * 纵轴这一支与纵向拖拽共用同一套意图量与钳制，方向对齐「向上滚 = 放大」。
     *
     * 落点在副图上一概不响应（`preventDefault` 仍要做：否则浏览器会去滚
     * `.chart-scroll`，整块画布连着主图一起挪走，那就还是「主图变动了」）。
     */
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const { rect, plotW } = geom();
      if (belowMain(e.clientY - rect.top)) return;
      crossRef.current = null;
      if (e.shiftKey) {
        // Blink 在把 Shift+滚轮交给页面之前已经把两个轴换过（垂直滚变成了横向 delta），
        // 所以哪个轴有值用哪个，符号不变；Firefox 不换轴，走 deltaY 那一支。
        const delta = e.deltaY !== 0 ? e.deltaY : e.deltaX;
        priceRef.current = zoomPriceView(
          priceRef.current,
          Math.exp(delta * 0.0015),
          currentBase(),
        );
        draw();
        return;
      }
      const anchor = clampNum((e.clientX - rect.left) / plotW, 0, 1);
      const factor = Math.exp(e.deltaY * 0.0015);
      vpRef.current = zoomViewport(
        vpRef.current,
        factor,
        anchor,
        modelRef.current.series.candles.length,
      );
      draw();
    };

    /** 复位小标被点到就回自动量程，不进入拖拽。 */
    const hitBadge = (e: PointerEvent): boolean => {
      const hit = badgeRef.current;
      if (!hit) return false;
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      return x >= hit.x && x <= hit.x + hit.w && y >= hit.y && y <= hit.y + hit.h;
    };

    const onPointerDown = (e: PointerEvent) => {
      // 只认左键：右键走 contextmenu（删线），按下时不吞掉它
      if (e.button !== 0) return;
      clickPrice = null;
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      if (hitBadge(e)) {
        priceRef.current = initialPriceView();
        draw();
        return;
      }
      const { top, bottom } = mainBounds();
      const tool = modelRef.current.drawTool;
      // 画线只在主图里：读数带、副图、时间轴、右侧价格刻度列都不算（与旧版同规矩）
      if (tool && x <= plotW && y >= top && y <= bottom) {
        const anchor = anchorAt(x, y, plotW);
        const pending = pendingRef.current;
        // ① 直线工具且已经点下第一下：这一落点就是第二下，成线。
        //    待定**优先于抓取** —— 不然第二下正好压在已有线上就永远连不成线。
        if (tool === 'trend' && pending && anchor) {
          const a = pxOfAnchor(pending.a, plotW);
          // 离第一点太近：当作空点，别画出退化的线（保持待定，等下一次落点）
          if (Math.hypot(a.x - x, a.y - y) >= TREND_MIN_DRAW_PX) {
            modelRef.current.onAddTrend(pending.a, anchor);
            pendingRef.current = null;
          }
          crossRef.current = null;
          draw();
          return;
        }
        // ② 抓取已有的线（两种工具都认，不必先切工具）：把手 → 直线线身 → 水平线。
        //    顺序 = 绘制顺序的逆序（后画的在上层），把手永远最优先（最小、最难中）。
        const handle = nearestTrendHandle(trendHandles(plotW), x, y);
        if (handle) {
          mode = 'trend-anchor';
          trendIndex = handle.index;
          trendEnd = handle.end;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          canvas.style.cursor = 'grabbing';
          draw();
          return;
        }
        const body = nearestTrendBody(trendBodies(plotW), x, y);
        if (body >= 0) {
          mode = 'trend-body';
          trendIndex = body;
          trendLastX = x;
          trendLastY = y;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          canvas.style.cursor = 'move';
          draw();
          return;
        }
        const hit = hitLine(y);
        if (hit >= 0) {
          mode = 'line';
          lineIndex = hit;
          lastY = e.clientY;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          canvas.style.cursor = 'ns-resize';
          draw();
          return;
        }
        // ③ 直线工具落在空白 = 起笔：记下待定点，之后要么拖出来（endDrag 成线）要么点第二下。
        if (tool === 'trend' && anchor) {
          pendingRef.current = { a: anchor, to: null };
          mode = 'draw';
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          draw();
          return;
        }
        // ④ 水平线工具落在空白 = 候选的一击：手势照旧是平移（按住拖仍要能看图），
        //    只有**没拖出位移**的那一次松手才真的落线（见 endDrag）。
        clickPrice = priceOfY(y);
      }
      // ⑤ 副图与时间轴：只读区，不参与拖拽
      if (belowMain(y)) return;
      // ⑥ 其余 = 拖画布
      mode = 'pan';
      axis = null;
      accumX = 0;
      accumY = 0;
      lastX = e.clientX;
      lastY = e.clientY;
      pointerId = e.pointerId;
      canvas.setPointerCapture(e.pointerId);
      canvas.style.cursor = 'grabbing';
    };

    const onPointerMove = (e: PointerEvent) => {
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const n = modelRef.current.series.candles.length;
      if (mode === 'draw') {
        // 画线预览：待定点跟着鼠标走（数据空间锚点，量程自己变也不会甩开）
        const anchor = anchorAt(x, y, plotW);
        const pending = pendingRef.current;
        if (anchor && pending) {
          pendingRef.current = { a: pending.a, to: anchor };
          draw();
        }
        return;
      }
      if (mode === 'trend-body') {
        // 线身整体平移。位移按**当前映射**换算成数据空间增量：时间用像素反算
        // （别自己推「像素 / 格宽 × 周期」—— 视窗被夹住时那个公式就不成立）。
        const list = modelRef.current.series.candles;
        const ivMs = intervalMs();
        const dTime =
          timeAtIndex(list, indexOfX(x, plotW), ivMs) -
          timeAtIndex(list, indexOfX(trendLastX, plotW), ivMs);
        const dPrice = priceOfY(y) - priceOfY(trendLastY);
        trendLastX = x;
        trendLastY = y;
        modelRef.current.onMoveTrendBy(trendIndex, dTime, dPrice);
        return;
      }
      if (mode === 'trend-anchor') {
        // 端点跟着鼠标走（另一端不动）
        const anchor = anchorAt(x, y, plotW);
        if (anchor) modelRef.current.onMoveTrendAnchor(trendIndex, trendEnd, anchor);
        return;
      }
      if (mode === 'line') {
        // 价格跟着鼠标走；落盘与重画都由上层（ChartView 的 useDrawings）带回来
        modelRef.current.onMoveLine(lineIndex, priceOfY(y));
        return;
      }
      if (mode === 'pan') {
        const dx = e.clientX - lastX;
        const dy = e.clientY - lastY;
        lastX = e.clientX;
        lastY = e.clientY;
        if (axis === null) {
          accumX += dx;
          accumY += dy;
          axis = axisLock(accumX, accumY, DRAG_LOCK_PX);
          // 没定性之前两个轴都不动：斜着拖时若先动一个轴，定性一变就是一次跳变
          if (axis === null) return;
          crossRef.current = null;
        }
        if (axis === 'H') {
          const vp = clampViewport(vpRef.current, n);
          const slot = plotW / Math.max(1, vp.visibleBars);
          vpRef.current = panViewport(vpRef.current, dx / slot, n);
        } else {
          // 上下拖 = 拖拽画布：把价格刻度整体搬走，K 线跟着鼠标（缩放只留给 Shift+滚轮）。
          priceRef.current = panPriceView(
            priceRef.current,
            dy,
            modelRef.current.heights.mainH,
            currentBase(),
          );
        }
        draw();
        return;
      }
      // 空闲：直线工具下已经点下第一下了，预览的虚线要跟着鼠标走 ——
      // 没有这一段的话，「点两下画线」在点完第一下后只剩一个孤零零的把手，
      // 第二下落在哪、线会是什么斜率完全看不出来（按住拖那一路本来就有预览）。
      const tool = modelRef.current.drawTool;
      const pending = pendingRef.current;
      if (pending && tool === 'trend') {
        const anchor = anchorAt(x, y, plotW);
        if (anchor) pendingRef.current = { a: pending.a, to: anchor };
      }
      // 按落点给指针形状：抓得住的东西给 grab/move/ns-resize，空白给 crosshair（点下去会画线），
      // 主图之外不给。待定期间只有 crosshair —— 这一落点是「第二下 = 成线」，不是抓取。
      const inMain = x <= plotW && y <= mainBounds().bottom;
      let cursor = '';
      if (pending && tool === 'trend') {
        cursor = 'crosshair';
      } else if (hitBadge(e)) {
        cursor = 'pointer';
      } else if (tool && inMain) {
        if (nearestTrendHandle(trendHandles(plotW), x, y)) cursor = 'grab';
        else if (nearestTrendBody(trendBodies(plotW), x, y) >= 0) cursor = 'move';
        else if (hitLine(y) >= 0) cursor = 'ns-resize';
        else cursor = 'crosshair';
      } else if (tool === 'trend') {
        cursor = 'crosshair';
      }
      canvas.style.cursor = cursor;
      // 十字光标照常贯穿副图：竖线跨窗格、副图读数带要能读值，那不属于「拖拽/缩放」
      // `onBar` 只关竖线（右侧留白里没有蜡烛），横线与读数带照旧。
      const px = e.clientX - rect.left;
      const fraction = px / plotW;
      const index = indexAt(vpRef.current, fraction, n);
      crossRef.current = index >= 0
        ? { index, y, onBar: px <= plotW && overDataAt(vpRef.current, fraction, n) }
        : null;
      draw();
    };

    const endDrag = () => {
      if (mode === 'none') {
        clickPrice = null;
        return;
      }
      // 直线工具里的「按住拖」：拖动距离够就成线；只点了一下（或只动了几像素）
      // 就保留待定等第二下点击 —— 两种手势走同一条收口，用户不必先声明用哪种。
      if (mode === 'draw') {
        const pending = pendingRef.current;
        if (pending?.to) {
          const { plotW } = geom();
          const a = pxOfAnchor(pending.a, plotW);
          const b = pxOfAnchor(pending.to, plotW);
          if (Math.hypot(a.x - b.x, a.y - b.y) >= TREND_MIN_DRAW_PX) {
            modelRef.current.onAddTrend(pending.a, pending.to);
            pendingRef.current = null;
          } else {
            // 只点了一下（或只动了几像素）：回到「等第二下」，并把预览抹掉 ——
            // 两个几乎重合的点定出的斜率是垃圾值，留着它会在鼠标下次动之前闪一条乱线。
            pendingRef.current = { a: pending.a, to: null };
          }
        }
        draw();
      }
      // 水平线工具：一场**没拖出定性位移**（<6px）的按下-松开就是单击 → 在落点价上落一条线。
      // 拖过了就只是平移，不落线 —— 同一次手势不能既是看图又是改图。
      if (mode === 'pan' && axis === null && clickPrice !== null) {
        modelRef.current.onAddLine(clickPrice);
      }
      clickPrice = null;
      mode = 'none';
      axis = null;
      lineIndex = -1;
      trendIndex = -1;
      if (pointerId !== null && canvas.hasPointerCapture(pointerId)) {
        canvas.releasePointerCapture(pointerId);
      }
      pointerId = null;
      canvas.style.cursor = modelRef.current.drawTool ? 'crosshair' : '';
    };

    const onLeave = () => {
      if (mode !== 'none') return;
      crossRef.current = null;
      draw();
    };

    /** 双击复位：视窗与价格刻度一起回自动（App 的「双击复位」同理）。副图不参与。 */
    const onDblClick = (e: MouseEvent) => {
      const { rect } = geom();
      if (belowMain(e.clientY - rect.top)) return;
      // 选中「画图」时不复位：那两下点击各是一次画线，顺手把视窗弹回自动量程会很意外
      if (modelRef.current.drawTool) return;
      vpRef.current = initialViewport();
      priceRef.current = initialPriceView();
      crossRef.current = null;
      draw();
    };

    /**
     * Esc = 撤掉画了一半的那条直线（待定的第一下）。挂 document 而不是 canvas：
     * canvas 不可聚焦，焦点多半在别处，挂它自己一条都收不到（与搜索浮层的教训同）。
     */
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || !pendingRef.current) return;
      pendingRef.current = null;
      draw();
    };

    /**
     * 右键 = **统一删线**（两种画线工具下都一样，不必先切工具）：
     * 命中判定与抓取同序（把手 → 直线 → 水平线），落谁删谁；什么都没命中就撤掉画了一半的待定。
     *
     * 右键不再画线了（用户拍板）：左键单击就能落水平线，一根键只管一件事，
     * 用不着先猜「现在是不是画线模式」。
     *
     * 不弹自建菜单：操作本来就只有删这一个，菜单反而多一次点击；而且自绘菜单会被
     * 窗口边界裁掉（悬浮窗那边就吃过这个亏，最后走了原生 popup）。
     *
     * 没选中「画图」这一格时**什么都不做**（连 preventDefault 之外的副作用都没有）：
     * 周期/指标/副图那几格里右键就是右键，别偷偷改图。
     */
    const onContextMenu = (e: MouseEvent) => {
      e.preventDefault(); // 挡住 Chromium 自带的「另存为图片」
      const model = modelRef.current;
      if (!model.drawTool) return;
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const { top, bottom } = mainBounds();
      // 只有主图里能操作：副图、时间轴、右侧价格刻度列一律不管
      if (x > plotW || y < top || y > bottom) return;
      const handle = nearestTrendHandle(trendHandles(plotW), x, y);
      if (handle) {
        model.onRemoveTrend(handle.index);
        return;
      }
      const body = nearestTrendBody(trendBodies(plotW), x, y);
      if (body >= 0) {
        model.onRemoveTrend(body);
        return;
      }
      const hit = hitLine(y);
      if (hit >= 0) model.onRemoveLine(hit);
      else if (pendingRef.current) {
        pendingRef.current = null;
        draw();
      }
    };

    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', endDrag);
    canvas.addEventListener('pointerleave', onLeave);
    canvas.addEventListener('dblclick', onDblClick);
    canvas.addEventListener('contextmenu', onContextMenu);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', endDrag);
      canvas.removeEventListener('pointercancel', endDrag);
      canvas.removeEventListener('pointerleave', onLeave);
      canvas.removeEventListener('dblclick', onDblClick);
      canvas.removeEventListener('contextmenu', onContextMenu);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [draw]);

  return (
    <div className="chart-scroll" ref={wrapRef}>
      <canvas className="chart-canvas" ref={canvasRef} style={{ height: totalH }} />
    </div>
  );
}

function readoutNumber(value: number | undefined, decimals: number): string {
  if (value === undefined || Number.isNaN(value)) return '--';
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/** K 线详情小条：读单根 K 线（长按/悬停跟着手指，平时读最新一根）。 */
function drawCandleRow(
  ctx: CanvasRenderingContext2D,
  candles: Bar[],
  index: number,
  decimals: number,
  intervalMinutes: number,
  p: Palette,
  y: number,
) {
  const candle = candles[index];
  if (!candle) return;
  const fmt = (v: number) => readoutNumber(v, decimals);
  let cursor = READOUT_X;
  const put = (text: string, color: string) => {
    ctx.fillStyle = color;
    ctx.fillText(text, cursor, y);
    cursor += ctx.measureText(text).width + 10;
  };
  put(formatCandleTime(candle.timestamp, intervalMinutes), p.label);
  put(`O ${fmt(candle.open)}`, p.ink);
  put(`H ${fmt(candle.high)}`, p.ink);
  put(`L ${fmt(candle.low)}`, p.ink);
  put(`C ${fmt(candle.close)}`, p.ink);
  const prev = candles[index - 1];
  if (prev && prev.close !== 0) {
    const change = ((candle.close - prev.close) / prev.close) * 100;
    const sign = change >= 0 ? '+' : '';
    put(`较上根 ${sign}${change.toFixed(2)}%`, change >= 0 ? p.up : p.down);
  }
}
