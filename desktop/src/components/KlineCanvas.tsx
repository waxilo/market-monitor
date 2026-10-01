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
import { nearestLineWithin, type PriceLine } from '../lib/priceLines';
import {
  clipLineToRect,
  nearestTrendBody,
  type TrendAnchor,
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
/** 两点直线：菜单起笔之后，第二次落点的最短距离。太近就当这一下还没落定，别画出退化的线。 */
const TREND_MIN_DRAW_PX = 8;
/** 待定起点的把手半径（画出来的大小；命中不用它 —— 画布上没有抓取）。 */
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
  /** 金/黄色徽标上的字（--on-accent）：黄底配纸色字在浅色主题下读不出来。 */
  onAccent: string;
  /** 用户画线（水平线 / 两点直线）：黄。告警线取 down 红、现价线取 muted 灰，三种互不串。 */
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
    onAccent: v('--on-accent') || '#1b1200',
    line: v('--line-mark') || '#f0b90b',
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

/** 画了一半的直线的起点圆点：实心 + 纸色描边（压在 K 线上也看得清）。出界的不画。 */
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

/** 右键落在什么上面 —— 决定菜单给哪几条。 */
export type ChartMenuHit =
  /**
   * 空白处：`price` 是落点反算出来的价格；`anchor` 是同一个落点的数据空间坐标
   * （「从这里画直线」的第一点就用它；图上还没有数据时为 null —— 那时没有时间锚点）。
   */
  | { kind: 'empty'; price: number; anchor: TrendAnchor | null }
  /** 一条水平线（`alert` 决定菜单里是「设成告警」还是「取消告警」）。 */
  | { kind: 'hline'; index: number; price: number; alert: boolean }
  /** 一条两点直线。 */
  | { kind: 'trend'; index: number };

/**
 * 菜单里那条「从这里画直线」落下的一次性信号。
 * `seq` 每递一次就重新起线：只带 anchor 的话，连着两次点在同一处会被 React 当成
 * 同一个 state（不触发 effect），第二次就静默没能开始。
 */
export interface TrendSeed {
  anchor: TrendAnchor;
  seq: number;
}

/** 画布请求弹右键菜单：菜单本身在 ChartView（画布只报落点，不画浮层）。 */
export interface ChartMenuRequest {
  /** 视口坐标（菜单是 `position: fixed` 的浮层，直接按光标定位）。 */
  x: number;
  y: number;
  hit: ChartMenuHit;
  /** 纵向刻度是否已被拖离自动量程 —— 决定给不给「复位纵向刻度」那一条。 */
  adjusted: boolean;
}

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
   * 用户画的水平线（价格 + 告警标记）与两点直线。状态与落盘都在 ChartView 侧（`useDrawings`），
   * 这里只负责画与命中判定（右键菜单要知道「压着哪条线」）—— 画布不做持久化。
   */
  priceLines: PriceLine[];
  trendLines: TrendLine[];
  /**
   * 菜单里「从这里画直线」落下的一次性起点；`null` = 没有画了一半的直线。
   * 换了它就把第一点落在这个锚点上，之后鼠标一动预览线跟着走、左键再落一下就成线
   * （Esc / 右键撤销）。**画布上没有别的改线手势**：新增、删除、清空全在右键菜单里，
   * 已有的线是只读的（看图时不该有任何手滑改图的机会）。
   */
  trendSeed: TrendSeed | null;
  onAddTrend: (a: TrendAnchor, b: TrendAnchor) => void;
  /** 右键落点上报（加线、删线、清空都由 ChartView 在菜单里做）。 */
  onMenu: (request: ChartMenuRequest) => void;
  /**
   * 递增一次 = 把纵向刻度退回自动量程。菜单里那条「复位纵向刻度」走的就是这个：
   * 量程意图住在画布的 ref 里，父层没有别的入口能改它。
   */
  resetPriceSignal: number;
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
  trendSeed,
  onAddTrend,
  onMenu,
  resetPriceSignal,
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
   * 画了一半的直线：起点（菜单里「从这里画直线」落下的那个锚点）与预览的当前点。
   * `to` 为空 = 还没动过（只有起点圆点），移动后才出现预览线。
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
    onAddTrend,
    onMenu,
  });
  useEffect(() => {
    modelRef.current = {
      series,
      heights,
      interval,
      tickSize,
      priceLines,
      trendLines,
      onAddTrend,
      onMenu,
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
    } = modelRef.current;
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
    // 告警线单独用红 + 点线：它是要等着被触发的，得一眼从普通划线里分出来。
    if (pl.length > 0) {
      ctx.lineWidth = 1;
      for (const line of pl) {
        const y = yOf(toFraction(mainR, line.price), mainTop, mh);
        if (y < mainTop || y > mainBottom) continue;
        ctx.strokeStyle = line.alert ? p.down : p.line;
        ctx.setLineDash(line.alert ? [1, 3] : [5, 3]);
        hline(ctx, 0, plotW, y);
      }
      ctx.setLineDash([]);
    }

    // 用户画的两点直线：**实线**（与水平线的虚线同色系、不同线型 —— 虚线读作「一个价位」、
    // 实线读作「一条斜率」）。端点不画把手：把手是「这东西能拖」的信号，现在拖不了。
    ctx.strokeStyle = p.line;
    ctx.lineWidth = 1;
    for (const line of tl) {
      const a = anchorPx(line.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      const b = anchorPx(line.b, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      infiniteLine(a.x, a.y, b.x, b.y, []);
    }

    // 画了一半的直线（菜单里「从这里画直线」之后）的预览：起点把手 + 过起点与鼠标的**虚线**
    // （预览的就是成品的样子：无限直线）
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
    if (lastY >= mainTop && lastY <= mainBottom) {
      // 最新价线走灰：它是「现在在哪儿」，不表态方向，别去跟涨跌色抢
      ctx.strokeStyle = p.label;
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
    for (const line of pl) {
      const y = yOf(toFraction(mainR, line.price), mainTop, mh);
      if (y < mainTop || y > mainBottom) continue;
      drawBadge(priceText(line.price), y, line.alert ? p.down : p.line, line.alert ? p.paper : p.onAccent);
    }
    if (lastY >= mainTop && lastY <= mainBottom) {
      drawBadge(priceText(last.close), lastY, p.label, p.paper);
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
   * 右键菜单里那条「复位纵向刻度」：父层把信号递增一次，画布就只把**纵向**意图退回自动量程
   * （时间视窗不动 —— 用户调的是刻度，不是看多宽）。0 是初始值，不做任何事。
   */
  useEffect(() => {
    if (resetPriceSignal === 0) return;
    priceRef.current = initialPriceView();
    draw();
  }, [resetPriceSignal, draw]);

  /**
   * 菜单里「从这里画直线」落下的一次性起点：把第一点落在锚点上，等下一次左键落点成线。
   * 起笔这个动作**只有菜单一个入口**（工具条上没有画图开关了），所以这里没有别的分支。
   */
  useEffect(() => {
    if (!trendSeed) return;
    pendingRef.current = { a: trendSeed.anchor, to: null };
    draw();
  }, [trendSeed, draw]);

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
     * 本场手势的种类。只剩 `pan`（拖画布）—— 线是只读的，画线一律从右键菜单发起：
     * 菜单里「从这里画直线」落下的起点在 `pendingRef`，左键那一下只是「落第二点」。
     * 种类在**按下那一刻**由落点决定：副图（以及它下面的时间轴）不参与任何手势。
     */
    type Mode = 'none' | 'pan';
    let mode: Mode = 'none';
    /**
     * 本场拖拽锁定的轴；`null` = 还没定性。
     * 定性后整场只动这一个轴 —— 上下（平移价格刻度）与左右（平移时间轴）不允许叠加。
     */
    let axis: DragAxis | null = null;
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
      nearestLineWithin(modelRef.current.priceLines.map((l) => yOfPrice(l.price)), y);

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

    /** 所有直线的两点像素（右键的线身命中用；无限直线的距离只由这两点定）。 */
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
      // 只认左键：右键走 contextmenu（弹菜单），按下时不吞掉它
      if (e.button !== 0) return;
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      if (hitBadge(e)) {
        priceRef.current = initialPriceView();
        draw();
        return;
      }
      const { top, bottom } = mainBounds();
      // 画布对已有的画线是**只读**的：没有抓手、没有拖动，落点一律按下面的规则走。
      // 唯一一种「点击改图」是画了一半的直线：菜单里「从这里画直线」之后，这一下就是第二点。
      if (pendingRef.current && x <= plotW && y >= top && y <= bottom) {
        const anchor = anchorAt(x, y, plotW);
        if (anchor) {
          const a = pxOfAnchor(pendingRef.current.a, plotW);
          // 离第一点太近：当作空点，别画出退化的线（保持待定，等下一次落点）
          if (Math.hypot(a.x - x, a.y - y) >= TREND_MIN_DRAW_PX) {
            modelRef.current.onAddTrend(pendingRef.current.a, anchor);
            pendingRef.current = null;
          }
          crossRef.current = null;
          draw();
          return;
        }
      }
      // 副图与时间轴：只读区，不参与拖拽
      if (belowMain(y)) return;
      // 其余 = 拖画布
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
      // 空闲：画了一半的直线，预览的虚线要跟着鼠标走 ——
      // 没有这一段的话，「从这里画直线」在点了菜单之后只剩一个孤零零的起点，
      // 第二下落在哪、线会是什么斜率完全看不出来。
      const pending = pendingRef.current;
      if (pending) {
        const anchor = anchorAt(x, y, plotW);
        if (anchor) pendingRef.current = { a: pending.a, to: anchor };
      }
      // 唯一可点的东西是量程复位小标；画布其余地方都是只读的十字光标（CSS 默认）。
      canvas.style.cursor = hitBadge(e) ? 'pointer' : '';
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
      if (mode === 'none') return;
      mode = 'none';
      axis = null;
      if (pointerId !== null && canvas.hasPointerCapture(pointerId)) {
        canvas.releasePointerCapture(pointerId);
      }
      pointerId = null;
      canvas.style.cursor = '';
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
      // 画了一半的直线时不复位：这两下点击里有一半是「落第二点」，顺手把视窗弹回自动量程会很意外
      if (pendingRef.current) return;
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
     * 右键 = **一律弹菜单**（用户拍板）：先按「直线线身 → 水平线 → 空白」判出落点
     * 落在谁身上，连同这一点的价格与「刻度是否已被拖离自动」一起报给 ChartView，
     * 由那边弹出浮层。删线、设告警、清空、从这里起一条直线都在菜单里做，
     * 画布自己不长菜单 —— 它只管像素，菜单要的是「这条线现在能不能动」这类父层语义。
     *
     * 只有主图里给菜单：副图、时间轴、右侧刻度列没有「这个价」的语义，右键仍是右键。
     * 画了一半的直线例外 —— 那一下右键还是「撤销这次画线」，弹菜单反而多一次点击。
     */
    const onContextMenu = (e: MouseEvent) => {
      e.preventDefault(); // 挡住 Chromium 自带的「另存为图片」
      const model = modelRef.current;
      const request = (hit: ChartMenuHit) =>
        model.onMenu({ x: e.clientX, y: e.clientY, hit, adjusted: priceAdjusted(priceRef.current) });
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const { top, bottom } = mainBounds();
      // 只有主图里能操作：副图、时间轴、右侧价格刻度列一律不管
      if (x > plotW || y < top || y > bottom) return;
      const body = nearestTrendBody(trendBodies(plotW), x, y);
      if (body >= 0) {
        request({ kind: 'trend', index: body });
        return;
      }
      const hit = hitLine(y);
      if (hit >= 0) {
        const line = model.priceLines[hit];
        request({ kind: 'hline', index: hit, price: line.price, alert: line.alert });
        return;
      }
      if (pendingRef.current) {
        pendingRef.current = null;
        draw();
        return;
      }
      const price = priceOfY(y);
      // 空白处给菜单带上数据空间锚点：「从这里画直线」要以这一落点起笔，
      // 菜单关掉之后量程/视窗可能已经变过，像素坐标不能留过夜。
      if (Number.isFinite(price)) request({ kind: 'empty', price, anchor: anchorAt(x, y, plotW) });
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
  put(`开 ${fmt(candle.open)}`, p.ink);
  put(`高 ${fmt(candle.high)}`, p.ink);
  put(`低 ${fmt(candle.low)}`, p.ink);
  put(`收 ${fmt(candle.close)}`, p.ink);
  const prev = candles[index - 1];
  if (prev && prev.close !== 0) {
    const change = ((candle.close - prev.close) / prev.close) * 100;
    const sign = change >= 0 ? '+' : '';
    put(`较上根 ${sign}${change.toFixed(2)}%`, change >= 0 ? p.up : p.down);
  }
}
