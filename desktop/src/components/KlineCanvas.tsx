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
  timeLabelPlacements,
  toFraction,
  zoomPriceView,
  zoomViewport,
  type DragAxis,
  type PriceView,
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
   * 用户画的水平线（价格）与它们的锁。状态与落盘都在 ChartView 侧
   * （`usePriceLines`），这里只负责画和手势 —— 画布不做持久化。
   */
  priceLines: number[];
  linesLocked: boolean;
  onAddLine: (price: number) => void;
  onRemoveLine: (index: number) => void;
  onMoveLine: (index: number, price: number) => void;
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
  linesLocked,
  onAddLine,
  onRemoveLine,
  onMoveLine,
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
    linesLocked,
    onAddLine,
    onRemoveLine,
    onMoveLine,
  });
  useEffect(() => {
    modelRef.current = {
      series,
      heights,
      interval,
      tickSize,
      priceLines,
      linesLocked,
      onAddLine,
      onRemoveLine,
      onMoveLine,
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
    draw();
  }, [resetKey, draw]);

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
     * 本场手势的种类。`line` = 正拖着某条水平线（只有它跟着鼠标走）。
     * 种类在**按下那一刻**由落点决定：副图（以及它下面的时间轴）不参与任何手势。
     */
    type Mode = 'none' | 'pan' | 'line';
    let mode: Mode = 'none';
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
      // 只认左键：右键走 contextmenu（画线/删线），按下时不吞掉它
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
      // ① 抓线优先：未锁住、落在主图内、离某条线 4px 以内 —— 整场只拖这一条线
      if (!modelRef.current.linesLocked && x <= plotW && y >= top && y <= bottom) {
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
      }
      // ② 副图与时间轴：只读区，不参与拖拽
      if (belowMain(y)) return;
      // ③ 其余 = 拖画布
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
      const y = e.clientY - rect.top;
      const n = modelRef.current.series.candles.length;
      if (mode === 'line') {
        // 价格跟着鼠标走；落盘与重画都由上层（ChartView 的 usePriceLines）带回来
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
      // 空闲：悬停在可拖的线上时给「能抓」的指针形状，其余交给十字光标
      const grabbable =
        !modelRef.current.linesLocked &&
        y <= mainBounds().bottom &&
        (e.clientX - rect.left) <= plotW &&
        hitLine(y) >= 0;
      canvas.style.cursor = hitBadge(e) ? 'pointer' : grabbable ? 'ns-resize' : '';
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
      lineIndex = -1;
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
      vpRef.current = initialViewport();
      priceRef.current = initialPriceView();
      crossRef.current = null;
      draw();
    };

    /**
     * 右键 = 画线 / 删线，这是水平线**唯一**的入口。
     *
     * 不弹自建菜单：一条线的全部操作就这两个，菜单反而多一次点击；而且自绘菜单会被
     * 窗口边界裁掉（悬浮窗那边就吃过这个亏，最后走了原生 popup）。
     * 落点先命中判定：落在已有线上（4px 内）就是删它，否则在落点价格上新增一条 ——
     * 于是「同一处右键两次」= 加了又删，不会叠出两根重合的线。
     *
     * 锁住时**什么都不做**（连 preventDefault 之外的副作用都没有）：这就是「锁」的意义。
     */
    const onContextMenu = (e: MouseEvent) => {
      e.preventDefault(); // 挡住 Chromium 自带的「另存为图片」
      const model = modelRef.current;
      if (model.linesLocked) return;
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const { top, bottom } = mainBounds();
      // 只有主图里能画：副图、时间轴、右侧价格刻度列一律不管
      if (x > plotW || y < top || y > bottom) return;
      const hit = hitLine(y);
      if (hit >= 0) model.onRemoveLine(hit);
      else model.onAddLine(priceOfY(y));
    };

    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', endDrag);
    canvas.addEventListener('pointerleave', onLeave);
    canvas.addEventListener('dblclick', onDblClick);
    canvas.addEventListener('contextmenu', onContextMenu);
    return () => {
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', endDrag);
      canvas.removeEventListener('pointercancel', endDrag);
      canvas.removeEventListener('pointerleave', onLeave);
      canvas.removeEventListener('dblclick', onDblClick);
      canvas.removeEventListener('contextmenu', onContextMenu);
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
