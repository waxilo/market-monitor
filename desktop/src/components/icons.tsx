/**
 * 窗口切换与布局用的图标。
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

/**
 * 侧栏开关：左边一列表示侧边栏，`open` 时空心（收起态）、收起时实心且收窄。
 * 状态画在图标里而不是加个 `.on` 类 —— 顶栏按钮的「亮着」在这一版里意味着
 * 别的东西（选中态），这里要表达的是**布局**，靠图形本身说话最不容易误读。
 */
export function SidebarIcon({ open }: { open: boolean }) {
  return (
    <svg {...BASE}>
      <rect x="1.7" y="2.7" width="12.6" height="10.6" rx="1.5" />
      {open ? (
        <line x1="6.5" y1="2.7" x2="6.5" y2="13.3" />
      ) : (
        <rect x="2.6" y="3.6" width="2.1" height="8.8" rx="0.6" fill="currentColor" stroke="none" />
      )}
    </svg>
  );
}
