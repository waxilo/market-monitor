import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { TrendAnchor } from '../lib/trendLines';
import type { ChartMenuHit } from './KlineCanvas';

interface Props {
  /** 画布报上来的落点（视口坐标 + 命中了什么）。 */
  x: number;
  y: number;
  hit: ChartMenuHit;
  /** 命中价格时用来显示的那串字（精度跟着标的的 tickSize，别在菜单里另算一套）。 */
  priceText: string;
  /** 当前标的的画线总数：清空那条要显示条数，一条都没有时整条不出现。 */
  drawingCount: number;
  /** 画线是否已锁定 —— 决定锁定条目写「锁定全部画线」还是「解锁全部画线」。 */
  linesLocked: boolean;
  onSetLinesLocked: (locked: boolean) => void;
  /** 纵向刻度已被拖离自动量程 —— 多给一条「复位」。 */
  adjusted: boolean;
  onAddLine: (price: number, alert: boolean) => void;
  onRemoveLine: (index: number) => void;
  onSetAlert: (index: number, alert: boolean) => void;
  onRemoveTrend: (index: number) => void;
  /** 「从这里画直线」：第一点就用右键落点，第二下左键成线（画布接着预览）。 */
  onStartTrend: (anchor: TrendAnchor) => void;
  onClear: () => void;
  onResetPrice: () => void;
  onClose: () => void;
}

interface Item {
  key: string;
  label: string;
  /** 右侧那列小字（价位 / 手势提示 / 条数），可有可无。 */
  aside?: string;
  danger?: boolean;
  run: () => void;
}

/** 菜单尺寸上限：用于贴边翻转（真实高度随条目数变，取个够用的上界即可：最多 6 条）。 */
const MENU_W = 208;
const MENU_H = 200;

/**
 * 图表区的右键菜单 —— **画线上唯一的入口**（0.1.11 起工具条上没有「画图」这组了）。
 *
 * 为什么这里可以自绘 HTML（悬浮窗那边非得走原生 popup）：主窗有 1024×680 的底，
 * 画布不是整个窗口，菜单再怎么贴边也翻得回来；悬浮窗就一块面板，浮层一定被窗口边界裁掉。
 *
 * 每条动作做完立刻收起（`run` 之后父层就 `onClose`）：菜单是一次性的定点操作，
 * 不是常驻工具条。「清空全部画线」是唯一的破坏性条目，排在最后并走 danger 色。
 */
export function ChartMenu({
  x,
  y,
  hit,
  priceText,
  drawingCount,
  linesLocked,
  onSetLinesLocked,
  adjusted,
  onAddLine,
  onRemoveLine,
  onSetAlert,
  onRemoveTrend,
  onStartTrend,
  onClear,
  onResetPrice,
  onClose,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: x, top: y });

  // 贴边翻转：菜单比光标靠右下时就往左上挪，别让条目掉出窗口（画布能右键的地方包含右边缘）。
  useLayoutEffect(() => {
    const w = ref.current?.offsetWidth ?? MENU_W;
    const h = ref.current?.offsetHeight ?? MENU_H;
    setPos({
      left: Math.max(6, Math.min(x, window.innerWidth - w - 6)),
      top: Math.max(6, Math.min(y, window.innerHeight - h - 6)),
    });
  }, [x, y, hit]);

  // 点外面 / Esc / 一滚动就收：菜单是按落点定位的，页面一动它的指向就不成立了
  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('keydown', onKey);
    window.addEventListener('wheel', onClose, { passive: true });
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('wheel', onClose);
    };
  }, [onClose]);

  const items: Item[] = [];
  if (hit.kind === 'empty') {
    items.push({
      key: 'alert',
      label: '设成告警线',
      aside: priceText,
      run: () => onAddLine(hit.price, true),
    });
    items.push({
      key: 'hline',
      label: '画一条水平线',
      aside: priceText,
      run: () => onAddLine(hit.price, false),
    });
    // 第一点直接取右键落点（anchor 为空 = 图上还没有数据，画出来的线没有时间锚点）
    if (hit.anchor) {
      items.push({
        key: 'trend',
        label: '从这里画直线',
        aside: '再点一下成线',
        run: () => onStartTrend(hit.anchor!),
      });
    }
  }
  if (hit.kind === 'hline') {
    items.push({
      key: 'toggle',
      label: hit.alert ? '取消这条告警' : '设成告警线',
      aside: priceText,
      run: () => onSetAlert(hit.index, !hit.alert),
    });
    items.push({ key: 'remove', label: '删掉这条线', danger: true, run: () => onRemoveLine(hit.index) });
  }
  if (hit.kind === 'trend') {
    items.push({ key: 'remove', label: '删掉这条直线', danger: true, run: () => onRemoveTrend(hit.index) });
  }
  if (drawingCount > 0) {
    // 锁定管的是**能不能拖**（整张图一个开关，默认解锁，见 useDrawings/linesLock）。
    // 一条线都没有时不给：没东西可锁，先画才有意义。
    items.push({
      key: 'lock',
      label: linesLocked ? '解锁全部画线' : '锁定全部画线',
      run: () => onSetLinesLocked(!linesLocked),
    });
    items.push({
      key: 'clear',
      label: '清空全部画线',
      aside: `${drawingCount}`,
      danger: true,
      run: onClear,
    });
  }
  if (adjusted) {
    items.push({ key: 'reset', label: '复位纵向刻度', run: onResetPrice });
  }

  return createPortal(
    <div className="chart-menu" ref={ref} style={{ left: pos.left, top: pos.top }} role="menu">
      {items.map((it) => (
        <button
          key={it.key}
          type="button"
          role="menuitem"
          className={`chart-menu-item${it.danger ? ' danger' : ''}`}
          onClick={() => {
            it.run();
            onClose();
          }}
        >
          <span>{it.label}</span>
          {it.aside && <span className="chart-menu-aside num">{it.aside}</span>}
        </button>
      ))}
    </div>,
    document.body,
  );
}
