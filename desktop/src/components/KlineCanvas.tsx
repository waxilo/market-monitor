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
  INTERVAL_MINUTES,
  mainRange,
  overlayFamilies,
  subRange,
  type LineRole,
  type ReadoutSegment,
  type SubPaneKind,
} from '../lib/chartSeries';
import { decimalsFor, formatCompact } from '../lib/format';

/**
 * 布局常量与 App ui/chart/KlineChart.kt 的 ChartGeo 对齐（dp → px）：
 * 价格刻度固定右侧、时间刻度固定底部；副图每块固定高、主图不被压缩，
 * 超出容器高度时整块图滚动（App 是靠页面滚动）。
 */
const AXIS_W = 58;
const TIME_AXIS_H = 18;
const READOUT_LINE = 12;
const READOUT_PAD = 6;
const READOUT_X = 8;
const SUB_PANE_H = 110;
const SUB_READOUT_H = 16;
const MAIN_MIN = 240;
const MAIN_MAX = 420;
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
  const crossRef = useRef<{ index: number; y: number } | null>(null);
  /** 复位小标上一帧的落点，供点击命中判定；不在图上时为 null。 */
  const badgeRef = useRef<{ x: number; y: number; w: number; h: number } | null>(null);
  const [availH, setAvailH] = useState(0);

  // 读数带：K 线一条 + 每个叠加线族（MA / BOLL）各一行
  const readoutRows = 1 + overlayFamilies(series).length;
  const readoutH = READOUT_PAD + READOUT_LINE * readoutRows;
  const mainH = availH > 0 ? clampNum(availH - readoutH - TIME_AXIS_H, MAIN_MIN, MAIN_MAX) : MAIN_MIN;
  const totalH = readoutH + mainH + series.subPanes.length * SUB_PANE_H + TIME_AXIS_H;

  const modelRef = useRef({ series, mainH, interval, tickSize });
  useEffect(() => {
    modelRef.current = { series, mainH, interval, tickSize };
  }, [series, mainH, interval, tickSize]);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const { series: s, mainH: mh, interval: iv, tickSize: ts } = modelRef.current;
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
    const rows = 1 + families.length;
    const mainTop = READOUT_PAD + READOUT_LINE * rows;
    const mainBottom = mainTop + mh;
    const plotBottom = mainBottom + s.subPanes.length * SUB_PANE_H;
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

    // ── 十字光标（长按/悬停）：竖线贯穿全部窗格，横线只在主图 ──
    const cross = crossRef.current;
    if (cross && cross.index >= rs && cross.index <= re) {
      const x = xOf(cross.index);
      ctx.globalAlpha = 0.7;
      ctx.strokeStyle = p.ink;
      ctx.lineWidth = 1.5;
      ctx.setLineDash([6, 6]);
      vline(ctx, x, mainTop, plotBottom);
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
    const minutes = INTERVAL_MINUTES[iv] ?? 15;
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
    const observer = new ResizeObserver(() => setAvailH(el.clientHeight));
    observer.observe(el);
    setAvailH(el.clientHeight);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    let dragging = false;
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

    /** 主图上下边界（读数带占几行由叠加线族数决定，与 draw 里同一套算法）。 */
    const mainBounds = () => {
      const s = modelRef.current.series;
      const top = READOUT_PAD + READOUT_LINE * (1 + overlayFamilies(s).length);
      return { top, bottom: top + modelRef.current.mainH };
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

    /**
     * 滚轮缩放：默认横轴（时间窗），Shift 改成纵轴（价格量程）。
     * 纵轴这一支与纵向拖拽共用同一套意图量与钳制，方向对齐「向上滚 = 放大」。
     */
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const { rect, plotW } = geom();
      crossRef.current = null;
      if (e.shiftKey) {
        // 副图区的纵向缩放与主图价格无关：落笔在主图以下就不响应
        if (e.clientY - rect.top >= mainBounds().bottom) return;
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
      if (hitBadge(e)) {
        priceRef.current = initialPriceView();
        draw();
        return;
      }
      dragging = true;
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
      const n = modelRef.current.series.candles.length;
      if (dragging) {
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
          // 起笔在副图区也一样：整块画布是一个整体，拖着没反应比拖错更难理解。
          priceRef.current = panPriceView(
            priceRef.current,
            dy,
            modelRef.current.mainH,
            currentBase(),
          );
        }
        draw();
        return;
      }
      canvas.style.cursor = hitBadge(e) ? 'pointer' : '';
      const index = indexAt(vpRef.current, (e.clientX - rect.left) / plotW, n);
      crossRef.current = index >= 0 ? { index, y: e.clientY - rect.top } : null;
      draw();
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      axis = null;
      if (pointerId !== null && canvas.hasPointerCapture(pointerId)) {
        canvas.releasePointerCapture(pointerId);
      }
      pointerId = null;
      canvas.style.cursor = '';
    };

    const onLeave = () => {
      if (dragging) return;
      crossRef.current = null;
      draw();
    };

    /** 双击复位：视窗与价格刻度一起回自动（App 的「双击复位」同理）。 */
    const onDblClick = () => {
      vpRef.current = initialViewport();
      priceRef.current = initialPriceView();
      crossRef.current = null;
      draw();
    };

    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', endDrag);
    canvas.addEventListener('pointercancel', endDrag);
    canvas.addEventListener('pointerleave', onLeave);
    canvas.addEventListener('dblclick', onDblClick);
    return () => {
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', endDrag);
      canvas.removeEventListener('pointercancel', endDrag);
      canvas.removeEventListener('pointerleave', onLeave);
      canvas.removeEventListener('dblclick', onDblClick);
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
