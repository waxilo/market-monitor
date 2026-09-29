/**
 * 图表**纵向**几何：唯一出处。
 *
 * 高度分配曾经分叉成两份（常量在组件里、公式在组件里手写），结果漏掉「副图也占高度」
 * 这一项。所以这里把「参与分配的常量」和「分配函数」放在一起，由 `chartHeights` 统一出口。
 *
 * 与 App `ui/chart/KlineChart.kt` 的 ChartGeo 对齐（dp → px），但**高度分配规则桌面端不同**：
 * App 在手机上靠页面滚动；桌面端窗口高度固定、滚动条又被 `.chart-scroll` 隐掉了，
 * 所以「主图吃掉剩余空间」：有副图时让位给副图，没副图时自己吃满 —— 详见 `chartHeights` 的注释。
 */

/** 右侧价格刻度列宽。 */
export const AXIS_W = 58;
/** 底部时间刻度带高度。 */
export const TIME_AXIS_H = 18;
/** 读数带每行行高。 */
export const READOUT_LINE = 12;
/** 读数带上下内边距合计。 */
export const READOUT_PAD = 6;
/** 每块副图的总高（含它自己的读数行）。 */
export const SUB_PANE_H = 110;
/** 副图读数行高度（与 `SUB_PANE_H` 的差就是真正画指标的高度）。 */
export const SUB_READOUT_H = 16;
/** 主图高度下限：再矮就数不清 K 线了。 */
export const MAIN_MIN = 240;

/** 读数带高度：K 线一行 + 每个叠加线族（MA / BOLL）各一行。 */
export function readoutHeight(rows: number): number {
  return READOUT_PAD + READOUT_LINE * rows;
}

export interface ChartHeights {
  /** 顶部读数带高度。 */
  readoutH: number;
  mainTop: number;
  mainH: number;
  mainBottom: number;
  /** 主图以下的副图区总高（刚性，每块 `SUB_PANE_H`）。 */
  subPanesH: number;
  /** 画布总高（写进 canvas 的 style.height）。 */
  totalH: number;
  /** 总高超出可视区的像素数；0 = 刚好放下。 */
  overflow: number;
}

/**
 * 把可视区高度分给 读数带 / 主图 / 副图 / 时间轴。
 *
 * 分配规则：**主图吃掉所有剩余空间**（只保留下限 `MAIN_MIN`），副图区刚性、
 * 每块固定 `SUB_PANE_H`。曾经的规则是主图在 [`MAIN_MIN`, 420] 内收放，两个坑：
 *
 *   ① 上限在没有副图时也生效 ⇒ 默认窗口（可视 652）画布只有 456px，图表区底下
 *      空掉 196px（大窗口空 376px）—— 「没选指标时主图不吃满」。
 *   ② 公式漏减副图占的高度 ⇒ 主图先吃满 420，副图再往下叠，选三块时总高 786px
 *      而可视区只有 652px，第三块整块落在滚动区外；偏偏 `.chart-scroll` 的滚动条
 *      是被刻意隐藏的，用户看到的就是「选了三个只显示两个」。
 *
 * 现在的式子对两种情形都给同一个答案：总高 == 可视区（填满，不滚动），只有
 * 「主图已到下限、副图仍放不下」时才如实溢出（溢出量见 `overflow`，界面靠滚动看）。
 *
 * `availH <= 0` 表示还没量到容器（首帧），按主图下限给一个稳定值。
 */
export function chartHeights(availH: number, readoutRows: number, subPaneCount: number): ChartHeights {
  const readoutH = readoutHeight(readoutRows);
  const subPanesH = subPaneCount * SUB_PANE_H;
  const mainH =
    availH > 0 ? Math.max(MAIN_MIN, availH - readoutH - subPanesH - TIME_AXIS_H) : MAIN_MIN;
  const mainTop = readoutH;
  const mainBottom = mainTop + mainH;
  const totalH = mainBottom + subPanesH + TIME_AXIS_H;
  return {
    readoutH,
    mainTop,
    mainH,
    mainBottom,
    subPanesH,
    totalH,
    overflow: availH > 0 ? Math.max(0, totalH - availH) : 0,
  };
}
