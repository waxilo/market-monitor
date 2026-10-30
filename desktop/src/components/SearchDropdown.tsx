import { useEffect, useMemo, useRef, useState } from 'react';
import { MARKET_LABEL, type Instrument, type MarketType } from '../lib/api';
import type { WatchItem } from '../hooks/useWatchlist';
import { rankMarket } from '../lib/search';
import { displaySymbol } from '../lib/format';

interface Props {
  /** 搜索词（已 trim 过，非空才渲染本组件）。 */
  keyword: string;
  /** 永续全量标的（右栏）。 */
  futures: Instrument[];
  /** 现货全量标的（左栏）。 */
  spot: Instrument[];
  /** 选中一条 = 打开（或切到）它的标签页。**只查看**，不收藏 —— 主窗没有自选。 */
  onPick: (item: WatchItem) => void;
  /** 收起浮层（点击外部 / Esc / 选中之后）。**不清空输入框**。 */
  onClose: () => void;
}

/** 键盘高亮的位置：第几栏、栏内第几行。`null` = 还没动过键盘/鼠标。 */
interface Cursor {
  col: number;
  row: number;
}

/**
 * 搜索结果下拉框：挂在搜索框下方，跟着输入走。
 *
 * **左右两栏：左现货、右永续**（0.1.48 起；此前是混排一条，靠行首的「现/永」记号区分）。
 * 分栏之后同名标的（`BTC_USDT`）天然各在各栏，不用再认记号；「我要看哪个市场」
 * 也从一个读字游戏变成看列 —— 扫一眼就知道。两栏各排各的（`rankMarket`），
 * 排序尺子同一条，不会一栏按 A 排、另一栏按 B 排。
 *
 * 键盘是全的（Ctrl+K 进来后不用碰鼠标）：↑/↓ 在当前栏内上下、←/→ 换栏、
 * Enter 打开高亮那条、Esc 收起。这些键**挂在 document 上而不是本组件的根节点上** ——
 * 焦点全程在顶部那个输入框里，事件不会经过这里的 DOM 树，挂在根节点上一条都收不到。
 * 换栏时栏内行号夹回该栏长度（两栏条数通常不一样）。
 */
export function SearchDropdown({ keyword, futures, spot, onPick, onClose }: Props) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [cursor, setCursor] = useState<Cursor | null>(null);

  const columns = useMemo<{ market: MarketType; list: Instrument[] }[]>(
    () => [
      { market: 'SPOT', list: rankMarket(spot, keyword) },
      { market: 'FUTURES', list: rankMarket(futures, keyword) },
    ],
    [spot, futures, keyword],
  );
  const total = columns[0].list.length + columns[1].list.length;

  // 关键词一变结果集整个换掉，上一轮的高亮没有意义
  useEffect(() => {
    setCursor(null);
  }, [keyword]);

  /** 高亮移到某处，并把那一行滚进各自栏的可视区（两栏各有一个滚动容器）。 */
  const moveTo = (next: Cursor) => {
    setCursor(next);
    rootRef.current
      ?.querySelectorAll('.sr-col')
      [next.col]?.querySelectorAll('.sr-row')[next.row]?.scrollIntoView({ block: 'nearest' });
  };

  const pick = (at: Cursor) => {
    const column = columns[at.col];
    const inst = column?.list[at.row];
    if (!inst) return;
    onPick({ market: column.market, symbol: inst.symbol });
  };

  /** 有结果的栏（键盘只在这些栏之间走；空格子不参与）。 */
  const filledCols = columns.flatMap((c, i) => (c.list.length > 0 ? [i] : []));

  useEffect(() => {
    // 点击浮层外部收起。用 pointerdown 而不是 click：click 要等抬手，期间落点若在
    // 别的按钮上会先跑那个按钮的行为，观感是「点外面反而点开了别的东西」。
    function onPointerDown(e: PointerEvent) {
      const el = rootRef.current;
      if (el && e.target instanceof Node && !el.contains(e.target)) onClose();
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        return;
      }
      if (filledCols.length === 0) return;

      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault();
        if (cursor == null) {
          moveTo({ col: filledCols[0], row: 0 });
          return;
        }
        const other = filledCols.find((c) => c !== cursor.col);
        if (other == null) return;
        moveTo({ col: other, row: Math.min(cursor.row, columns[other].list.length - 1) });
        return;
      }

      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const delta = e.key === 'ArrowDown' ? 1 : -1;
        if (cursor == null) {
          // 无高亮时：↓ 从第一栏第一行开始，↑ 直接跳最后一栏的最后一行
          const col = delta > 0 ? filledCols[0] : filledCols[filledCols.length - 1];
          moveTo({ col, row: delta > 0 ? 0 : columns[col].list.length - 1 });
          return;
        }
        const len = columns[cursor.col].list.length;
        if (len === 0) return;
        // 栏内循环：到顶再按 ↑ 回到本栏末行，不串到另一栏（换栏是 ←/→ 的事）
        moveTo({ col: cursor.col, row: (cursor.row + delta + len) % len });
        return;
      }

      if (e.key === 'Enter' && cursor != null) {
        e.preventDefault();
        pick(cursor);
      }
    }
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
    // 刻意**不写依赖数组**：处理函数要读最新的 `cursor` / `columns`。
    // 只挂一次（`[]`）会把首次渲染的闭包冻住 —— 高亮永远停在 null、Enter 选中空。
    // 每轮重挂一次事件监听的开销可以忽略（键盘敲一下才一轮），换来的是没有陈旧闭包。
  });

  return (
    <div className="search-drop" ref={rootRef}>
      <div className="sr-head">
        <span className="overline">搜索结果 · 现货 + 永续</span>
        <span className="sr-count num">{total}</span>
      </div>

      <div className="sr-cols">
        {columns.map((col, ci) => (
          <section className="sr-col" key={col.market} aria-label={MARKET_LABEL[col.market]}>
            <div className="sr-col-head">
              <span className="overline">{MARKET_LABEL[col.market]}</span>
              <span className="sr-count num">{col.list.length}</span>
            </div>
            <div className="sr-scroll">
              {col.list.map((inst, ri) => {
                const on = cursor != null && cursor.col === ci && cursor.row === ri;
                return (
                  <div
                    key={inst.symbol}
                    className={`sr-row${on ? ' active' : ''}`}
                    title={`${displaySymbol(inst.symbol)} · ${MARKET_LABEL[col.market]} · 点击打开图表`}
                    onMouseEnter={() => setCursor({ col: ci, row: ri })}
                    onClick={() => pick({ col: ci, row: ri })}
                  >
                    <span className="sr-name">{inst.baseAsset}</span>
                    <span className="sr-quote">{inst.quoteAsset}</span>
                  </div>
                );
              })}
              {col.list.length === 0 && (
                <div className="sr-col-empty">
                  {(col.market === 'SPOT' ? spot : futures).length === 0
                    ? `正在拉取${MARKET_LABEL[col.market]}标的…`
                    : `没有匹配「${keyword}」的${MARKET_LABEL[col.market]}`}
                </div>
              )}
            </div>
          </section>
        ))}
      </div>

      <div className="sr-foot">
        <span>↑↓ 选择</span>
        <span>←→ 换栏</span>
        <span>Enter 打开</span>
        <span>Esc 关闭</span>
      </div>
    </div>
  );
}
