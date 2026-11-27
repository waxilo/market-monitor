import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Bar, OpenInterestSeries } from '../lib/api';
import {
  axisLock,
  candleBodyRect,
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
  rawFraction,
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
  AXIS_GUTTER,
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
import { lineBadgeVisible, nearestLineWithin, type PriceLine } from '../lib/priceLines';
import {
  clipLineToRect,
  nearestTrendBody,
  nearestTrendHandle,
  findTrendBodyGrab,
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
/** 读数带距绘图区左右边缘的留白（两侧同值）。 */
const READOUT_X = 8;
/** 悬浮读数里段与段之间的间距（与 App 的读数带同口径）。 */
const READOUT_GAP = 10;
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
/**
 * 把手的半径。**这是「能否抓住」的唯一视觉线索**，所以比线宽明显大一圈：
 * 3.5 + 1px 描边在黄色画线上几乎融成一体（2026-11-21 实测截图里根本看不见圆点），
 * 用户就不知道这条线还能拖。4.5 + 2px 纸色描边之后，黄线上是一枚带白边的实心点。
 */
const TREND_HANDLE_R = 4.5;

/**
 * 水平线「按住任意位置就拖」的命中带宽（像素）—— 线上哪一段都能抓，不再有把手。
 *
 * 2026-11-26 用户：「水平线应该去掉抓点，任意位置都应该可以拖拽」。此前的规矩是
 * 两端各一枚圆点当**改价的唯一入口**（理由见下面这条历史），代价是「必须精确点中那两个
 * 半径 7px 的圆点」，手势上就是「抓不住」。
 *
 * ⚠️ 只对**已选中**那条生效（2026-11-25 用户「不管什么线，都要先选中才能编辑」）：
 * 未选中时按下只做选中，落点继续拖画布 —— 否则这条横贯全宽的带子会把画布横切成一条
 * 误抓走廊（2026-11-21 报过同一件事，实测线价 57500 → 56288.84 而 `pricePan` 一格没动）。
 * 带宽只比选中宽一点（直线是 8，这里同值），拖动时按「线相对指尖的价差」跟手
 * （见 `grabPriceOffset`）—— 线不跳、读数不乱。若日后觉得误抓频繁，第一选项是缩这条带子，
 * 而不是把把手加回来。
 */
const PRICE_BODY_GRAB_PX = 8;

/**
 * 「行情线」的粗细（CSS px）：最新价那条虚线、以及用户画的所有线（水平线 / 两点直线 / 预览）。
 *
 * 集中在这里是因为它们必须**同粗**（对价时靠这一层参照），而调粗细是视觉反馈的常规动作：
 * 2 → 1.5（2026-11-21 用户「太粗」）。⚠️ 画布按 `devicePixelRatio` 缩放，2 CSS px 在 150%
 * 屏上是 3 个物理像素 —— 别按「屏幕像素」直觉去估这里的数。
 * 划线（1.5）现在与均线同粗；再细到 1 就会重演 2026-11-15 那次「压在 K 线上看丢」。
 */
const W_LAST_PRICE = 1.5;
const W_DRAW_LINE = 1.5;

/**
 * 选中一条线时那道**对称外发光**（alpha / 粗细，CSS px）：铺在线下面，不改线本身。
 *
 * 2026-11-26 起选中的**主信号是线色转黄**，这道光退成辅信号 —— 几条平行线挨得近时，
 * 「正在动的是哪一条」靠一圈光比靠颜色更快看见。仍不加粗：加粗会让读数看着偏半个像素
 * （2026-11-15 踩过）。App 侧同一套（`LINE_SELECTED_HALO_ALPHA = 0.22`、宽 9）。
 */
const SEL_HALO_ALPHA = 0.22;
const SEL_HALO_WIDTH = 5;

/** 最新价虚线的疏密：跟着线宽一起收（2px 时代是 [5, 4]），细了之后长段看着更空。 */
const LAST_PRICE_DASH = [4, 3];

/**
 * 用户画线的虚线（`PriceLine.dashed` / `TrendLine.dashed`）：短划 8px、空隙 5px。
 *
 * 比最新价那条**长一档**（8/5 对 4/3）：最新价是行情自己画的辅助线、越不抢戏越好；
 * 用户的线是他特意设的，虚着也该读得出「这条线在哪」，太碎会像 K 线上的杂点。
 *
 * ⚠️ 6/4 试过，1 倍屏上几乎读不出断开（1.5px 的线本身抗锯齿，空隙被两端半透明吃掉一半，
 * 实测只剩 2px 真空）—— 4 倍放大截图才看得清。现在 5px 空隙落实约 3px，正常大小就有「虚线感」。
 * App 侧同值（`DRAW_DASH_LENGTH` / `DRAW_DASH_GAP`）。
 */
const DRAW_DASH = [8, 5];

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
   * 用户画线（水平线 / 两点直线）的**选中色**：黄。
   *
   * 三档语义（2026-11-26 定，与 App 同一套）：
   * · 普通 → [plain]（中性：浅色主题近黑、深色主题近白）；
   * · 选中 → 这里（黄，临时交互态，**压过告警色**：正拖着的那一条先看见黄）；
   * · 挂了告警 → down 红（会响）。
   * **三种线型一律实线**，区分只在颜色。
   */
  line: string;
  /** 普通画线的中性色：「黑色划线」按主题落地 —— 浅色主题近黑、深色主题近白。 */
  plain: string;
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
    plain: v('--line-plain') || '#ecedef',
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
  // 奇偶宽度分别落在半像素 / 整像素上：1px 走 0.5 才实，2px 走整数才不会被抗锯齿拉成 3px
  const center = Math.round(y) + (Math.round(ctx.lineWidth) % 2 === 1 ? 0.5 : 0);
  ctx.beginPath();
  ctx.moveTo(x0, center);
  ctx.lineTo(x1, center);
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
  color = p.line,
) {
  if (x < -TREND_HANDLE_R || x > plotW + TREND_HANDLE_R) return;
  if (y < top - TREND_HANDLE_R || y > bottom + TREND_HANDLE_R) return;
  ctx.beginPath();
  ctx.arc(x, y, TREND_HANDLE_R, 0, Math.PI * 2);
  ctx.fillStyle = color;
  ctx.fill();
  // 2px 纸色描边：把手大半压在**同色**的画线上，细描边等于没有 —— 这圈白边才是
  // 「这里能抓」的形状依据（见 TREND_HANDLE_R 的注释）。
  ctx.strokeStyle = p.paper;
  ctx.lineWidth = 2;
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
 * 现在牌面取**线的当前颜色**（中性＝普通画线 / 黄＝选中 / 红＝告警），字取该色上的反差色，
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
  font = NOTE_FONT,
): { left: number; width: number } {
  if (!text) return { left: x, width: 0 };
  const padX = 4;
  const h = 14;
  ctx.save();
  ctx.font = font;
  ctx.textBaseline = 'middle';
  const w = ctx.measureText(text).width + padX * 2;
  const nx = clampNum(x - padX, 2, Math.max(2, plotW - w - 2));
  const ny = clampNum(y, top + h / 2, Math.max(top + h / 2, bottom - h / 2));
  // **牌面照右轴那枚价签做**：实色块 + 高反差字，不是「纸色半透明」——
  // 纸色底板跟图底本来就同一个颜色，压在密集 K 线/网格上时字被底下的东西拖着读不清
  // （用户反馈「容易被背景影响」）。底色即线当前的颜色（中性 / 选中黄 / 告警红），
  // 与右轴价签同色同尺寸，读起来就是「这条线的标签」。
  ctx.globalAlpha = 1;
  ctx.fillStyle = bg;
  roundRect(ctx, nx, ny - h / 2, w, h, 3);
  ctx.fillStyle = color;
  ctx.fillText(text, nx + padX, ny + 0.5);
  ctx.restore();
  return { left: nx, width: w };
}

/**
 * 数据空间锚点 → 主图像素（两点直线的绘制与命中判定**共用这一处**换算）。
 * 与 draw 里的 `xOf`/`yOf` 严格同式：x 走 `(i - start + 0.5) * slot`、y 走
 * `top + fraction(量程, 价格) * 高` —— 两处各写一份必然会分叉（曾经手写过就分叉）。
 *
 * ⚠️ 纵向这一支用的是**不夹取**的 [rawFraction]，不是 `toFraction`：直线是两点定斜率，
 * 任一端被夹到 [-0.5, 1.5] 就改了斜率（纵轴缩放到锚点出量程时肉眼可见地转），
 * 越界的那一端由 `clipLineToRect` 裁掉即可。2026-11-23 用户「缩放纵坐标，直线穿过的点还是会变」。
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
  const y = top + rawFraction(mainR, anchor.p) * (bottom - top);
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
  /** 双击已选中的那条线：打开它的备注框（改哪条线由落点命中决定）。 */
  onEditNote: (selection: LineSelection) => void;
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
  onEditNote,
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
   * 十字光标。`index` 是**夹回序列内**的下标（读数、横竖线与价格标都按它走），
   * `onBar` 表示指针是不是落在有数据的横向区间里 —— 竖线、时间标与顶部读数都只看这一个开关，
   * 因为视窗右侧还有一段没有蜡烛的留白（默认 3 根）。**纵坐标不参与命中**：读数按列走
   * （2026-11-03 用户拍板，鼠标在哪一列就读哪根，不必压到蜡烛实体上）。
   */
  const crossRef = useRef<{
    index: number;
    y: number;
    onBar: boolean;
  } | null>(null);
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
   * 高度决定纵向分配；宽度只用于「宽度变了也要重画」—— 窗口横向缩放只改宽度、
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
    onEditNote,
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

    // 绘图宽 = 画布宽：右轴那一列不再扣掉（2026-11-21 用户：右侧不再空出一块放价签，
    // 图表铺满，价签浮在图上 —— 见 `drawBadge` 与 `AXIS_GUTTER`）。
    const plotW = Math.max(1, cssW);

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
    /**
     * 可见窗口里的最高 / 最低那一根（币安式极值标注，画法见主图那段与价格标那段）。
     * 标的是「画布上这几根 K 线的高低」：平移或缩放换了一批 K 线，标就跟着换成新窗口的极值。
     * 同价取**较新**的一根，标与线更靠近右侧正在走的部分。
     */
    let hiIdx = -1;
    let loIdx = -1;
    for (let i = ws; i <= we; i++) {
      const c = list[i];
      if (!c) continue;
      if (hiIdx < 0 || c.high >= list[hiIdx].high) hiIdx = i;
      if (loIdx < 0 || c.low <= list[loIdx].low) loIdx = i;
    }
    const extremes: { index: number; price: number }[] = [];
    if (hiIdx >= 0) extremes.push({ index: hiIdx, price: list[hiIdx].high });
    if (loIdx >= 0) extremes.push({ index: loIdx, price: list[loIdx].low });
    const [rs, re] = plotRange(vpRef.current, n);
    const start = plotStart(vpRef.current, n);
    const slot = plotW / Math.max(1, vp.visibleBars);
    const xOf = (i: number) => (i - start + 0.5) * slot;
    const yOf = (fraction: number, top: number, height: number) => top + fraction * height;
    // 实体随槽宽等比，**但槽里必须留缝**（规则与取值见 chartMath.candleBodyRect）：
    // 曾用 `Math.max(1, round(slot * 0.66)) | 1`，缩到槽宽 3~4px 时会算出 3px，
    // 相邻两根只剩不到 0.5px 缝，整排 K 线糊成一条连续色带（2026-11-21 用户）。
    // 它返回的宽度恒为奇数、左缘恒为整数：柱心正好压在 `round(x) + 0.5` 这一列，
    // 与下面 `vline` 的影线严格共线 —— 偶数宽 / 取整左缘的写法会差 1px（芯歪到实体一侧）。
    const bodyRect = (x: number) => candleBodyRect(x, slot);
    const dec = decimalsFor(ts);
    /** 价格读数（轴上的价签与划线价牌共用一套小数位）。 */
    const priceText = (price: number) =>
      price.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
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
      const { left, w } = bodyRect(x);
      ctx.fillRect(
        left,
        Math.round(Math.min(openY, closeY)),
        w,
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

    // 下面这一整段（水平线 / 两点直线 / 半截直线预览）的**线型由每条线自己决定**
    // （`dashed`，2026-11-26 起可切）。进段先清掉残留 dash —— 别让上面某个绘制分支的
    // 虚线渗进「用户没要求虚线」的那一条；段内每条线画完也各自复位。
    ctx.setLineDash([]);

    // 用户画的水平线横贯主图，压在 K 线/均线之上、最新价之下
    // （最新价是行情本身，任何时候都不该被遮）。价格标在下面与最新价标一起画。
    //
    // **颜色**三档（2026-11-26 定稿）：选中 → 黄（临时交互态，压过告警色）、
    // 挂告警 → 红（要等着被触发，得一眼从普通划线里分出来）、普通划线 → 中性
    // （浅色主题近黑、深色主题近白）。两点直线同一套。
    // **线型**是用户自己的选择（实线 / 虚线），与颜色正交 —— 颜色说「会不会响 / 是不是正在动」，
    // 线型只说「这条线我想画淡一点」。早期给告警线配虚线算过语义，那样「虚线 = 会响」和
    // 「虚线 = 用户想要虚线」就会抢同一个形状，现在形状全部还给人。
    /**
     * 线备注牌：先记下来、等这一层所有线（连最新价那条虚线也算）画完再统一「盖章」。
     * 牌是实心的、牌心就压在线 y 上 ⇒ 线在牌底下断成两截，看着就是线从牌里穿过去；
     * 所以**任何线都不许从牌面上穿过去**，只能在所有线之后画。
     */
    const noteChips: { text: string; x: number; y: number; fg: string; bg: string }[] = [];
    /** 选中 = **线色转黄**（2026-11-26 起，颜色是此刻唯一的状态信号）；粗细与位置照旧不动 ——
     *  加粗会把对价的那一圈带偏（2026-11-15）。 */
    const selTrend = sel?.kind === 'trend' ? sel.index : -1;
    const selPrice = sel?.kind === 'price' ? sel.index : -1;
    // 水平线另加一道**对称外发光**（2026-11-26 去掉了两端把手）：线上任意位置都能按住拖，
    // 再画两枚「只能抓这儿」的圆点等于把可拖范围讲反了；多条平行线时这道光让
    // 「正在动哪一条」更明确。发光不改线的位置与粗细（对齐读价不受影响），与 App 同一口径。
    if (pl.length > 0) {
      pl.forEach((line, index) => {
        const y = yOf(toFraction(mainR, line.price), mainTop, mh);
        if (y < mainTop || y > mainBottom) return;
        // 三档（2026-11-26）：选中 = 黄（正在操作这一条，压过告警色）、挂告警 = 红、普通 = 中性。
        const picked = index === selPrice;
        const color = picked ? p.line : line.alert ? p.down : p.plain;
        // 线型：发光与线身用**同一套 dash**，否则虚线下面垫着一条实心的光，看着还是实线。
        ctx.setLineDash(line.dashed ? DRAW_DASH : []);
        if (picked) {
          ctx.globalAlpha = SEL_HALO_ALPHA;
          ctx.strokeStyle = color;
          ctx.lineWidth = SEL_HALO_WIDTH;
          hline(ctx, 0, plotW, y);
          ctx.globalAlpha = 1;
        }
        ctx.strokeStyle = color;
        ctx.lineWidth = W_DRAW_LINE;
        hline(ctx, 0, plotW, y);
        ctx.setLineDash([]);
        // 备注牌串在线的左端（图上一眼从左边读起），牌面与右轴那枚价签同一套：
        // 牌底 = 线色，字色取与它反差最大的一档（黄底配深字、中性/红底配纸色字）。
        if (line.note) {
          noteChips.push({
            text: line.note,
            x: 8,
            y,
            fg: picked ? p.onAccent : p.paper,
            bg: color,
          });
        }
      });
    }

    // 用户画的两点直线：与水平线同一套三档色（普通中性 / 选中黄 / 告警红 ——
    // 直线本身不挂告警，所以实际只有前两档），线型同样由 `dashed` 决定。两者只差形状 ——
    // 横线读作「一个价位」、斜线读作「一条斜率」，颜色与线型都不必再分工。
    // 端点把手**只画在选中的那条上**：把手是「这条线现在能拖」的信号。
    ctx.strokeStyle = p.line;
    ctx.lineWidth = W_DRAW_LINE;
    tl.forEach((line, index) => {
      const a = anchorPx(line.a, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      const b = anchorPx(line.b, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
      // 选中不改粗细、不加光晕：对齐靠的就是线本来的位置（见上面水平线同一口径），
      // 选中信号是**线色转黄** + 两端锚点把手。
      const picked = index === selTrend;
      const color = picked ? p.line : p.plain;
      ctx.strokeStyle = color;
      ctx.lineWidth = W_DRAW_LINE;
      ctx.setLineDash(line.dashed ? DRAW_DASH : []);
      infiniteLine(a.x, a.y, b.x, b.y);
      ctx.setLineDash([]);
      // 备注挂在**看得见的那个锚点**旁：A 被判出绘图区（线画到未来/过去很远）就换 B ——
      // 否则「给这条直线写了备注但图上找不到」。
      if (!line.note) return;
      const inView = (pt: { x: number; y: number }) =>
        pt.x >= 0 && pt.x <= plotW && pt.y >= mainTop && pt.y <= mainBottom;
      const spot = inView(a) ? a : inView(b) ? b : null;
      if (!spot) return;
      // 牌心压在锚点所在的那条斜线上（往右让开一点，别压住把手）；牌底跟线色、字色取反差色。
      noteChips.push({
        text: line.note,
        x: spot.x + 8,
        y: spot.y,
        fg: picked ? p.onAccent : p.paper,
        bg: color,
      });
    });
    // 待定的两点直线预览也吃这个宽度（下面的 infiniteLine 用的是当前 ctx.lineWidth）
    ctx.lineWidth = W_DRAW_LINE;
    const selectedTrend = selTrend >= 0 ? tl[selTrend] : undefined;
    if (!lock && selectedTrend) {
      // 选中信号 = **只有两端**这两枚圆点（2026-11-23 起中点那枚已删）。
      // 两端=改角度；整根平移改成按住线身（`onTrendBody`），于是屏幕上不再有一枚
      // 正好压在中央、既挡读数又抢「中央上下拖 = 调量程」起手位的圆点。
      for (const anchor of [selectedTrend.a, selectedTrend.b]) {
        const pt = anchorPx(anchor, list, ivMs, vp, mainR, mainTop, mainBottom, plotW);
        drawTrendHandle(ctx, pt.x, pt.y, p, mainTop, mainBottom, plotW);
      }
    }

    // 画了一半的直线（划线区选了「直线」、已经落了第一个点）的预览：起点把手 + 过起点与鼠标的
    // **实线、中性色**（预览的就是成品的样子：一条普通的中性无限直线；线型/颜色落点后才改得动）
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
    /** 最新价的涨跌色：与 App 同口径，跟着**最新这一根**的收盘 vs 开盘走（实时跳动时也会翻面）。 */
    const lastColor = last.close >= last.open ? p.up : p.down;
    if (lastY >= mainTop && lastY <= mainBottom) {
      // 最新价线走涨跌色（与 App 一致）：它是「现在在哪儿」，而这一根的颜色就是最近的答案。
      // 与划线同粗（[W_LAST_PRICE] === [W_DRAW_LINE]）：这一条是现价的参照线，比 K 线本身细
      // 就压不住背景、比划线粗又会抢戏。2026-11-15 曾加到 2（1px 看丢），2026-11-21 收回 1.5（太粗）。
      ctx.strokeStyle = lastColor;
      ctx.lineWidth = W_LAST_PRICE;
      ctx.setLineDash(LAST_PRICE_DASH);
      hline(ctx, 0, plotW, lastY);
      ctx.setLineDash([]);
    }

    // ── 可见区间最高 / 最低的价线 ──
    // 从极值那一根拉一条虚线到右缘（币安那张图的画法）：左端咬住极值那根蜡烛、右端接上那枚价签，
    // 一眼看出「这个数字说的是图上哪个点」。所以它比最新价那条更淡、也不横贯整张图 ——
    // 左边那半张图不该被两条新线切碎。
    const drawExtremeLevel = (index: number, price: number) => {
      const y = yOf(toFraction(mainR, price), mainTop, mh);
      if (y < mainTop || y > mainBottom) return;
      ctx.globalAlpha = 0.5;
      ctx.strokeStyle = p.label;
      ctx.lineWidth = 1;
      ctx.setLineDash([2, 3]);
      hline(ctx, Math.max(0, xOf(index)), plotW, y);
      ctx.setLineDash([]);
      ctx.globalAlpha = 1;
    };
    for (const mark of extremes) drawExtremeLevel(mark.index, mark.price);

    // 备注牌**最后盖章**：牌心压在线 y 上，实心牌把线盖掉一截 ⇒ 线看着从牌里穿过去。
    // 放在最新价虚线之后，是为了让牌面永远干净（牌上不许有别的线划过去）。
    //
    // 划线自己的价位牌**不在这里**：它和最新价 / 极值 / 十字光标一起画在图表右侧（悬浮）
    // （2026-11-15 用户：不和其他价签分开放），见下面「价格标」那段。
    // 备注牌仍在左端（图上一眼从左边读起）。
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
          const { left, w } = bodyRect(x);
          ctx.fillRect(
            left,
            Math.round(Math.min(y, zero)),
            w,
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
    // 右侧价格轴**不画固定档位的数字**（2026-11-15 用户）：这一列只留动态价签
    // （最新价 / 极值 / 十字光标）。网格线照旧（见上面的 `gridLines(mainR)`），
    // 档位本身还能看见，只是不再拿一串跟着缩放重排的数字去挤右轴。
    // 副图的指标刻度不在此列，见下。

    // 副图刻度：每块只取首尾两条，块本来就矮
    //
    // 与主图价签同一套：**右对齐、贴在画布右边缘**（右轴不再占一列）。刻度没有底牌，
    // 所以淡淡的压在柱子上 —— 这也是币安 / TradingView 在窄屏上的取舍（读数优先于柱子）。
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
        ctx.textAlign = 'right';
        ctx.fillText(
          // 成交量与持仓量都是大额（几亿起步），走 K/M/B 缩写；其余指标的小数刻度照旧
          pane.kind === 'VOLUME' || pane.kind === 'OI' ? formatCompact(value) : value.toFixed(2),
          cssW - AXIS_GUTTER - 2,
          y,
        );
        ctx.textAlign = 'left';
      }
    });

    // ── 价格标（划线价牌 / 极值 / 最新价 / 十字光标）：全贴画布右缘、浮在图上，与线同高 ──
    // 排在刻度之后，盖住同高刻度。
    const drawBadge = (text: string, centerY: number, bg: string, fg: string) => {
      const w = ctx.measureText(text).width + 8;
      const h = 14;
      const y = clampNum(centerY - h / 2, mainTop, Math.max(mainTop, mainBottom - h));
      // **右对齐、贴在画布右边缘**（2026-11-21 用户：右轴不再占一列，价签浮在图表上）：
      // 图形铺满整宽，牌子压在最右这几根蜡烛之上 —— 实底牌心保证读数照样清楚，
      // 线（最新价 / 极值 / 划线）画到 `plotW` 也就是画布最右，末端自然被牌子盖住、看着是「接着」的。
      const left = Math.max(0, cssW - AXIS_GUTTER - w);
      ctx.fillStyle = bg;
      roundRect(ctx, left, y, w, h, 3);
      ctx.fillStyle = fg;
      ctx.fillText(text, left + 5, y + h / 2 + 0.5);
    };
    // **不在可视区里的不画标** —— drawBadge 会把标夹到上下边缘，那会让一条屏外的线
    // 看着像就在那个价上。
    //
    // 划线自己的价位牌也进这一列（2026-11-15 用户：不和其他价签分开放），颜色照旧跟着线：
    // 选中黄底、告警红底、普通中性底，字色取与底色反差最大的那一档。
    // **先画划线、后画行情读数** —— 同一高度时由极值 /
    // 最新价 / 十字光标压在它上面（行情本身比用户的记号重要，且读数常常是同一个数）。
    // 每条线单独显隐，见 `lineBadgeVisible`。
    pl.forEach((line, index) => {
      if (!lineBadgeVisible(line)) return;
      const y = yOf(toFraction(mainR, line.price), mainTop, mh);
      if (y < mainTop || y > mainBottom) return;
      const picked = index === selPrice;
      drawBadge(
        priceText(line.price),
        y,
        picked ? p.line : line.alert ? p.down : p.plain,
        picked ? p.onAccent : p.paper,
      );
    });
    // 可见区间的最高 / 最低价：靠那条虚线对回极值那根；最新价后画，同高时由它盖住
    // （行情本身比注释重要，且那时读数是同一个数）。
    // 极值线与价签统一用灰（`p.label`）：它们是「这段行情的上下沿」这类注释，
    // 不该抢最新价（涨跌色）与十字光标读数的注意力（2026-11-15 用户）。
    for (const mark of extremes) {
      const y = yOf(toFraction(mainR, mark.price), mainTop, mh);
      if (y < mainTop || y > mainBottom) continue;
      drawBadge(priceText(mark.price), y, p.label, p.paper);
    }
    if (lastY >= mainTop && lastY <= mainBottom) {
      // 价签与线同色（涨绿 / 跌红），一眼对得上「这条虚线说的是这个数」
      drawBadge(priceText(last.close), lastY, lastColor, p.paper);
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

    // ── 读数（主图右上角）──
    // 不占版面：主图从画布顶端开始画，读数叠在图上；**常在** —— 指针落在有数据的某一列
    // 就读那一根（只认横坐标，纵坐标不参与，2026-11-03 用户拍板），其余时候读最新一根。
    // 右侧留白 / 价格轴 / 指针离开画布都算「不在任何一根上」，读最新。
    // 整块靠绘图区右缘（紧邻价格轴）：左半边让给 K 线本身，最近的行情读数在视线落点处
    // （2026-11-15 用户要求从左上挪到右侧）。
    // 底下垫一层 86% 纸色的圆角薄垫，压在蜡烛 / 布林线上也读得清 ——
    // 无描边、无阴影，它是浮在图上的一块读数，不是卡片。
    const reading = !!cross && cross.onBar && cross.index >= rs && cross.index <= re;
    const idx = reading && cross ? clampNum(cross.index, 0, n - 1) : n - 1;
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
    const plateRight = plotW - READOUT_X;
    const plateY = mainTop + 4;
    const plateW = Math.min(
      Math.max(...rows.map((row) => textSegmentsWidth(ctx, row))) + padX * 2,
      plateRight - (READOUT_X - padX),
    );
    const plateX = plateRight - plateW;
    const textX = plateX + padX;
    const plateH = padY * 2 + READOUT_LINE * rows.length;

    ctx.save();
    ctx.beginPath();
    ctx.rect(plateX, plateY, plateW, plateH);
    ctx.clip();
    ctx.globalAlpha = 0.86;
    ctx.fillStyle = p.paper;
    roundRect(ctx, plateX, plateY, plateW, plateH, 6);
    ctx.globalAlpha = 1;
    rows.forEach((row, r) => {
      drawTextSegments(ctx, row, textX, plateY + padY + READOUT_LINE * r + READOUT_LINE / 2);
    });
    ctx.restore();
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
      // 没变就返回同一个对象：否则每轮观察都触发一次重渲染（窗口拖宽时尤其频繁）
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
     * `pan` = 拖画布；`trend-anchor` = 改直线角度；`trend-body` / `line` = 拖整条线改位置/价位。
     *
     * 两条铁律（2026-11-25 用户「不管什么线，都要先选中才能编辑」）：
     * · **先选中，才编辑**：未选中任何线时，按下只做「选中 / 取消选中」，绝不拖动 ——
     *   一场误拖会把线挪走，用户却以为自己在平移画布；
     * · 工具（划线区里点的线型）激活时，按下即落点画线，不进入拖拽。
     * 选中之后（2026-11-26 用户「水平线去掉抓点，任意位置都应该可以拖拽」）：直线抓两端
     * 把手改角度、按住线身整根平移；水平线**线上任意位置**按住就改价，两端把手已删。
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
    /**
     * 拖水平线时「线比指尖高多少价」（按下那一刻算一次，整场不变）。
     *
     * 抓取带有 [PRICE_BODY_GRAB_PX]，指针平均落不到线心上；不加这个偏移，第一帧线会
     * 跳到指尖所在的价位（按 8px 折算，等于凭空改了几个 tick）。
     */
    let grabPriceOffset = 0;

    const geom = () => {
      const rect = canvas.getBoundingClientRect();
      // 与 draw 同口径：绘图宽 = 画布宽（命中 / 缩放 / 平移都按这个换算）
      return { rect, plotW: Math.max(1, rect.width) };
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

    /**
     * 落点取价用的 y：以**屏上已经显示的十字光标**为准（看到多少就画多少）。
     *
     * `dblclick` / `pointerdown` 的 y 与最后一次 `pointermove` 的 y 在 Retina 上能差
     * 半个 CSS 像素，直接用事件 y，价签会在点击瞬间跳成另一个价（2026-11-15 用户报的
     * 双击跳价）。光标不在主图内（已离开 / 刚被缩放收走）时才退回事件落点。
     */
    const shownPriceY = (y: number) => {
      const { top, bottom } = mainBounds();
      const cross = crossRef.current;
      return cross && cross.y >= top && cross.y <= bottom ? cross.y : y;
    };

    /**
     * 离落点最近的那条水平线的下标（[PRICE_BODY_GRAB_PX] 容差），没有则 -1。
     *
     * 这条带子同时是「选中」和「按住拖动」的判据（2026-11-26 起）：线上任意位置都能抓，
     * 不再有「只在两端能抓」的把手。带宽比早先只用于选中的 4px 宽一档（与直线线身同值），
     * 「任意位置都能拖」才真的成立 —— 4px 在触控板上等于要把光标压在线上。
     * 拖动本身只对**已选中**那条开放，未选中时这条带子只负责「选中它」（见 onPointerDown）。
     */
    const hitLine = (y: number) =>
      nearestLineWithin(
        modelRef.current.priceLines.map((l) => yOfPrice(l.price)),
        y,
        PRICE_BODY_GRAB_PX,
      );

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
        const price = priceOfY(shownPriceY(y));
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

      // 抓取已有的画线：**先选中，才编辑**（2026-11-25 用户「不管什么线，都应该要先选中
      // 才能编辑，现在没选中就可以拖动」）。选中之后：直线抓两端把手改角度、按住线身
      // 整根平移（2026-11-23 中点那枚圆点退场）；水平线**线上任意位置**按住就改价 ——
      // 两端把手已删（2026-11-26 用户「去掉抓点，任意位置都应该可以拖拽」）。
      // 锁定时整段跳过：落点照旧是「拖画布」。
      //
      // 为什么未选中时绝不拖动（2026-11-21 用户「上下移动图表，直线穿过的位置变了」）：
      // 水平线在屏幕上**贯穿全宽**，线身命中带等于把画布横切成一条带子 —— 想上下平移图表时
      // 起手压到线上，线就跟着指针走（实测拖动 120px 后线价 57500 → 56288.84，而图表一格都没动）。
      // 直线那侧靠**窄带 + 只认已选中的那条**兜住同一个风险（`TREND_BODY_GRAB_PX` = 8px，
      // 且只认画出来那一段）：误抓面积只有一个 16px 宽的走廊，不是整片画布。
      // 水平线「任意位置可拖」同样只对**已选中**那条生效（带宽 [PRICE_BODY_GRAB_PX] = 8px
      // 与直线同值），未选中这一下只做选中、继续拖画布。
      let hitAnyLine = false;
      if (!modelRef.current.linesLocked && inMain) {
        const model = modelRef.current;
        /** 选中这条线（已经选中就什么也不做）—— 不进入任何拖拽。 */
        const selectOnly = (next: LineSelection) => {
          hitAnyLine = true;
          if (isSameLine(model.selection, next)) return;
          model.onSelect(next);
          crossRef.current = null;
          draw();
        };
        // 1) 趋势线两端的把手：改角度（未选中时这一下只选中，把手在选中后才画出来）。
        const handle = nearestTrendHandle(trendHandles(plotW), x, y);
        if (handle) {
          if (!isSameLine(model.selection, { kind: 'trend', index: handle.index })) {
            selectOnly({ kind: 'trend', index: handle.index });
            return;
          }
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
        // 2) **已选中那条**直线的线身：整体平移（2026-11-23 起，中点圆点已删）。
        //    判据是「到**画出来那一段**的距离 ≤ TREND_BODY_GRAB_PX」—— 与 `infiniteLine`
        //    同一个裁剪矩形，锚点早被平移到屏幕外时也不会认「空白里的延长线」。
        //    端点已在第 1 步吃过，所以按在端点上仍是改角度，按在其余处才是整根平移。
        //    **只认已选中那条**（2026-11-25 用户「先选中才能编辑」）：没选中时按线身
        //    只做第 3 步的选中，绝不拖动 —— 否则一次「想选中」的误拖就把线挪走了。
        const selIndex = model.selection?.kind === 'trend' ? model.selection.index : -1;
        const selBody = selIndex >= 0 ? trendBodies(plotW)[selIndex] : undefined;
        if (
          selBody
          && findTrendBodyGrab([selBody], { left: 0, top, right: plotW, bottom }, x, y) === 0
        ) {
          mode = 'trend-body';
          grabIndex = selIndex;
          grabLastX = x;
          grabLastY = y;
          crossRef.current = null;
          pointerId = e.pointerId;
          canvas.setPointerCapture(e.pointerId);
          applyCursor(canvas, 'on-line');
          draw();
          return;
        }
        // 3) 其余线身：未选中的线只做选中，落点继续走「拖画布」；**已选中那条水平线**则
        //    线上任意位置按住就改价（2026-11-26 用户「去掉抓点，任意位置都能拖」）。
        //    走到这里说明「按中的不是已选中那条的线身」（已选中 + 线身命中在第 2 步就接管了）。
        //    命中带与空闲光标的十字角标同一条（`findTrendBodyGrab`）：光标承诺「这里能抓」，
        //    按下去至少要把这条线选中；真正能动它要等下一次按下（2026-11-25「先选中，才编辑」）。
        const grab = findTrendBodyGrab(trendBodies(plotW), { left: 0, top, right: plotW, bottom }, x, y);
        if (grab >= 0) selectOnly({ kind: 'trend', index: grab });
        else {
          const priceHit = hitLine(y);
          if (priceHit >= 0) {
            const priceSelected =
              model.selection?.kind === 'price' && model.selection.index === priceHit;
            if (!priceSelected) selectOnly({ kind: 'price', index: priceHit });
            else {
              mode = 'line';
              grabIndex = priceHit;
              // 抓取带有 8px，指针未必正压在线心：记下「线比指尖高多少价」，逐帧加回去 ——
              // 线只跟位移走，不会在第一帧跳到指针上。
              grabPriceOffset =
                (model.priceLines[priceHit]?.price ?? 0) - priceOfY(clampNum(y, top, bottom));
              crossRef.current = null;
              pointerId = e.pointerId;
              canvas.setPointerCapture(e.pointerId);
              applyCursor(canvas, 'on-line');
              draw();
              return;
            }
          }
        }
      }
      // 空白处 = 取消选中。**只看「没命中任何线」**，不管落在哪儿：主图空地、副图、时间轴、
      // 右侧价格轴留白都算空白（2026-11-21 用户「点击空白直接去掉选中」—— 划线区那枚 ✕ 已删，
      // 这里成了退出选中的主要入口，只认主图空地会让人觉得「点了没反应」）。
      // 必须在 `belowMain` 之前：副图与时间轴本来是「只读区直接 return」，那样点它们就没反应了。
      // 命中线身的那一下已经选中了线，不能再抹掉（否则「点线选中」会变成「点线选中又立刻取消」）。
      if (!hitAnyLine && modelRef.current.selection) modelRef.current.onSelect(null);
      // 副图与时间轴：只读区，不参与拖拽
      if (belowMain(y)) return;
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
        // 价格跟着指针走，但带上按下那一刻的价差（`grabPriceOffset`）—— 线不往指尖跳。
        const { top, bottom } = mainBounds();
        modelRef.current.onMovePriceLine(
          grabIndex,
          priceOfY(clampNum(y, top, bottom)) + grabPriceOffset,
        );
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
          // 直线的线身用**和按下同一套判据**（`findTrendBodyGrab`）：光标承诺的
          // 「这里能抓」必须与真按下去的命中带一致，否则那枚四角十字就成了空头支票。
          const onAnyLine = nearestTrendHandle(trendHandles(plotW), x, y) != null
            || findTrendBodyGrab(trendBodies(plotW), { left: 0, top, right: plotW, bottom }, x, y) >= 0
            || hitLine(y) >= 0;
          if (onAnyLine) cursor = 'on-line';
        }
      }
      applyCursor(canvas, cursor);
      // 十字光标照常贯穿副图：竖线跨窗格、副图读数带要能读值，那不属于「拖拽/缩放」
      // `onBar` 管竖线、时间标与顶部读数（右侧留白 / 价格轴上没有蜡烛，那里读最新一根）。
      const px = e.clientX - rect.left;
      const fraction = px / plotW;
      const index = indexAt(vpRef.current, fraction, n);
      const onBar = px <= plotW && overDataAt(vpRef.current, fraction, n);
      crossRef.current = index >= 0 ? { index, y, onBar } : null;
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
     * 双击的两种意思，按落点分开：
     * - 落在**已选中的那条线**上 → 打开它的备注框（第一次单击已经完成「选中」，双击接着就是「改」）；
     * - 其余落点 → 在价格上画一条水平线（与划线区的「水平线」工具同一个动作，普通画线、不告警）。
     * 只认主图：副图的双击没有价位含义，复位小标与工具态的双击各有各的意思。
     * 同价已有线时 `addPriceLine` 自己会挡掉，不会两根重合。
     *
     * 落点价走 [shownPriceY]：用户看到的价格签是几，画出来的线就是几 ——
     * 不能直接拿 `dblclick` 的 y 反算，否则双击瞬间价签会跳成另一个价。
     */
    const onDblClick = (e: MouseEvent) => {
      const { rect, plotW } = geom();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const { top, bottom } = mainBounds();
      if (x > plotW || y < top || y > bottom) return;
      if (modelRef.current.tool !== 'none') return;
      if (hitBadge(e)) return;
      const sel = modelRef.current.selection;
      if (sel) {
        const onSelected =
          sel.kind === 'trend'
            ? nearestTrendBody(trendBodies(plotW), x, y) === sel.index
            : hitLine(y) === sel.index;
        if (onSelected) {
          modelRef.current.onEditNote(sel);
          return;
        }
      }
      const price = priceOfY(shownPriceY(y));
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
