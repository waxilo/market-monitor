import { useEffect, useMemo, useRef, useState } from 'react';
import { MARKET_LABEL, type Instrument, type MarketType, type Ticker24h } from '../lib/api';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { rankInstruments } from '../lib/search';
import { displaySymbol } from '../lib/format';

interface Props {
  market: MarketType;
  /** 搜索词（已 trim 过，非空才渲染本组件）。 */
  keyword: string;
  /** 当前市场全量标的（搜索的数据源）。 */
  instruments: Instrument[];
  /** 搜索结果里未自选标的的行情快照，只用来按成交额排序。 */
  snapshot: Record<string, Ticker24h>;
  /** 当前市场的自选条目（既判重也决定置顶）。 */
  items: WatchItem[];
  /** 选中一个标的（**只查看，不自动入自选** —— 加自选走行内那枚 ＋/✓ 按钮）。 */
  onPick: (item: WatchItem) => void;
  onAdd: (item: WatchItem) => void;
  onRemove: (item: WatchItem) => void;
  /** 收起浮层（点击外部 / Esc / 选中之后）。**不清空输入框**。 */
  onClose: () => void;
}

/**
 * 搜索结果下拉框：挂在搜索框下方，跟着输入走。
 *
 * 为什么是浮层而不是侧栏里的一块：搜索是**临时态**。塞进侧栏就等于把它放进一个
 * 常驻容器，输入时自选被冲掉、清空才恢复，每次搜一下都得重新找位置；浮层则
 * 天然「用完就消失」，也不占用侧栏的窄宽度。
 *
 * 键盘是全的（Ctrl+K 进来后不用碰鼠标）：↑/↓ 移动高亮、Enter 选中、Esc 收起。
 * 这些键**挂在 document 上而不是本组件的根节点上** —— 焦点全程在顶部那个输入框里，
 * 事件不会经过这里的 DOM 树，挂在根节点上一条都收不到。
 */
export function SearchDropdown({
  market,
  keyword,
  instruments,
  snapshot,
  items,
  onPick,
  onAdd,
  onRemove,
  onClose,
}: Props) {
  const rootRef = useRef<HTMLDivElement>(null);
  /** 键盘高亮的下标；-1 = 无高亮（还没动过键盘/鼠标）。 */
  const [active, setActive] = useState(-1);

  const watchedKeys = useMemo(() => new Set(items.map(watchKey)), [items]);
  /** 本市场的自选标的（symbol 在当前市场内唯一，判重只看 symbol）。 */
  const watchedSymbols = useMemo(() => new Set(items.map((i) => i.symbol)), [items]);

  const results = useMemo(
    () => rankInstruments(instruments, keyword, { market, watched: watchedKeys, snapshot }),
    [instruments, keyword, market, watchedKeys, snapshot],
  );

  // 关键词一变结果集整个换掉，上一轮的下标没有意义
  useEffect(() => {
    setActive(-1);
  }, [keyword]);

  /**
   * 整行点击 = **只看这一条**，不碰自选（0.1.11 起）：加自选是行内那枚 ＋ 的专职，
   * 顺手把「搜一下」变成「往列表里塞一条」会让人没法只看看。图表面板里也有 ☆/★ 可切换。
   */
  const pick = (inst: Instrument) => {
    onPick({ market, symbol: inst.symbol });
  };

  useEffect(() => {
    // 点击浮层外部收起。用 pointerdown 而不是 click：click 要等抬手，期间落点若在
    // 别的按钮上会先跑那个按钮的行为，观感是「点外面反而点开了别的东西」。
    function onPointerDown(e: PointerEvent) {
      const el = rootRef.current;
      if (el && e.target instanceof Node && !el.contains(e.target)) onClose();
    }
    // Esc / ↑ / ↓ / Enter 都在这儿收：全部 preventDefault，避免顺带滚动页面
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        return;
      }
      if (results.length === 0) return;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const delta = e.key === 'ArrowDown' ? 1 : -1;
        // 无高亮时：↓ 从第一项开始，↑ 直接跳末项
        const next = active < 0 ? (delta > 0 ? 0 : results.length - 1) : active + delta;
        const clamped = (next + results.length) % results.length;
        setActive(clamped);
        rootRef.current?.querySelectorAll('.sr-row')[clamped]?.scrollIntoView({ block: 'nearest' });
        return;
      }
      if (e.key === 'Enter' && active >= 0) {
        e.preventDefault();
        pick(results[active]);
      }
    }
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
    // 刻意**不写依赖数组**：两个处理函数都要读最新的 `active` / `results` / `pick`。
    // 只挂一次（`[]`）会把首次渲染的闭包冻住 —— 高亮永远停在 -1、Enter 选中空。
    // 每轮重挂一次事件监听的开销可以忽略（键盘敲一下才一轮），换来的是没有陈旧闭包。
  });

  return (
    <div className="search-drop" ref={rootRef}>
      <div className="sr-head">
        <span className="overline">搜索结果 · {MARKET_LABEL[market]}</span>
        <span className="sr-count num">{results.length}</span>
      </div>

      <div className="sr-scroll">
        {results.map((inst, i) => {
          const item: WatchItem = { market, symbol: inst.symbol };
          const added = watchedSymbols.has(inst.symbol);
          return (
            <div
              key={watchKey(item)}
              className={`sr-row${i === active ? ' active' : ''}`}
              title={displaySymbol(inst.symbol)}
              onMouseEnter={() => setActive(i)}
              onClick={() => pick(inst)}
            >
              <span className="sr-name">{inst.baseAsset}</span>
              <span className="sr-quote">{inst.quoteAsset}</span>
              <span className="sr-tail">
                <button
                  className={`row-add${added ? ' on' : ''}`}
                  title={
                    added
                      ? `从自选移除 ${displaySymbol(inst.symbol)}`
                      : `把 ${displaySymbol(inst.symbol)} 加入自选`
                  }
                  onClick={(e) => {
                    // 行本身就是「选中」，按钮只管增删，别冒泡上去
                    e.stopPropagation();
                    if (added) onRemove(item);
                    else onAdd(item);
                  }}
                >
                  {added ? '✓' : '＋'}
                </button>
              </span>
            </div>
          );
        })}

        {results.length === 0 && (
          <div className="list-hint">
            {instruments.length === 0
              ? `正在拉取${MARKET_LABEL[market]}标的列表…`
              : `没有匹配「${keyword}」的标的`}
          </div>
        )}
      </div>

      <div className="sr-foot">
        <span>↑↓ 选择</span>
        <span>Enter 打开</span>
        <span>Esc 关闭</span>
      </div>
    </div>
  );
}
