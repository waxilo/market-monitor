/**
 * 画线备注的**唯一口径**：长度上限 + 文本归一化。
 *
 * 备注是「这条线是干什么的」那几个字（`缺口上沿` / `前高` / `跌破这里就废`），
 * 不是笔记：60 字上限是为了它永远画得下、也永远读得完 —— 再长就该写进别的工具里。
 *
 * 归一化做三件事，缺一不可：
 *  1. `trim` —— 用户手抖敲进来的首尾空格不该在图上占位；
 *  2. 换行/连续空白压成一个空格 —— 画布上是**一行** `fillText`，多行会在线上糊成一片；
 *  3. 截到上限 —— 存档里的旧值、手改坏的值都从这条路进来，超长必须在**入库时**挡住。
 *
 * 零依赖：`priceLines.ts` / `trendLines.ts` 都是零依赖纯函数模块，这个文件也一样，
 * 好让它们照旧能被 Node 直接跑（`.mts` 验收脚本见 `.workbuddy/tmp`）。
 */

/** 备注长度上限（字符）。 */
export const LINE_NOTE_MAX = 60;

/** 任意输入 → 可落盘的备注。空串 = 没有备注（调用方据此删字段，而不是存一个空字符串）。 */
export function normalizeLineNote(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  return raw.replace(/\s+/g, ' ').trim().slice(0, LINE_NOTE_MAX);
}
