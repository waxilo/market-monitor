import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { displaySymbol } from '../lib/format';
import { MINI_MAX_ROWS } from '../lib/layout';
import { rankAcrossMarkets, type MarketHit } from '../lib/search';
import { EnglishField } from './EnglishField';
import { useInstruments } from '../hooks/useMarketData';
import { useMiniWatchlist, watchKey, type WatchItem } from '../hooks/useWatchlist';
import { useDragSort } from '../hooks/useDragSort';

/**
 * 「悬浮窗」这一整段：悬浮窗里显示哪几条、按什么顺序。
 *
 * **左右两栏**（0.1.16 起）：左边 = 「显示中」列表（顺序即面板顺序），右边 = 搜索添加。
 * 列表**独立于自选** —— 增删/排序都不动左侧自选；升级上来的机器第一次读会拷一份
 * 当前自选当出厂值（见 `readMiniWatchlist`）。
 *
 * 搜索是**两个市场混搜**：悬浮窗面板本来就现货/合约混排，每次只搜一边还得先想
 * 「它在哪个市场」；结果行带现货/合约胶囊区分同名标的（BTC_USDT 两边都有）。
 * 键盘是全的（与主窗搜索下拉框同一套按键）：↑↓ 高亮（循环）、Enter 添加高亮那条
 * （没高亮时退回「加第一条未加入的」）、Esc 清词。点结果行仍按整行语义切换加/移。
 *
 * 改完最迟 4 秒反映到悬浮窗；切过去的那一下也会立刻重读（见 `MiniApp`）。
 */
