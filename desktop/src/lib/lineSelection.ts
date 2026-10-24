/**
 * 划线的两个交互概念：**当前工具**与**选中哪条线**（2026-10-24 起与 App 的划线区同一套口径）。
 *
 * · 工具 —— 在划线区点一下线型就进入，再点图上落点画线；画成一条就自动退出。
 *   工具激活期间点击 = 落点，不会去选线。
 * · 选中 —— 单击图上的线才选中。**先选中，才谈得上移动 / 备注 / 删除**：
 *   选中是这些动作的前置条件，也是「我现在在改哪一条」的唯一凭据。
 *
 * 两者互斥（工具优先），并且都只在解锁态下成立 —— 锁定管的是「整张图的线能不能动」。
 */
export type DrawTool = 'none' | 'hline' | 'trend';

/**
 * 选中的是哪条线。
 *
 * 水平线与两点直线各有**一套下标**（两条存储互不相通），所以 `index` 单独拿出来没有意义，
 * 必须连同 `kind` 一起看 —— 任何跨层传递都用整个对象，别在中间拆开。
 */
export interface LineSelection {
  kind: 'price' | 'trend';
  index: number;
}

/** 选中判定（比较两处 state 时用值比较，别比对象引用）。 */
export function isSameLine(a: LineSelection | null, b: LineSelection | null): boolean {
  if (a === null || b === null) return a === b;
  return a.kind === b.kind && a.index === b.index;
}
