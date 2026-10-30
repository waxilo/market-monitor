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

/*
 * 划线区用的一组图标（2026-10-24）。
 *
 * 线型用图形而不是文字缩写：两种线只差「横着」和「斜着」，一条线的小图比
 * 「水平线 / 直线」六个字更快认出来，也不占那一行本就不宽的位置。
 */
const LINE_ICON = { ...BASE, width: 12, height: 12 };

/** 水平线：一条横线，两端各一个小刻度（像钉在价格轴上的那条）。 */
export function HLineIcon() {
  return (
    <svg {...LINE_ICON}>
      <line x1="1.6" y1="8" x2="14.4" y2="8" />
      <line x1="4.2" y1="5.6" x2="4.2" y2="10.4" />
      <line x1="11.8" y1="5.6" x2="11.8" y2="10.4" />
    </svg>
  );
}

/** 两点直线：一条斜线，两端各一个锚点。 */
export function TrendIcon() {
  return (
    <svg {...LINE_ICON}>
      <line x1="3.4" y1="12.2" x2="12.6" y2="3.8" />
      <circle cx="3.4" cy="12.2" r="1.5" fill="currentColor" stroke="none" />
      <circle cx="12.6" cy="3.8" r="1.5" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** 备注：一支笔（右上角一道短斜线是笔尖）。 */
export function NoteIcon() {
  return (
    <svg {...BASE} width={12} height={12}>
      <path d="M3 13h2.2l7-7-2.2-2.2-7 7Z" />
      <line x1="9.4" y1="3.4" x2="11.6" y2="5.6" />
    </svg>
  );
}

/** 删除：垃圾桶（`danger` 类给红色，图标本身不表态）。 */
export function TrashIcon() {
  return (
    <svg {...BASE} width={12} height={12}>
      <path d="M3 4.6h10" />
      <path d="M6.2 4.6V3.2h3.6v1.4" />
      <path d="M4.4 4.6l.7 8.2h5.8l.7-8.2" />
      <line x1="6.8" y1="7" x2="6.8" y2="10.4" />
      <line x1="9.2" y1="7" x2="9.2" y2="10.4" />
    </svg>
  );
}

/* 自绘窗口控件（WindowChrome）的图标：比字符字形更细、更居中 */
const WIN_ICON = { ...BASE, width: 12, height: 12, strokeWidth: 1.2 };

/** 最小化：一条横线。 */
export function MinimizeIcon() {
  return (
    <svg {...WIN_ICON}>
      <line x1="3" y1="8" x2="13" y2="8" />
    </svg>
  );
}

/** 最大化 / 还原：单方框 / 两层错开的方框。 */
export function MaximizeIcon({ restore }: { restore: boolean }) {
  return (
    <svg {...WIN_ICON}>
      {restore ? (
        <>
          <rect x="3" y="5" width="8" height="8" rx="1" />
          <path d="M5 5V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-1" />
        </>
      ) : (
        <rect x="3" y="3" width="10" height="10" rx="1.4" />
      )}
    </svg>
  );
}

/** 关闭：×。 */
export function CloseIcon() {
  return (
    <svg {...WIN_ICON}>
      <line x1="3.6" y1="3.6" x2="12.4" y2="12.4" />
      <line x1="12.4" y1="3.6" x2="3.6" y2="12.4" />
    </svg>
  );
}

/** 返回：左向折角（设置页返回行情）。 */
export function BackIcon() {
  return (
    <svg {...BASE} width={16} height={16}>
      <path d="M9.8 3.4 5.2 8l4.6 4.6" />
    </svg>
  );
}

/** 搜索：放大镜（顶栏搜索卡左侧）。 */
export function SearchIcon() {
  return (
    <svg {...BASE} width={15} height={15}>
      <circle cx="7.2" cy="7.2" r="4.4" />
      <line x1="10.5" y1="10.5" x2="13.6" y2="13.6" />
    </svg>
  );
}

/**
 * 标签页的锁：`open` = 没锁（锁梁只搭一半，随时能关）。
 * 锁住时点它会解锁 —— 图形本身就是那个状态开关，不再另配文字。
 */
export function LockIcon({ open }: { open: boolean }) {
  return (
    <svg {...BASE} width={12} height={12}>
      <rect x="3.5" y="7.2" width="9" height="6.2" rx="1.4" />
      {open ? <path d="M5.6 7.2V5.4a2.4 2.4 0 0 1 4.8 0" /> : <path d="M5.6 7.2V5.4a2.4 2.4 0 0 1 4.8 0v1.8" />}
    </svg>
  );
}

