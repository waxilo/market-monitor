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
 * 行高 / 封顶行数因此收在 Rust 侧（`MINI_ROW_H` / `MINI_MAX_ROWS`），前端那两份
 * 定义在 `lib/layout.ts`（TS 侧的权威）与 `styles/theme.css`（视觉投影）。
 */
export function resizeMiniRows(rows: number) {
  if (!IS_TAURI) return;
  void invoke('set_mini_rows', { rows });
}

/**
 * 悬浮窗右键菜单。
 *
 * 菜单在 Rust 侧用原生 popup 弹（`open_mini_menu`），不在这里画 HTML ——
 * 悬浮窗就面板那么大，webview 里画的菜单会被窗口自己的边界裁掉。
 * 失败不往上报：菜单没弹出来不值得打断用户，托盘菜单里有同一个开关。
 */
export function openMiniMenu() {
  if (!IS_TAURI) return;
  void invoke('open_mini_menu').catch(() => {
    /* 见上：静默 */
  });
}

/**
 * 弹一条系统通知（价格告警）。文案全由调用方给：宿主不参与措辞。
 *
 * 失败只在控制台留一句，不打断用户 —— 通知弹不出来是系统侧的事（比如用户在
 * 「专注助手」里把它挡了），应用这边没法替用户解决，吼一声只会更吵。
 */
export function notifyPriceAlert(title: string, body: string) {
  if (!IS_TAURI) return;
  void invoke('notify_price_alert', { title, body }).catch((e: unknown) => {
    console.warn('系统通知失败', e);
  });
}
