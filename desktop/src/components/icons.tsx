/**
 * 窗口切换用的图标。
 *
 * 主窗与悬浮窗的入口原来写的是「悬浮窗」「大窗」两个词，占位宽又容易被读成
 * 两个独立功能；换成图标后只是同一件事的两个方向：收成小窗 / 展成大窗。
 * 语义靠 `title` 补，图标本身给个方向的形状。
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

/** 展成大窗：四角向外撑开。 */
export function ExpandWindowIcon() {
  return (
    <svg {...BASE}>
      <path d="M6.3 2.6H2.6v3.7" />
      <path d="M9.7 2.6h3.7v3.7" />
      <path d="M13.4 9.7v3.7H9.7" />
      <path d="M2.6 9.7v3.7h3.7" />
    </svg>
  );
}

/** 收起（隐藏到托盘）。 */
export function CloseIcon() {
  return (
    <svg {...BASE}>
      <path d="M4.2 4.2l7.6 7.6" />
      <path d="M11.8 4.2l-7.6 7.6" />
    </svg>
  );
}
