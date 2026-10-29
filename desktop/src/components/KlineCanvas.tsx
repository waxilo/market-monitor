import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Bar, OpenInterestSeries } from '../lib/api';
import {
  axisLock,
  clampNum,
  clampPriceView,
  clampViewport,
  dataWindow,
  DEFAULT_RIGHT_BLANK,
  fromFraction,
  gridLines,
  indexAt,
  initialPriceView,
  initialViewport,
  needsOlder,
  overDataAt,
  overlapShare,
  panPriceView,
  panViewport,
  plotRange,
  plotStart,
  priceAdjusted,
  priceIntentFor,
  pricePanCenteringOn,
  priceRange,
  PRICE_MIN_VISIBLE_SHARE,
  timeAtIndex,
  timeIndexOf,
  timeLabelPlacements,
  toFraction,
  viewportAdjusted,
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
  roleColorHex,
  subRange,
  type LineRole,
  type MaFamilySpec,
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
  type ChartHeights,
} from '../lib/chartLayout';
import { decimalsFor, formatCompact } from '../lib/format';
import {
  loadChartViewPrefs,
  saveChartViewPrefs,
  scheduleChartViewPrefsSave,
} from '../lib/chartViewPrefs';
import { minutesOf } from '../lib/intervals';
import { isSameLine, type DrawTool, type LineSelection } from '../lib/lineSelection';
import { nearestLineWithin, type PriceLine } from '../lib/priceLines';
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
 * `chartHeights()` 负责把可视区高度分给 主图 / 副图 / 时间轴（读数悬浮在图上、不占版面）。这里只留纯**绘制**用的。
 *
 * 与 App ui/chart/KlineChart.kt 的 ChartGeo 对齐（dp → px）：价格刻度固定右侧、时间刻度固定底部、
 * 副图每块固定高。**唯一不同的是主图会把剩余空间吃满**（有副图则让位、没副图则自己占满）
 * —— App 在手机上靠页面滚动，桌面端窗口高度固定、滚动条又被隐藏，见 chartLayout.ts 里 chartHeights 的注释。
 */
const READOUT_X = 8;
/** 悬浮读数里段与段之间的间距（与 App 的读数带同口径）。 */
const READOUT_GAP = 10;
const BODY_SHARE = 0.66;
const MAX_TIME_LABELS = 6;
const TIME_LABEL_GAP = 12;
const AXIS_MIN_GAP = 13;
const FONT = '10px ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace';
/** 画线备注的字体：比刻度大一号 —— 它是给人读的句子，不是刻度。 */
const NOTE_FONT = '11px ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace';

/** 拖拽方向定性所需的累积位移（App 用触摸的 touchSlop，网页鼠标取固定值）。 */
const DRAG_LOCK_PX = 6;
/** 视角偏离默认取景时的复位小标（刻度被调、或往前拖看历史都会出现）。 */
const RESET_BADGE_TEXT = '复位';
const RESET_BADGE_H = 16;
/** 两点直线：起笔之后，第二次落点的最短距离。太近就当这一下还没落定，别画出退化的线。 */
const TREND_MIN_DRAW_PX = 8;
/** 待定起点的把手半径（画出来的大小；命中不用它 —— 画布上没有抓取）。 */
const TREND_HANDLE_R = 3.5;

/**
 * 骨架网格的量程：**任取一段等距区间**即可 —— 网格的条数与间距由 `gridLines` 的 1-2-5 阶梯
 * 决定，取 (0,1) 恰好落成 6 条等距横线，就是一张「还没有数据的图」该有的样子。
 * 数值本身不参与任何绘制（骨架不画刻度数字），与 App ChartSkeleton 的 SKELETON_RANGE 同值。
 */
const SKELETON_RANGE: Range = { low: 0, high: 1 };

interface Palette {
  up: string;
  down: string;
  grid: string;
  label: string;
  ink: string;
  paper: string;
  wash: string;
  edge: string;
  /** 金/黄色徽标上的字（--on-accent）：黄底配纸色字在浅色主题下读不出来。 */
  onAccent: string;
  /**
   * 用户画线（水平线 / 两点直线）：黄。告警线取 down 红、现价线取 muted 灰，三种互不串。
   * **三种线型一律实线**，区分只在颜色（对齐 App：红＝会响的告警、黄＝只是记号的画线）。
   */
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
    onAccent: v('--on-accent') || '#1b1200',
    line: v('--line-mark') || '#f0b90b',
  };
}

