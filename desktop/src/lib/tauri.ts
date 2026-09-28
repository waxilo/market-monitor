import { invoke } from '@tauri-apps/api/core';

/** 是否跑在 Tauri 壳里（浏览器 npm run dev 时为 false，Tauri 专属调用据此短路）。 */
export const IS_TAURI = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

/**
 * 主窗 ⇄ 悬浮窗是两个方向、不是两个开关：点哪边的图标都表示「我要换成另一个」，
 * 所以走两个明确的命令而不是一个 toggle（避免「两个窗口同时开着」这种中间态）。
 */
export function switchToMiniWindow() {
  if (!IS_TAURI) return;
  void invoke('switch_to_mini');
}

export function switchToMainWindow() {
  if (!IS_TAURI) return;
  void invoke('switch_to_main');
}

/** 悬浮窗点某一行：主窗起来并选中该标的（主窗起来时悬浮窗会自动收起）。 */
export function showMainWindow(item: { market: string; symbol: string }) {
  if (!IS_TAURI) return;
  void invoke('show_main_window', { symbol: item.symbol, market: item.market });
}

/**
 * 悬浮窗按自选条数自撑高度（Rust 侧保持下边缘不动）。
 *
 * 只报条数、不报高度：悬浮窗是启动时预建的**隐藏**窗口，隐藏状态下 WebView2 不给它排版，
 * 量不到 `getBoundingClientRect()`、读 CSS 变量也不可靠，只有「条数」是一定算得出来的。
 * 行高 / 封顶行数因此收在 Rust 侧（`MINI_ROW_H` / `MINI_MAX_ROWS`，注释里标了与 theme.css 的对应关系）。
 */
export function resizeMiniRows(rows: number) {
  if (!IS_TAURI) return;
  void invoke('set_mini_rows', { rows });
}
