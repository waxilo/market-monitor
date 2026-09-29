/**
 * 悬浮窗几何的**单一来源**。
 *
 * 面板高度只由自选条数决定：`min(条数, miniMaxRows) × miniRowHeight + 2px 边框`，
 * 超过封顶条数时行区内部滚动（窗口高度不再增长）。
 *
 * 这里是前端侧的权威定义；另外两处必须跟它对齐：
 * - `styles/theme.css` 的 `--mini-row-h` / `--mini-max-rows`（管视觉。CSS 变量没法从
 *   TS 里直接消费，所以只能靠注释互指 —— **改这里就要改那边**）；
 * - `src-tauri/src/lib.rs` 的 `MINI_ROW_H` / `MINI_MAX_ROWS` / `MINI_W`（管真实窗口
 *   尺寸与位置。前端量不到隐藏窗口的排版，所以只报条数，几何在 Rust 算）。
 *
 * 那个「+ 2」是面板自己的上下各 1px 边框，三处都要一起算。
 */
export const MINI_ROW_HEIGHT = 26;
export const MINI_MAX_ROWS = 6;
/**
 * 面板宽度（逻辑像素）。这是**唯一的硬约束**：窗口是 `inner_size(MINI_WIDTH, …)`，
 * 而面板是块级 flex，被 webview 视口撑满 —— 所以宽度只能在这儿定，CSS 里没有第二个旋钮。
 *
 * 行内可用宽度 = MINI_WIDTH − 2(面板边框) − 20(行左右 padding 各 10)
 *              = 符号列(1fr) + 现价列(auto) + 涨跌列(52px) + 两个 8px 间隙。
 * 现价列不换行、涨跌列定宽，所以**变窄只会吃掉符号列**，符号按设计走省略号
 * （`1000PEPE` 这类 8 字符在 12px 半粗下约 56px，是符号列的实测下界）。
 * 268 时符号列有 ~119px（多出 60px 空档），视觉上整块面板明显偏宽 —— 收到 236
 * 后符号列仍有 ~87px，够 `1000PEPE` 这类最长名。**要再窄就得先确认符号列还有余量**
 * （`.workbuddy/tmp/verify-mini-width.mjs` 用真数据量各列宽度与溢出）。
 */
export const MINI_WIDTH = 236;

/** 面板的上下边框合计（Rust 的 `MINI_CHROME_H` 同值）。 */
export const MINI_CHROME_HEIGHT = 2;

/** 按自选条数算出面板高度（逻辑像素）—— 与 Rust `mini_height_for_rows` 同一式子。 */
export function miniHeightForRows(rows: number): number {
  const clamped = Math.min(Math.max(rows, 1), MINI_MAX_ROWS);
  return clamped * MINI_ROW_HEIGHT + MINI_CHROME_HEIGHT;
}