export function MiniListSection() {
  const mini = useMiniWatchlist();
  const [query, setQuery] = useState('');
  /** 键盘高亮的搜索结果下标；-1 = 无高亮（还没动过键盘/鼠标）。 */
  const [active, setActive] = useState(-1);
  /** 结果滚动区：↑↓ 换高亮时把那一行滚进来（与 `.mw-row.pick` 的顺序一一对应）。 */
  const resultsRef = useRef<HTMLDivElement>(null);

  // 两个市场各拉一份全量清单（api 层有缓存，合约那份通常主窗本来就拉着）
  const futures = useInstruments('FUTURES');
  const spot = useInstruments('SPOT');
  const hits = useMemo(
    () =>
      rankAcrossMarkets(
        [
          { market: 'FUTURES' as const, instruments: futures },
          { market: 'SPOT' as const, instruments: spot },
        ],
        query.trim(),
      ),
    [futures, spot, query],
  );

  // 关键词一变结果集整个换掉，上一轮的下标没有意义
  useEffect(() => {
    setActive(-1);
  }, [query]);

  const byKey = useMemo(() => new Map(mini.items.map((i) => [watchKey(i), i])), [mini.items]);
  const inList = useMemo(() => new Set(byKey.keys()), [byKey]);

  const pick = (hit: MarketHit) => {
    const item: WatchItem = { market: hit.market, symbol: hit.inst.symbol };
    mini.setItems((prev) =>
      prev.some((s) => watchKey(s) === watchKey(item)) ? prev : [...prev, item],
    );
  };
  const drop = (item: WatchItem) =>
    mini.setItems((prev) => prev.filter((s) => watchKey(s) !== watchKey(item)));

  const { dragId, ghost, ghostRef, rowProps, nudge } = useDragSort({
    list: mini.items.map(watchKey),
    flow: 'column',
    onChange: (keys) =>
      mini.setItems(keys.map((k) => byKey.get(k)).filter((i): i is WatchItem => i != null)),
  });

  const ghostItem = ghost ? byKey.get(ghost.id) : undefined;

  return (
    <div className="set-block-body">
      <p className="up-hint">
        悬浮窗上显示哪几条、按什么顺序都在这里定（顺序就是面板从上到下），
        和左侧自选互不影响。超过 {MINI_MAX_ROWS} 条窗口高度封顶，多出的在面板里滚动。
      </p>

      <div className="mini-split">
        {/* 左栏＝显示中：整行可拖（也能 ↑↓ 微调），× 摘掉 */}
        <div className="mini-col">
          <div className="up-label">
            显示中 · {mini.items.length} 条 · 拖动整行排序（也可用 ↑ ↓）
          </div>
          {mini.items.length === 0 ? (
            <p className="set-sub">一条都没有 —— 在右边搜索添加。</p>
          ) : (
            <div className="mw-scroll">
              {mini.items.map((item) => (
                <div
                  className={`mw-row drag-row${dragId === watchKey(item) ? ' slot' : ''}`}
                  key={watchKey(item)}
                  tabIndex={0}
                  role="listitem"
                  title="按住这一行任意处拖动排序（也可用 ↑ ↓）"
                  {...rowProps(watchKey(item))}
                  onKeyDown={(e) => {
                    if (e.key === 'ArrowUp') {
                      e.preventDefault();
                      nudge(watchKey(item), -1);
                    }
                    if (e.key === 'ArrowDown') {
                      e.preventDefault();
                      nudge(watchKey(item), 1);
                    }
                  }}
                >
                  <RowInner item={item} onRemove={() => drop(item)} />
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 右栏＝搜索添加：键盘全在这一栏里（焦点始终在搜索框上，↑↓/Enter 走它的
            onKeyDown；框体是自绘的 EnglishField —— 非编辑控件，输入法不弹候选词） */}
        <div className="mini-col">
          <div className="up-label">搜索添加 · ↑↓ 选择 · Enter 添加</div>
          <EnglishField
            className="ms-input"
            value={query}
            onChange={setQuery}
            onKeyDown={(e) => {
              if (e.key === 'Escape' && query !== '') {
                // 只清关键词：这一下别把整个设置弹窗也关了（面板的 Esc 挂在 window 上）
                e.stopPropagation();
                setQuery('');
                return;
              }
              if (hits.length === 0) return;
              if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                const delta = e.key === 'ArrowDown' ? 1 : -1;
                // 无高亮时：↓ 从第一项开始，↑ 直接跳末项（与主窗搜索下拉框同款）
                const next = active < 0 ? (delta > 0 ? 0 : hits.length - 1) : active + delta;
                const clamped = (next + hits.length) % hits.length;
                setActive(clamped);
                resultsRef.current
                  ?.querySelectorAll('.mw-row.pick')[clamped]?.scrollIntoView({ block: 'nearest' });
                return;
              }
              if (e.key === 'Enter') {
                // 高亮哪条加哪条（已在列表里的按下去就是去重后的原样，不动它）；
                // 还没动过键盘才退回老行为 —— 加第一条未加入的
                const hit =
                  active >= 0
                    ? hits[active]
                    : hits.find((h) => !inList.has(watchKey({ market: h.market, symbol: h.inst.symbol })));
                if (hit) pick(hit);
              }
            }}
            placeholder="搜索现货 / 永续全市场，如 BTC"
          />

          {query.trim() !== '' && (
            <div
              className="mw-scroll ms-results"
              ref={resultsRef}
              // 点结果行后焦点留在输入框（默认 mousedown 会把焦点抢到行/按钮上，键盘流就断了）
              onMouseDown={(e) => e.preventDefault()}
            >
              {hits.map((hit, i) => {
                const item: WatchItem = { market: hit.market, symbol: hit.inst.symbol };
                const added = inList.has(watchKey(item));
                return (
                  <div
                    className={`mw-row pick${added ? ' on' : ''}${i === active ? ' active' : ''}`}
                    key={watchKey(item)}
                    title={
                      added
                        ? `从悬浮窗移除 ${displaySymbol(hit.inst.symbol)}`
                        : `把 ${displaySymbol(hit.inst.symbol)} 加到悬浮窗（放在最后）`
                    }
                    onMouseEnter={() => setActive(i)}
                    onClick={() => (added ? drop(item) : pick(hit))}
                  >
                    <RowInner
                      item={item}
                      onAdd={added ? undefined : () => pick(hit)}
                      done={added ? () => drop(item) : undefined}
                    />
                  </div>
                );
              })}
              {hits.length === 0 && (
                <p className="set-sub">
                  {futures.length + spot.length === 0
                    ? '正在拉取标的列表…'
                    : `没有匹配「${query.trim()}」的标的`}
                </p>
              )}
            </div>
          )}
        </div>
      </div>

      {ghost && ghostItem && createPortal(
        <div
          className="mw-row drag-row dragging drag-ghost"
          ref={ghostRef}
          style={{ width: ghost.w, height: ghost.h }}
        >
          <RowInner item={ghostItem} />
        </div>,
        document.body,
      )}
    </div>
  );
}

/** 行内容：列表行、搜索结果行与拖动幽灵共用同一套 DOM，免得三处样式分叉。
 *  末列按「这一行能做什么」分三种：显示中 → × 摘掉；未加入 → ＋ 加进去；幽灵 → 不画按钮。
 *  三种都保留这一列的位子（`.mw-ops` 定宽）—— 否则「市场」胶囊会跟着按钮的有无左右跳，
 *  幽灵飞起来时和它替掉的那一行也对不齐。 */
function RowInner({
  item,
  onRemove,
  onAdd,
  done,
}: {
  item: WatchItem;
  onRemove?: () => void;
  onAdd?: () => void;
  /** 搜索行「已在列表里」：显示 ✓（点了把它摘掉）。 */
  done?: () => void;
}) {
  const [base, quote] = item.symbol.split('_');
  return (
    <>
      <span className="mw-name">{base}</span>
      {quote && <span className="mw-quote num">{quote}</span>}
      <span className="mw-market">{item.market === 'FUTURES' ? '永续' : '现货'}</span>
      <span className="mw-ops">
        {onRemove && (
          <button
            className="mw-btn"
            title={`从悬浮窗移除 ${displaySymbol(item.symbol)}`}
            onClick={onRemove}
          >
            ×
          </button>
        )}
        {onAdd && (
          <button
            className="row-add"
            title="加到悬浮窗（放在最后）"
            // 整行也能点（搜索结果整行是加/移的入口），这一层别把事件再冒一遍
            onClick={(e) => {
              e.stopPropagation();
              onAdd();
            }}
          >
            ＋
          </button>
        )}
        {done && (
          <button
            className="row-add on"
            title="从悬浮窗移除"
            onClick={(e) => {
              e.stopPropagation();
              done();
            }}
          >
            ✓
          </button>
        )}
      </span>
    </>
  );
}
