/**
 * 窗口切换用的图标。
 *
 * 主窗与悬浮窗的入口原来写的是「悬浮窗」「大窗」两个词，占位宽又容易被读成
 * 两个独立功能；换成图标后只是同一件事的两个方向。
 *
 * 这里只有「收成小窗」这一个方向：反方向不用图标 —— 悬浮窗整块面板就是入口，
 * 点它任意处即换到主窗（见 MiniApp 的 onPanelClick）。
 */
const BASE = {
  width: 14,
  height: 14,
  viewBox: '0 0 16 16',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.3,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
};

/** 收成悬浮窗：大窗框里缩在右下角的一块小面板。 */
export function MiniWindowIcon() {
  return (
    <svg {...BASE}>
      <rect x="1.7" y="2.7" width="12.6" height="10.6" rx="1.5" />
      <rect x="7.9" y="8.4" width="5.6" height="4.1" rx="0.9" fill="currentColor" stroke="none" />
    </svg>
  );
}