function roleColor(role: LineRole, p: Palette): string {
  switch (role) {
    case 'UP':
      return p.up;
    case 'DOWN':
      return p.down;
    case 'LABEL':
      return p.label;
    default:
      // 其余都是调色板色位（MA 八位 / EMA 八位 / BOLL 三位），由 chartSeries 统一解析
      return roleColorHex(role) ?? p.label;
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

/**
 * 画布指针**只有两种形态**（2026-10-26 用户）：
 * `''` = 空心圆环（CSS 里 `.chart-canvas` 的默认指针）；
 * `'on-line'` = 四个直角拼成的十字（压在画线上 / 拖线时，见 theme.css 的 `.chart-canvas.is-on-line`）。
 * 复位小标、拖画布、拖线都不再切系统的 pointer / grabbing / move / ns-resize。
 */
type CursorKind = '' | 'on-line';

/**
 * 画布指针的**唯一出口**：`on-line` 靠 CSS 类，其余走内联 `style.cursor`。
 *
 * 位图 cursor 的 data URI 只待在 theme.css 里（TS 里再抄一份就会两份真相）；而 CSS 类有个
 * 反过来的坑：`endDrag` / `onLeave` 都会把内联样式重置成 `''`，**类若没摘掉**，那枚四角角标
 * 会借着这次重置重新冒出来、盖住本该出现的圆环。所以「切类」与「设样式」必须同一处做，
 * 谁都不许单独写 `canvas.style.cursor`。
 */
function applyCursor(canvas: HTMLCanvasElement, kind: CursorKind) {
  canvas.classList.toggle('is-on-line', kind === 'on-line');
  canvas.style.cursor = '';
}

/**
 * 骨架：**还没有数据时先摆好的那张空图**，只有网格与副图分隔线。
 *
 * 与真实图共用 `chartHeights` 的那套几何（主图占多少、每块副图落在哪），
 * 所以它是「这一张图还没填上蜡烛」，而不是另开一张图 —— 数据到位的瞬间只有蜡烛浮现，
 * 版面本身不重排（App 的 ChartSkeleton 是同一思路）。
 *
 * 三条刻意的「不画」：
 * - **不画刻度数字**：还不知道任何价位，画出来只能是编的；
 * - **不画蜡烛 / 均线 / 读数**：没有数据时读数那几行全是 `--`，比留白更吵；
 * - **不画最新价与时间刻度**：没有数据就没有「最新」。
 *
 * 网格量程固定用 `SKELETON_RANGE`，**不吃用户的缩放 / 平移意图** —— 那两个意图是相对量，
 * 套在一段假的量程上只会得到一堆没有意义的线。
 */
function drawSkeleton(
  ctx: CanvasRenderingContext2D,
  p: Palette,
  plotW: number,
  gh: ChartHeights,
  paneCount: number,
) {
  const { mainTop, mainH, mainBottom } = gh;
  // 网格用**重一档**的发丝线（`--hairline-strong`）而不是常规的 `--hairline`：
  // 它上面还盖着一层半透明遮罩，再淡一档就洗没了（浅色主题实测对比度只剩 3.5%，
  // 观感退回「一块空白 + 转圈」）。加重之后透过遮罩看，浓淡正好与真实图的网格相当。
  ctx.strokeStyle = p.edge;
  ctx.lineWidth = 1;
  ctx.setLineDash([]);
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, mainTop, plotW, mainH);
  ctx.clip();
  for (const value of gridLines(SKELETON_RANGE)) {
    const y = mainTop + toFraction(SKELETON_RANGE, value) * mainH;
    if (y >= mainTop - 0.5 && y <= mainBottom + 0.5) hline(ctx, 0, plotW, y);
  }
  ctx.restore();
  // 副图分隔线：每块副图的上边界，与真实图同一式
  for (let k = 0; k < paneCount; k++) {
    hline(ctx, 0, plotW, mainBottom + k * SUB_PANE_H + SUB_READOUT_H);
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
 * 画线的备注标签：**一块串在线上的实色牌 + 高反差字**（照右轴那枚价签做）。
 *
 * 位置：牌的**竖向中心压在线的 y 上**（用户原话「串在画的线上，不再画线上方展示」）。
 * 牌是实心的，画在线之后 ⇒ 线在牌底下被盖住、两侧照旧延伸，看着就是「线从牌里穿过去」。
 * 贴顶/贴底时把牌整体夹回绘图区（`top`/`bottom`），否则半行字会被 canvas 裁掉。
 *
 * 为什么不是给字描一圈边（halo）：11px 的等宽字笔画不到 1px 宽，3px 的描边会把字
 * 整个吃掉 —— 浅色主题下看着是一团灰、深色主题下干脆看不见（两种都实测过）。
 *
 * 为什么也不是「纸色半透明底板」：纸色跟图底同一个颜色，底板等于没底 —— 压在密集
 * K 线、均线、网格上的备注，字被底下的东西拖着读不清（用户截图反馈「容易被背景影响」）。
 * 现在牌面取**线的语义色**（黄＝普通画线 / 红＝告警），字取该色上的反差色，
 * 与右轴价签同色同尺寸（`drawBadge`）—— 一眼读作「这条线的标签」，背景是什么都不影响。
 *
 * 横向夹进绘图区：备注是长句，贴着右边缘时会整段跑到轴外面去（canvas 不报错，用户只看到
 * 「备注没了」）。
 */
function drawLineNote(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  color: string,
  bg: string,
  plotW: number,
  top: number,
  bottom: number,
) {
  if (!text) return;
  const padX = 4;
  const h = 14;
  ctx.save();
  ctx.font = NOTE_FONT;
  ctx.textBaseline = 'middle';
  const w = ctx.measureText(text).width + padX * 2;
  const nx = clampNum(x - padX, 2, Math.max(2, plotW - w - 2));
  const ny = clampNum(y, top + h / 2, Math.max(top + h / 2, bottom - h / 2));
  // **牌面照右轴那枚价签做**：实色块 + 高反差字，不是「纸色半透明」——
  // 纸色底板跟图底本来就同一个颜色，压在密集 K 线/网格上时字被底下的东西拖着读不清
  // （用户反馈「容易被背景影响」）。底色即线的语义色（黄＝普通画线 / 红＝告警），
  // 与右轴价签同色同尺寸，读起来就是「这条线的标签」。
  ctx.globalAlpha = 1;
  ctx.fillStyle = bg;
  roundRect(ctx, nx, ny - h / 2, w, h, 3);
  ctx.fillStyle = color;
  ctx.fillText(text, nx + padX, ny + 0.5);
  ctx.restore();
}

/**
 * 数据空间锚点 → 主图像素（两点直线的绘制与命中判定**共用这一处**换算）。
 * 与 draw 里的 `xOf`/`yOf` 严格同式：x 走 `(i - start + 0.5) * slot`、y 走
 * `top + toFraction(量程, 价格) * 高` —— 两处各写一份必然会分叉（曾经手写过就分叉）。
 */function anchorPx(
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

/*
 * 画布**不再**往外报「右键落在什么上面」—— 右键菜单已整档移除（2026-10-24）。
 * 落点命中的判定留在画布内部：命中谁就 `onSelect` 谁，改哪条线由 ChartView 的划线区按钮决定。
 */

interface Props {
  candles: Bar[];
  /**
   * 主图均线族（MA / EMA 各一份「画哪几档 + 各自什么线色」）。
   * 已经「藏起来」的族由调用方折算成空 `periods` —— 画布不管显隐开关，只画给它的东西。
   */
  families: MaFamilySpec[];
  showBoll: boolean;
  subPanes: SubPaneKind[];
  /**
   * 历史持仓量（原始时间点，没对齐）。`null` = 没有（没选 OI、源不支持、或还没取回来）——
   * 那时 OI 副图整块不出。对齐（前向填充 + 可能的折美元）在 `chartSeries` 里做。
   */
  openInterest: OpenInterestSeries | null;
  interval: string;
  tickSize?: string;
  theme: string;
  /**
   * 数据还没到（换标的 / 换周期 / 换数据源之后的那一段）。为真时在画布上盖一层遮罩
   * （半透明 + 转圈 + 「加载中」），画布自己则在画**骨架**（见 draw 里 `n === 0` 的分支）。
   * 两层合起来才是完整观感：骨架保证版面不变，遮罩说清楚「正在取数」。
   */
  loading: boolean;
  /**
   * 标的身份（`market|symbol`）。它与 [interval] 任一变化都作废**过程状态**（十字光标、
   * 画了一半的直线）并把纵向位置重定到最新价（缩放倍数不动）；
   * 只有它变（换标的 = 重开详情）才把横向位置拉回最新价（缩放同样不动）。
   */
  symbolKey: string;
  /**
   * 用户画的水平线（价格 + 告警标记）与两点直线。状态与落盘都在 ChartView 侧（`useDrawings`），
   * 这里只负责画、命中判定与拖动 —— 画布不做持久化。
   */
  priceLines: PriceLine[];
  trendLines: TrendLine[];
  /**
   * 划线区里选中的线型（`none` = 没在画）。
   *
   * 工具激活期间左键落点就是「画」，不会去选线；水平线落一次即成，
   * 两点直线落两次（第一次起笔、第二次成线，Esc 撤掉起笔）。
   */
  tool: DrawTool;
  /**
   * 当前选中的线（`null` = 没选中）。**只有选中的线能拖** —— 选中是「我在改这一条」的凭据，
   * 也是划线区切换成编辑按钮条的开关（见 ChartView）。
   */
  selection: LineSelection | null;
  /** 点图上报：命中一条线就选中它，落空就取消选中（`null`）。 */
  onSelect: (selection: LineSelection | null) => void;
  onAddTrend: (a: TrendAnchor, b: TrendAnchor) => void;
  /** 工具落点划出一条水平线（价位由画布按落点反算，精度不在画布这侧定）。 */
  onAddPriceLine: (price: number) => void;
  /** 一条线画完了：让划线区把工具收回去（一次一个动作，别让它一直举着）。 */
  onDrawn: () => void;
  /** 整张图的画线是否锁定（默认解锁）：锁上后画布不抓线、不画把手，拖动落点一律还给「拖画布」。 */
  linesLocked: boolean;
  /** 拖动回报（状态与落盘都在 ChartView 的 useDrawings）：水平线改价、直线整体平移、直线端点。 */
  onMovePriceLine: (index: number, price: number) => void;
  onMoveTrendBy: (index: number, deltaTime: number, deltaPrice: number) => void;
  onMoveTrendAnchor: (index: number, end: TrendEnd, anchor: TrendAnchor) => void;
  /**
   * 时间轴快够到最老一根了（还剩约一屏，见 OLDER_MARGIN_*）—— 请上层补一页更早的。
   * 画布只负责上报「到墙了没」；去重、在飞、失败退避都在 ChartView（它才知道当前标的是谁）。
   */
  onNeedOlder: () => void;
}

export function KlineCanvas({
  candles,
  families,
  showBoll,
  subPanes,
  openInterest,
  interval,
  tickSize,
  theme,
  loading,
  symbolKey,
  priceLines,
  trendLines,
  tool,
  selection,
  onSelect,
  onAddTrend,
  onAddPriceLine,
  onDrawn,
  linesLocked,
  onMovePriceLine,
  onMoveTrendBy,
  onMoveTrendAnchor,
  onNeedOlder,
}: Props) {
  const series = useMemo(
    () => buildSeries(candles, families, showBoll, subPanes, openInterest),
    [candles, families, showBoll, subPanes, openInterest],
  );
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  /**
   * 时间轴视窗与价格量程（缩放倍数 + 平移量，1 / 0 = 自动量程）—— 两枚「用户意图」。
   *
   * 横纵**缩放**全局记住（mm.chartView，见 lib/chartViewPrefs）：切周期、换标的、重启都带着走。
   * 横向**位置**（rightOffset）不落盘：首帧与换标的（= 重开详情）都回到最新价；切周期保持。
   * 纵向**位置**（pan）在换 (标的, 周期) / 首次进入后重定到「最新价居中」、倍数不动
   * （见 recenter effect）—— 旧位置套到新序列上可能把现价顶出量程。
   * 越界由 chartMath 的钳制在用时兜底，存储里保留原始意图。
   */
  const vpRef = useRef<Viewport>({
    visibleBars: loadChartViewPrefs().visibleBars,
    rightOffset: DEFAULT_RIGHT_BLANK,
  });
  const priceRef = useRef<PriceView>(loadChartViewPrefs().price);
  /**
   * 拖拽期间冻结的纵向基准量程（手势开始那一刻按当前窗口算出来的 `mainRange`）。
   *
   * 拖动时可见窗口每帧都在变；基准若跟着重算，自动量程就会**逐帧重排** ——
   * 画面上 K 线一边横移一边上下缩放，正是用户报的「拖到历史时 K 线跳动」。
   * 冻结之后价格轴整场手势不动；横向平移松手时再把这段取景落成相对新窗口的意图
   * （见 `repinPriceAfterPan`），松手瞬间也不会跳。与 App KlineChart 的 `priceBase` 同口径。
   */
  const panBaseRef = useRef<Range | null>(null);
  /**
   * 纵轴重定的挂起标记：symbolKey / interval 一变就立起，等新序列带着有限现价落地
   * 才在 recenter effect 里清掉并重算 —— 序列空的时候现价还不存在，先算只会锚错
   * （与 App KlineChart.kt 里 snapshotFlow 等首个非空序列同一手法）。
   */
  const recenterPendingRef = useRef(false);
  /**
   * 十字光标。`index` 是**夹回序列内**的下标（读数带、横线与价格标都按它走），
   * `onBar` 则表示指针是不是真的压在某根蜡烛的格子上 —— 竖线与顶部读数带只看这一个开关，
   * 因为视窗右侧还有一段没有蜡烛的留白（默认 3 根）。
   */
  const crossRef = useRef<{ index: number; y: number; onBar: boolean } | null>(null);
  /** 复位小标上一帧的落点，供点击命中判定；不在图上时为 null。 */
  const badgeRef = useRef<{ x: number; y: number; w: number; h: number } | null>(null);
  /**
   * 画了一半的直线：起点（划线区选了「直线」后的第一次落点）与预览的当前点。
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

  /**
   * 纵向几何一律由 chartLayout 分配 —— 副图占的高度在那里被一起减掉了。
   * 读数不参与分配（悬浮在主图上），所以主图从画布顶端开始画。
   * 之前这里手写 `clamp(avail - readout - TIME_AXIS_H)` 漏掉副图，导致第三块副图
   * 被挤出可视区（见 chartLayout.ts 的 chartHeights 注释）。
   */
  const heights = chartHeights(box.h, series.subPanes.length);
  const { totalH } = heights;

  /**
   * 绘制与手势共用的一份「最新值」。
   *
   * 事件监听只在挂载时绑一次（见下面手势 effect 的依赖），闭包里拿不到新 props，
   * 所以一律从这里读；同步 effect **不写依赖数组**，保证每轮渲染后都是最新的，
   * 且声明在绘制 effect 之前（effect 按声明顺序跑，先同步再画）。
   *
   * 抽成一个对象字面量再赋值，而不是两处各抄一遍字段清单 —— 漏一个字段的表现是
   * 「某个交互始终读到旧值」，排查成本远高于这里多一个变量。
   */
  const liveModel = {
    series,
    heights,
    interval,
    tickSize,
    priceLines,
    trendLines,
    tool,
    selection,
    linesLocked,
    onSelect,
    onAddTrend,
    onAddPriceLine,
    onDrawn,
    onMovePriceLine,
    onMoveTrendBy,
    onMoveTrendAnchor,
    onNeedOlder,
  };
  const modelRef = useRef(liveModel);
  useEffect(() => {
    modelRef.current = liveModel;
  });

  /**
   * 「该补更早的历史了」上报 —— 判据本体在 `chartMath.needsOlder`（与 App 同一套口径：
   * 视窗左缘进入一屏提前量即算，**不看是哪条手势把视窗挪过去的**）。
   *
   * 调用点只有 [draw] 的尾巴一处（见那里的说明）：平移、滚轮缩放、复位、尺寸变化、
   * 恢复上次视角最终都会重画，挂在重画之后就不存在「某条入口忘了上报」。
   * 去重、在飞、失败退避都在 ChartView 侧收口。
   */
  const reportOlder = useCallback(() => {
    if (needsOlder(vpRef.current, modelRef.current.series.candles.length)) {
      modelRef.current.onNeedOlder();
    }
  }, []);

  /**
   * 数据变了（补到更早的一页 / 换标的）再查一次是否到墙 —— 补页落地后视窗没动，
   * 「更早的根数」却变多了：若视窗比补回来的还宽，要立刻继续要下一页。
   */
  useEffect(() => {
    reportOlder();
  }, [series, reportOlder]);

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
      linesLocked: lock,
      selection: sel,
    } = modelRef.current;
    const mh = gh.mainH;
    const list = s.candles;
    const n = list.length;
    const cssW = canvas.clientWidth;
    const cssH = canvas.clientHeight;
    // 尺寸还没量到（首帧、面板收起中）就什么都不画。注意**「没有数据」不在此列** ——
    // 那时要出骨架，见下面 n === 0 的分支（这里返早会让换周期的那段变成一块全白）。
    if (cssW <= 0 || cssH <= 0) return;

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

    /**
     * 数据还没到（换标的 / 换周期 / 换数据源之后的那一段）：画**骨架**。
     * 组件侧此时挂着一层遮罩（`.chart-loading`），两者合起来才是完整观感 ——
     * 骨架负责「版面不变」，遮罩负责「说清楚正在取数」。
     */
    if (n === 0) {
      drawSkeleton(ctx, p, plotW, gh, s.subPanes.length);
      return;
    }

    const families = overlayFamilies(s);
    // 各段的纵向落点统一取自 heights：主图与副图的边界只有一处定义
    const mainTop = gh.mainTop;
    const mainBottom = gh.mainBottom;
    const plotBottom = mainBottom + gh.subPanesH;

    const vp = clampViewport(vpRef.current, n);
    const [ws, we] = dataWindow(vpRef.current, n);
    // 纵向基准：手势期间取冻结值（见 panBaseRef），平时只由可见窗口决定 ——
    // 用户的纵向缩放/平移是叠在它之上的用户意图
    const rangeBase = panBaseRef.current ?? mainRange(s, ws, we);
    const mainR = priceRange(rangeBase, priceRef.current);
    const subRs = s.subPanes.map((pane) => subRange(pane, ws, we));
    const [rs, re] = plotRange(vpRef.current, n);
    const start = plotStart(vpRef.current, n);
    const slot = plotW / Math.max(1, vp.visibleBars);
    const xOf = (i: number) => (i - start + 0.5) * slot;
    const yOf = (fraction: number, top: number, height: number) => top + fraction * height;
    // 实体随槽宽等比。曾钳 26px 上限：放大/短序列撑满后实体不再变宽、间距继续拉大，
    // 就是「实体偏窄、间距巨大」那个散开观感的来源（2026-10-03 去上限）。
    const bodyW = Math.max(1, slot * BODY_SHARE);
    const dec = decimalsFor(ts);
    /** 一根 K 线的时长（直线锚点的时间 ⇄ 下标换算用）。 */
    const ivMs = (minutesOf(iv) ?? 15) * 60_000;
    /**
     * 过两点画**无限直线**并裁到主图。
     * 直线是「两点定一条线」的语义：向两端延伸、到绘图区边界才断，不是 A-B 线段。
     * 线型恒为实线（与水平线同一口径，见下面水平线那段）：一笔画下去就是一条实线，
     * 不存在「预览虚线、落定实线」两套样子。
     */
    const mainRect = { left: 0, top: mainTop, right: plotW, bottom: mainBottom };
    const infiniteLine = (fromX: number, fromY: number, toX: number, toY: number) => {
      const seg = clipLineToRect(fromX, fromY, toX, toY, mainRect);
      if (!seg) return;
      ctx.beginPath();
      ctx.moveTo(seg.x1, seg.y1);
      ctx.lineTo(seg.x2, seg.y2);
      ctx.stroke();
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

    ctx.lineWidth = 1.5;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    for (const line of s.overlayLines) {
      ctx.strokeStyle = roleColor(line.role, p);
      strokeLine(ctx, line.values, rs, re, xOf, (i) =>
        yOf(toFraction(mainR, line.values[i]), mainTop, mh),
      );
    }

    // 下面这一整段（水平线 / 两点直线 / 半截直线预览）**一律实线**：画线不靠线型表达语义，
    // 所以进段先清掉任何残留的 dash —— 别让上面某个绘制分支的虚线渗下来。
    ctx.setLineDash([]);

    // 用户画的水平线：**实线**横贯主图，压在 K 线/均线之上、最新价虚线之下
    // （最新价是行情本身，任何时候都不该被遮）。价格标在下面与最新价标一起画。
    //
    // 线型只有一种（实线），语义全部交给颜色：告警线红（它要等着被触发，得一眼从普通
    // 划线里分出来）、普通划线黄（与两点直线同色系）。早期靠「点线 / 虚线」区分告警，
    // 但在密集 K 线上虚线断成一串点、反而不如实线好瞄 —— 现在红＝会响、黄＝只是记号。
    /**
     * 线备注牌：先记下来、等这一层所有线（连最新价那条虚线也算）画完再统一「盖章」。
     * 牌是实心的、牌心就压在线 y 上 ⇒ 线在牌底下断成两截，看着就是线从牌里穿过去；
     * 所以**任何线都不许从牌面上穿过去**，只能在所有线之后画。
     */
    const noteChips: { text: string; x: number; y: number; fg: string; bg: string }[] = [];
    /** 选中的那条线：加粗 + 一层同色光晕（不换色 —— 红/黄本身就是语义，选中只是临时状态）。 */
    const selPrice = sel?.kind === 'price' ? sel.index : -1;
    const selTrend = sel?.kind === 'trend' ? sel.index : -1;
    if (pl.length > 0) {
      pl.forEach((line, index) => {
        const y = yOf(toFraction(mainR, line.price), mainTop, mh);
        if (y < mainTop || y > mainBottom) return;
        const color = line.alert ? p.down : p.line;
        const selected = index === selPrice;
        if (selected) {
          ctx.globalAlpha = 0.22;
          ctx.strokeStyle = color;
          ctx.lineWidth = 7;
          hline(ctx, 0, plotW, y);
          ctx.globalAlpha = 1;
        }
        ctx.strokeStyle = color;
        ctx.lineWidth = selected ? 2 : 1;
        hline(ctx, 0, plotW, y);
        // 备注牌串在线的左端（图上一眼从左边读起），牌面与右轴那枚价签同一套：
        // 告警线红底纸色字、普通画线黄底深字。
        if (line.note) {
          noteChips.push({
            text: line.note,
            x: 8,
            y,
            fg: line.alert ? p.paper : p.onAccent,
            bg: color,
          });
        }
      });
    }

    // 用户画的两点直线：**实线**、与水平线同色（黄）。两者只差形状 —— 横线读作「一个价位」、
    // 斜线读作「一条斜率」，颜色不必再分工。端点把手**只画在选中的那条上**：把手是
    // 「这条线现在能拖」的信号，没选中就没有能拖的理由（0.1.x 起先选中、后拖动）。
    ctx.strokeStyle = p.line;
    ctx.lineWidth = 1;
    tl.forEach((line, index) => {
      const a = anchorPx(line.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      const b = anchorPx(line.b, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      const selected = index === selTrend;
      if (selected) {
        ctx.globalAlpha = 0.22;
        ctx.strokeStyle = p.line;
        ctx.lineWidth = 7;
        infiniteLine(a.x, a.y, b.x, b.y);
        ctx.globalAlpha = 1;
      }
      ctx.strokeStyle = p.line;
      ctx.lineWidth = selected ? 2 : 1;
      infiniteLine(a.x, a.y, b.x, b.y);
      // 备注挂在**看得见的那个锚点**旁：A 被判出绘图区（线画到未来/过去很远）就换 B ——
      // 否则「给这条直线写了备注但图上找不到」。
      if (!line.note) return;
      const inView = (pt: { x: number; y: number }) =>
        pt.x >= 0 && pt.x <= plotW && pt.y >= mainTop && pt.y <= mainBottom;
      const spot = inView(a) ? a : inView(b) ? b : null;
      if (!spot) return;
      // 牌心压在锚点所在的那条斜线上（往右让开一点，别压住把手）
      noteChips.push({ text: line.note, x: spot.x + 8, y: spot.y, fg: p.onAccent, bg: p.line });
    });
    ctx.lineWidth = 1;
    const selectedTrend = selTrend >= 0 ? tl[selTrend] : undefined;
    if (!lock && selectedTrend) {
      for (const anchor of [selectedTrend.a, selectedTrend.b]) {
        const pt = anchorPx(anchor, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
        drawTrendHandle(ctx, pt.x, pt.y, p, mainTop, mainBottom, plotW);
      }
    }

    // 画了一半的直线（划线区选了「直线」、已经落了第一个点）的预览：起点把手 + 过起点与鼠标的
    // **实线**（预览的就是成品的样子：一条黄色的无限直线）
    const pending = pendingRef.current;
    if (pending) {
      const a = anchorPx(pending.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      if (pending.to) {
        const b = anchorPx(pending.to, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
        infiniteLine(a.x, a.y, b.x, b.y);
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

    // 备注牌**最后盖章**：牌心压在线 y 上，实心牌把线盖掉一截 ⇒ 线看着从牌里穿过去。
    // 放在最新价虚线之后，是为了让牌面永远干净（牌上不许有别的线划过去）。
    for (const chip of noteChips) {
      drawLineNote(ctx, chip.text, chip.x, chip.y, chip.fg, chip.bg, plotW, mainTop, mainBottom);
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
          // 成交量与持仓量都是大额（几亿起步），走 K/M/B 缩写；其余指标的小数刻度照旧
          pane.kind === 'VOLUME' || pane.kind === 'OI' ? formatCompact(value) : value.toFixed(2),
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

    // ── 十字光标的日期标 ──
    // 与横线的价格标成对：两条线各答一个问题（哪个时间 / 哪个价位），不必自己数到轴上去。
    // 显示条件与竖线一致（`onBar`）：右侧留白里 `indexAt` 会把位置夹回最后一根，
    // 那时标出来的日期不属于鼠标下那一点。画在时间轴刻度之后 —— 压住同位的刻度文字。
    if (cross && cross.onBar && cross.index >= rs && cross.index <= re) {
      const c = list[cross.index];
      if (c) {
        const text = formatCandleTime(c.timestamp, minutes);
        const w = ctx.measureText(text).width + 8;
        const h = 14;
        // 以竖线为心中、再夹进绘图区：贴着左右边缘时标会被切掉半个字
        const x = clampNum(xOf(cross.index) - w / 2, 0, Math.max(0, plotW - w));
        const y = plotBottom + (TIME_AXIS_H - h) / 2;
        ctx.fillStyle = p.ink;
        roundRect(ctx, x, y, w, h, 3);
        ctx.fillStyle = p.paper;
        ctx.fillText(text, x + 4, y + h / 2 + 0.5);
      }
    }

    // ── 悬浮读数（主图左上角）──
    // 不占版面：主图从画布顶端开始画，读数叠在图上；只在指针压在某根蜡烛上时出现
    // （与竖线同一个 `onBar` 判据）。底下垫一层 86% 纸色的圆角薄垫，压在蜡烛 /
    // 布林线上也读得清 —— 无描边、无阴影，它是浮在图上的一块读数，不是卡片。
    const idx = cross ? clampNum(cross.index, 0, n - 1) : n - 1;
    if (cross && cross.onBar && cross.index >= rs && cross.index <= re) {
      const rows: TextSegment[][] = [candleRowSegments(list[idx], list[idx - 1], dec, minutes, p)];
      for (const family of families) {
        rows.push(
          family.map((line) => ({
            text: `${line.label}: ${readoutNumber(line.values[idx], dec)}`,
            color: roleColor(line.role, p),
          })),
        );
      }
      const padX = 6;
      const padY = READOUT_PAD / 2;
      const plateX = READOUT_X - padX;
      const plateY = mainTop + 4;
      const plateW = Math.min(
        Math.max(...rows.map((row) => textSegmentsWidth(ctx, row))) + padX * 2,
        plotW - plateX,
      );
      const plateH = padY * 2 + READOUT_LINE * rows.length;

      ctx.save();
      ctx.beginPath();
      ctx.rect(0, plateY, plateX + plateW, plateH);
      ctx.clip();
      ctx.globalAlpha = 0.86;
      ctx.fillStyle = p.paper;
      roundRect(ctx, plateX, plateY, plateW, plateH, 6);
      ctx.globalAlpha = 1;
      rows.forEach((row, r) => {
        drawTextSegments(ctx, row, READOUT_X, plateY + padY + READOUT_LINE * r + READOUT_LINE / 2);
      });
      ctx.restore();
    }
    s.subPanes.forEach((pane, k) => {
      drawSegments(
        ctx,
        pane.readoutAt(idx, dec),
        READOUT_X,
        mainBottom + k * SUB_PANE_H + SUB_READOUT_H / 2,
        p,
      );
    });

    // ── 「复位」小标 ──
    // 视角一旦被调离默认取景就没法从图上看出来（刻度拖远了 / 往前翻到历史里），
    // 点它 = `resetView`：横纵缩放回默认 + 回到最新价。
    // 放绘图区**左下角**：右侧压着最新一根蜡烛、顶部压着读数带，左下角信息密度最低。
    if (priceAdjusted(priceRef.current) || viewportAdjusted(vpRef.current)) {
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

    // ── 视窗落定 → 判一次要不要更早的历史 ──
    // 挂在重画的尾巴而不是散在 wheel / pointermove 里：所有改变视窗的路径最终都会走到
    // 这里（滚轮缩放、拖动平移、点「复位」小标、容器尺寸变化、恢复上次视角），
    // 少一处就少一条补页入口 —— 「一直缩小不刷新」正是漏在这类入口上。
    // 判据本身很便宜（几次浮点比较），真发不发请求由 ChartView 的门闸决定。
    reportOlder();
  }, [reportOlder]);

  /**
   * 视角落盘：拖拽松手、复位这类「一次性动作」立即写；滚轮连续缩放走防抖
   * （停手才写，取最后的意图），避免每个滚轮事件都打一次 localStorage。
   */
  const persistView = useCallback((debounced = false) => {
    if (debounced) scheduleChartViewPrefsSave(vpRef.current, priceRef.current);
    else saveChartViewPrefs(vpRef.current, priceRef.current);
  }, []);

  /**
   * 横向平移松手：把**手势期间显示的绝对量程**落成相对新窗口的意图。
   *
   * 不这么做的话，`panBaseRef` 一清空纵向基准就切回新窗口的自动量程，松手瞬间
   * 整张 K 线上下跳一下 —— 与 App 同一处修正（见 KlineChart.kt 的 `repinPriceRange`）。
   * 落成意图之后取景与松手前逐像素一致；想回自动适配仍走「复位」小标。
   */
  const repinPriceAfterPan = useCallback((frozenBase: Range) => {
    const s = modelRef.current.series;
    const n = s.candles.length;
    if (n <= 0) return;
    const [ws, we] = dataWindow(vpRef.current, n);
    const base = mainRange(s, ws, we);
    const shown = priceRange(frozenBase, priceRef.current);
    // 新窗口的数据基本滑出冻结量程（< 25% 可见，与纵向平移的收口同一条线）：
    // 硬钉下去就是一张空图，退回自动适配 —— 宁可跳一下，也不能没 K 线。
    if (overlapShare(base, shown) < PRICE_MIN_VISIBLE_SHARE) return;
    const intent = priceIntentFor(shown, base);
    if (intent) priceRef.current = intent;
  }, []);

  /**
   * 纵轴重定（换周期 / 换标的 / 首次进入后）：key 变时挂起（见下方 key effect），
   * 新序列一就绪就把纵向**位置**对到最新价，**缩放倍数原样保留**（2026-10-26 用户：
   * 切周期、重开详情要记住纵向缩放）。旧实现把倍数也重算成「罩住全部蜡烛」，
   * 等于每次切周期都把纵向缩放抹掉。钳制口径见 `pricePanCenteringOn`（同 App
   * `ValueRange.panCenteringOn`）：`zoom = 1` 时恒为 0，不会裁掉半屏蜡烛。
   *
   * 量程按当前序列+视口现算（`mainRange`），不读绘制帧缓存 —— 序列从空变满的那一刻
   * 读缓存会拿到空序列的 (0,1) 兜底，居中会锚错。
   * 不调 draw：本 effect 声明在绘制 effect 之前，同一轮渲染里紧随其后的 draw() 自然用上。
   */
  useEffect(() => {
    if (!recenterPendingRef.current) return;
    const list = series.candles;
    const close = list[list.length - 1]?.close;
    if (close === undefined || !Number.isFinite(close)) return;
    const [ws, we] = dataWindow(vpRef.current, list.length);
    const base = mainRange(series, ws, we);
    if (!(base.high - base.low > 0)) return;
    recenterPendingRef.current = false;
    const zoom = priceRef.current.zoom;
    priceRef.current = clampPriceView({ zoom, pan: pricePanCenteringOn(base, close, zoom) }, base);
    persistView();
  }, [series, persistView]);

  // 数据 / 尺寸 / 主题任一变化都重画；无依赖写法保证「每次渲染后」都会同步一次
  useEffect(() => {
    draw();
  });

  // 主题属性由 App 的 effect 写入（子 effect 先跑），下一帧再取一次色才准
  useEffect(() => {
    const id = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(id);
  }, [theme, draw]);

  /**
   * 换标的 = 「重开详情」：横向位置回到最新价（缩放不动）。首帧初值已是默认留白，
   * 这里只处理之后的切换；切周期不走这里 —— 本标的内的横向位置照旧保留。
   */
  const lastSymbolRef = useRef(symbolKey);
  useEffect(() => {
    if (lastSymbolRef.current === symbolKey) return;
    lastSymbolRef.current = symbolKey;
    vpRef.current = { ...vpRef.current, rightOffset: DEFAULT_RIGHT_BLANK };
  }, [symbolKey]);

  useEffect(() => {
    // 纵向位置重定 —— 这里只把挂起标记立起，等新序列落地后由 recenter effect 对到最新价。
    // 十字光标与画了一半的直线是过程状态，跨数据一律作废：下标会越界，
    // 半截直线的锚点（虽是时间戳）也没有跨标的的意义。
    recenterPendingRef.current = true;
    crossRef.current = null;
    pendingRef.current = null;
    panBaseRef.current = null;
    draw();
  }, [symbolKey, interval, draw]);

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
     * 本场手势的种类，在**按下那一刻**由落点决定：
     * `pan` = 拖画布；`trend-anchor` / `trend-body` / `line` = 拖**已选中的**那条线。
     *
     * 两条铁律（2026-10-24 起）：
     * · 未选中任何线时，按下只做「选中 / 取消选中」，绝不拖动 —— 一场误拖会把线挪走，
     *   而用户以为自己在平移画布；
     * · 工具（划线区里点的线型）激活时，按下即落点画线，不进入拖拽。
     * 锁定时整段抓取跳过，落点一律走 `pan`（锁 = 线一根都不许动）。
     * 副图（以及它下面的时间轴）不参与任何手势。
     */
    type Mode = 'none' | 'pan' | 'trend-anchor' | 'trend-body' | 'line';
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
    /** 画线拖拽：抓的是哪条、哪个端点、上一帧的落点（trend-body 的位移换算用）。 */
    let grabIndex = -1;
    let grabEnd: TrendEnd = 'a';
    let grabLastX = 0;
    let grabLastY = 0;

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
    const autoBase = () => {
      const s = modelRef.current.series;
      const n = s.candles.length;
      if (n <= 0) return { low: 0, high: 1 };
      const [ws, we] = dataWindow(vpRef.current, n);
      return mainRange(s, ws, we);
    };

    /**
     * 纵向意图的换算基准：手势期间取冻结值，其余时候取自动量程（与 draw 同源）。
     * 手势中若仍跟着窗口变，`pricePan` 会被反复重新钳，拖拽手感会逐帧漂。
     */
    const currentBase = () => panBaseRef.current ?? autoBase();

    /** 当帧**真正画出来**的价格量程：自动量程 + 用户的缩放/平移意图（与 draw 同源）。 */
    const currentMainRange = () => priceRange(currentBase(), priceRef.current);

    /** 价格 → 主图内的 y（与 draw 里 `yOf(toFraction(mainR, price), mainTop, mh)` 同式）。 */
    const yOfPrice = (price: number) => {
      const { top, bottom } = mainBounds();
      return top + toFraction(currentMainRange(), price) * (bottom - top);
    };

    /** 主图内的 y → 价格（工具落点的反算）。 */
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

    /** 所有直线的两点像素（单击选中的线身命中用；无限直线的距离只由这两点定）。 */
    const trendBodies = (plotW: number) =>
      modelRef.current.trendLines.map((line) => {
        const a = pxOfAnchor(line.a, plotW);
        const b = pxOfAnchor(line.b, plotW);
        return { x1: a.x, y1: a.y, x2: b.x, y2: b.y };
      });

    /** 所有直线的两端把手（抓取判定与空闲光标共用；锁定时不会被调用）。 */
    const trendHandles = (plotW: number): TrendHandle[] =>
      modelRef.current.trendLines.flatMap((line, index) => {
        const a = pxOfAnchor(line.a, plotW);
        const b = pxOfAnchor(line.b, plotW);
        return [
          { index, end: 'a' as const, x: a.x, y: a.y },
          { index, end: 'b' as const, x: b.x, y: b.y },
        ];
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
        persistView(true);
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
      persistView(true);
      draw();
    };

    /** 复位小标被点到就走 [resetView]，不进入拖拽。 */
    const hitBadge = (e: MouseEvent): boolean => {
      const hit = badgeRef.current;
      if (!hit) return false;
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      return x >= hit.x && x <= hit.x + hit.w && y >= hit.y && y <= hit.y + hit.h;
    };

    /**
     * 复位：**横纵缩放全部回默认 + 回到最新价格的 K 线**（2026-10-26 用户拍板，与 App 同口径）。
     *
     * 与「重开详情」的区别：重开只回到最新价、缩放原样保留；复位连缩放一起清掉。
     * 改完立即落盘 —— 缩放是持久化偏好，不写回的话切个周期旧缩放又被带回来。
     */
    const resetView = () => {
      vpRef.current = initialViewport();
      priceRef.current = initialPriceView();
      persistView();
      draw();
    };

    /**
     * 工具落点（划线区里选了线型之后，图上的每一次点击都走这里）。
     *
     * · 水平线：落一次即成，价位由落点反算；
     * · 两点直线：第一次落点起笔（记在 `pendingRef`，之后鼠标一动就预览），第二次成线；
     *   两下离得太近当作没落成 —— 退化的零长度直线没有意义，也不该被建出来。
     *
     * 画成一条就 `onDrawn()` 把工具收回去：一次点击一个动作，举着工具连画只是「多点了一下
     * 又画一条」的风险，没有收益（要接着画再点一次线型就行）。
     */
    const addPointAt = (x: number, y: number, plotW: number, tool: DrawTool) => {
      const model = modelRef.current;
      if (tool === 'hline') {
        const price = priceOfY(y);
        if (Number.isFinite(price)) model.onAddPriceLine(price);
        model.onDrawn();
        crossRef.current = null;
        draw();
        return;
      }
      const anchor = anchorAt(x, y, plotW);
      if (!anchor) return; // 图上还没有数据：没有时间锚点，画不出直线
      const seed = pendingRef.current;
      if (!seed) {
        pendingRef.current = { a: anchor, to: null };
        crossRef.current = null;
        draw();
        return;
      }
      const a = pxOfAnchor(seed.a, plotW);
      if (Math.hypot(a.x - x, a.y - y) < TREND_MIN_DRAW_PX) return;
      model.onAddTrend(seed.a, anchor);
      pendingRef.current = null;
      model.onDrawn();
      crossRef.current = null;
      draw();
    };

    const onPointerDown = (e: PointerEvent) => {
      // 只认左键：右键既不画线也不选线（浏览器自带菜单已在 contextmenu 里挡掉）
      if (e.button !== 0) return;
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      if (hitBadge(e)) {
        resetView();
        return;
      }
      const { top, bottom } = mainBounds();
      const inMain = x <= plotW && y >= top && y <= bottom;

      // 工具态：按下即落点，不进拖拽（拖画布请先在划线区把工具点掉）
      const activeTool = modelRef.current.tool;
      if (activeTool !== 'none') {
        if (inMain) addPointAt(x, y, plotW, activeTool);
        return;
      }

      // 抓取已有的画线（只在**未锁定 + 已选中**时）：把手 → 直线线身 → 水平线 ——
      // 把手最小、最难中，排最前。锁定时整段跳过：落点照旧是「拖画布」。
      //
      // **没选中的线这一下只选中、不拖动**：一场误拖会把线挪走，而用户以为在平移画布；
      // 「先单击选中，再按住拖」也让划线区的编辑按钮先出现一次，动作永远有据可查。
      if (!modelRef.current.linesLocked && inMain) {
        const model = modelRef.current;
        /**
         * 命中一条线：已是选中的那条 → true（继续进入拖拽）；否则只上报选中 → false。
         */
        const pick = (next: LineSelection) => {
          if (isSameLine(model.selection, next)) return true;
          model.onSelect(next);
          crossRef.current = null;
          draw();
          return false;
        };
        const handle = nearestTrendHandle(trendHandles(plotW), x, y);
        if (handle) {
          if (!pick({ kind: 'trend', index: handle.index })) return;
          mode = 'trend-anchor';
          grabIndex = handle.index;
          grabEnd = handle.end;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          applyCursor(canvas, 'on-line');
          draw();
          return;
        }
        const body = nearestTrendBody(trendBodies(plotW), x, y);
        if (body >= 0) {
          if (!pick({ kind: 'trend', index: body })) return;
          mode = 'trend-body';
          grabIndex = body;
          grabLastX = x;
          grabLastY = y;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          applyCursor(canvas, 'on-line');
          draw();
          return;
        }
        const hit = hitLine(y);
        if (hit >= 0) {
          if (!pick({ kind: 'price', index: hit })) return;
          mode = 'line';
          grabIndex = hit;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          applyCursor(canvas, 'on-line');
          draw();
          return;
        }
      }
      // 副图与时间轴：只读区，不参与拖拽
      if (belowMain(y)) return;
      // 空白处 = 取消选中 + 拖画布
      if (modelRef.current.selection) modelRef.current.onSelect(null);
      mode = 'pan';
      axis = null;
      accumX = 0;
      accumY = 0;
      lastX = e.clientX;
      lastY = e.clientY;
      pointerId = e.pointerId;
      canvas.setPointerCapture(e.pointerId);
      applyCursor(canvas, '');
      // 整场手势冻结纵向基准：可见窗口每帧都在变，不冻结的话自动量程会逐帧重排
      // （K 线一边横移一边上下缩放）。松手时由 repinPriceAfterPan 把取景落成意图。
      panBaseRef.current = autoBase();
    };

    const onPointerMove = (e: PointerEvent) => {
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const n = modelRef.current.series.candles.length;
      if (mode === 'trend-anchor') {
        // 端点跟着指针走（另一端不动）。坐标夹在主图内：指针甩出画布后锚点不再继续飞。
        const { top, bottom } = mainBounds();
        const anchor = anchorAt(clampNum(x, 0, plotW), clampNum(y, top, bottom), plotW);
        if (anchor) modelRef.current.onMoveTrendAnchor(grabIndex, grabEnd, anchor);
        return; // 重画由上层（ChartView 的 useDrawings）随新 props 带回来
      }
      if (mode === 'trend-body') {
        // 整体平移。位移按**当前映射**换算成数据空间增量：时间用像素反算
        // （别自己推「像素 / 格宽 × 周期」—— 视窗被夹住时那个公式就不成立）。
        const { top, bottom } = mainBounds();
        const cx = clampNum(x, 0, plotW);
        const cy = clampNum(y, top, bottom);
        const list = modelRef.current.series.candles;
        const ivMs = intervalMs();
        const dTime =
          timeAtIndex(list, indexOfX(cx, plotW), ivMs) -
          timeAtIndex(list, indexOfX(grabLastX, plotW), ivMs);
        const dPrice = priceOfY(cy) - priceOfY(clampNum(grabLastY, top, bottom));
        grabLastX = cx;
        grabLastY = cy;
        modelRef.current.onMoveTrendBy(grabIndex, dTime, dPrice);
        return;
      }
      if (mode === 'line') {
        // 价格跟着指针走（绝对定位；抓取容差 4px，贴手不跳）。y 夹在主图内。
        const { top, bottom } = mainBounds();
        modelRef.current.onMovePriceLine(grabIndex, priceOfY(clampNum(y, top, bottom)));
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
      // 空闲：画了一半的直线，预览的实线要跟着鼠标走 ——
      // 没有这一段的话，「从这里画直线」在点了菜单之后只剩一个孤零零的起点，
      // 第二下落在哪、线会是什么斜率完全看不出来。
      const pending = pendingRef.current;
      if (pending) {
        const anchor = anchorAt(x, y, plotW);
        if (anchor) pendingRef.current = { a: pending.a, to: anchor };
      }
      // 空闲态的指针形状只有两种（2026-10-26 用户）：
      // · 压在任意一条画线上（**选没选中都一样**）→ 四个直角拼成的十字（`on-line`），
      //   拖线过程中也保持这枚十字；
      // · 其余（含复位小标、拖画布）一律空心圆环。
      //
      // 工具就位时**一律圆环**（2026-10-26 用户）：那两下是落点，系统十字的两根短线
      // 正好压在环心那颗蜡烛上 —— 而落点最需要看清的就是那里。工具态也不再做画线命中，
      // 因为 `onPointerDown` 里工具态直接短路掉了选线/拖线，`on-line`/`move` 只会骗人。
      let cursor: CursorKind = '';
      if (modelRef.current.tool === 'none' && !modelRef.current.linesLocked) {
        const { top, bottom } = mainBounds();
        if (x <= plotW && y >= top && y <= bottom) {
          const onAnyLine = nearestTrendHandle(trendHandles(plotW), x, y) != null
            || nearestTrendBody(trendBodies(plotW), x, y) >= 0
            || hitLine(y) >= 0;
          if (onAnyLine) cursor = 'on-line';
        }
      }
      applyCursor(canvas, cursor);
      // 十字光标照常贯穿副图：竖线跨窗格、副图读数带要能读值，那不属于「拖拽/缩放」
      // `onBar` 管竖线与顶部读数带（右侧留白里没有蜡烛，就不读数），横线与价格标照旧。
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
      const pannedHorizontal = axis === 'H';
      const frozenBase = panBaseRef.current;
      mode = 'none';
      axis = null;
      panBaseRef.current = null;
      grabIndex = -1;
      if (pointerId !== null && canvas.hasPointerCapture(pointerId)) {
        canvas.releasePointerCapture(pointerId);
      }
      pointerId = null;
      // 松手回到空闲指针（`on-line` 的类也在这里一并摘掉 —— 只清内联样式的话，
      // 那枚四角角标会顶替掉本该出现的圆环，直到下一次 pointermove 才纠正）
      applyCursor(canvas, '');
      // 横向平移松手**不重新适配**纵向量程：把冻结取景落成相对新窗口的意图，
      // 否则自动量程会按新窗口重算、K 线整块上下跳一下（用户报的「拖到历史时自动摆回中间」）。
      // 极少数留不住的情况（新窗口的数据基本滑出冻结量程）会退回自动适配，那一下得重画。
      if (pannedHorizontal && frozenBase) repinPriceAfterPan(frozenBase);
      // 一场拖拽结束时落盘一次：过程中每帧写没有意义，松手才是「我停在这儿」的意图
      persistView();
      draw();
    };

    const onLeave = () => {
      if (mode !== 'none') return;
      crossRef.current = null;
      applyCursor(canvas, '');
      draw();
    };

    /**
     * 双击 = 在落点画一条水平线（与划线区的「水平线」工具同一个动作，普通画线、不告警）。
     * 只认主图：副图的双击没有价位含义，复位小标与工具态的双击各有各的意思。
     * 同价已有线时 `addPriceLine` 自己会挡掉，不会两根重合。
     */
    const onDblClick = (e: MouseEvent) => {
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const { top, bottom } = mainBounds();
      if (x > plotW || y < top || y > bottom) return;
      if (modelRef.current.tool !== 'none') return;
      if (hitBadge(e)) return;
      const price = priceOfY(y);
      if (!Number.isFinite(price)) return;
      modelRef.current.onAddPriceLine(price);
      draw();
    };

    /**
     * Esc = 撤掉画了一半的那条直线（待定的第一下），顺手取消选中。
     * 挂 document 而不是 canvas：canvas 不可聚焦，焦点多半在别处，挂它自己一条都收不到。
     */
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (pendingRef.current) {
        pendingRef.current = null;
        draw();
      }
      if (modelRef.current.selection) modelRef.current.onSelect(null);
    };

    /**
     * 右键 = **只挡掉浏览器自带菜单**（canvas 上的「另存为图片」没有意义），不再弹自定义菜单。
     *
     * 划线的入口全在划线区（画什么线、改哪条线都由那里的按钮决定），所以画布这一侧
     * 不需要知道"落点落在谁身上"。唯一的保留动作：画了一半的直线用右键撤销 ——
     * 与 Esc 并行，符合"右键 = 退一步"的手感。
     */
    const onContextMenu = (e: MouseEvent) => {
      e.preventDefault();
      if (pendingRef.current) {
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
  }, [draw, persistView, reportOlder, repinPriceAfterPan]);

  return (
    <div className="chart-stage">
      <div className="chart-scroll" ref={wrapRef}>
        <canvas className="chart-canvas" ref={canvasRef} style={{ height: totalH }} />
      </div>
      {/* 遮罩层压在画布上（`.chart-stage` 是定位容器）：底下的骨架网格透得出来，
          转圈与「加载中」说明这是在取数、不是没有数据。它同时也挡住画布的手势 ——
          数据没到时的拖拽 / 缩放会算出没有意义的视窗并落进偏好（见 lib/chartViewPrefs）。 */}
      {loading && (
        <div className="chart-loading" role="status" aria-live="polite">
          <span className="chart-loading-spin" aria-hidden="true" />
          <span className="chart-loading-text">加载中…</span>
        </div>
      )}
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

/** 读数里的一段：文本 + 已解析好的颜色（悬浮读数绘制与量宽共用同一份）。 */
interface TextSegment {
  text: string;
  color: string;
}

/** K 线读数一行的各段：时间 / 开高低收 / 较上根（读数与它所属的线同色）。 */
function candleRowSegments(
  candle: Bar | undefined,
  prev: Bar | undefined,
  decimals: number,
  intervalMinutes: number,
  p: Palette,
): TextSegment[] {
  if (!candle) return [];
  const fmt = (v: number) => readoutNumber(v, decimals);
  const segments: TextSegment[] = [
    { text: formatCandleTime(candle.timestamp, intervalMinutes), color: p.label },
    { text: `开 ${fmt(candle.open)}`, color: p.ink },
    { text: `高 ${fmt(candle.high)}`, color: p.ink },
    { text: `低 ${fmt(candle.low)}`, color: p.ink },
    { text: `收 ${fmt(candle.close)}`, color: p.ink },
  ];
  if (prev && prev.close !== 0) {
    const change = ((candle.close - prev.close) / prev.close) * 100;
    const sign = change >= 0 ? '+' : '';
    segments.push({
      text: `较上根 ${sign}${change.toFixed(2)}%`,
      color: change >= 0 ? p.up : p.down,
    });
  }
  return segments;
}

/** 一行读数的宽度（段间留 `READOUT_GAP`）：悬浮底板按它定宽。 */
function textSegmentsWidth(ctx: CanvasRenderingContext2D, segments: TextSegment[]): number {
  return segments.reduce(
    (w, seg, i) => w + ctx.measureText(seg.text).width + (i > 0 ? READOUT_GAP : 0),
    0,
  );
}

/** 画一行读数，返回行尾 x。 */
function drawTextSegments(
  ctx: CanvasRenderingContext2D,
  segments: TextSegment[],
  x: number,
  y: number,
): number {
  let cursor = x;
  for (const seg of segments) {
    ctx.fillStyle = seg.color;
    ctx.fillText(seg.text, cursor, y);
    cursor += ctx.measureText(seg.text).width + READOUT_GAP;
  }
  return cursor - READOUT_GAP;
}
