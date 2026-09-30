import { useEffect, useMemo } from 'react';
import { createPortal } from 'react-dom';
import { readWatchlist, useMiniWatchlist, watchKey, type WatchItem } from '../hooks/useWatchlist';
import { useDragSort } from '../hooks/useDragSort';
import { displaySymbol } from '../lib/format';
import { MINI_MAX_ROWS } from '../lib/layout';

interface Props {
  onClose: () => void;
}

/** 行内容：列表里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。
 *  末列按「这一行能做什么」分三种：显示中 → × 摘掉；未加入 → + 加进去；幽灵 → 不画按钮。
 *  三种都保留这一列的位子（`.mw-ops` 定宽）—— 否则「市场」胶囊会跟着按钮的有无左右跳，
 *  幽灵飞起来时和它替掉的那一行也对不齐。 */
function RowInner({
  item,
  onRemove,
  onAdd,
}: {
  item: WatchItem;
  onRemove?: () => void;
  onAdd?: () => void;
}) {
  const [base, quote] = item.symbol.split('_');
  return (
    <>
      <span className="mw-name">{base}</span>
      {quote && <span className="mw-quote num">{quote}</span>}
      <span className="mw-market">{item.market === 'FUTURES' ? '合约' : '现货'}</span>
      <span className="mw-ops">
        {onRemove && (
          <button
            className="mw-btn"
            title={`从悬浮窗移除 ${displaySymbol(item.symbol)}（不删自选）`}
            onClick={onRemove}
          >
            ×
          </button>
        )}
        {onAdd && (
          <button
            className="mw-btn"
            title="加入悬浮窗（放在最后）"
            // 整行也能点（下面写着「点一下加到末尾」），这一层别把事件再冒一遍
            onClick={(e) => {
              e.stopPropagation();
              onAdd();
            }}
          >
            +
          </button>
        )}
      </span>
    </>
  );
}

/**
 * 悬浮窗显示哪几条、按什么顺序。
 *
 * · **显示中**：整行都能拖（手感与周期条同一套，见 `hooks/useDragSort`），键盘 ↑↓ 也能换位；
 *   行尾 × 只把它从悬浮窗摘掉，**不动自选**。
 * · **未加入**：点一下加到末尾。这里只有「已经在自选里的」—— 悬浮窗是自选的一个子集，
 *   要看的标的先搜进自选，两窗共用同一份收藏。
 * · **恢复跟随自选**：删掉单独配置（`mm.miniWatchlist`），回到「悬浮窗 = 整份自选」的默认状态。
 *
 * 改完最迟 4 秒反映到悬浮窗；切过去的那一下也会立刻重读（见 `MiniApp`）。
 */
export function MiniListPanel({ onClose }: Props) {
  /** 自选在打开弹窗时读一次：弹窗只管「从这份收藏里挑」，不在这里增删自选。 */
  const watch = useMemo(() => readWatchlist(), []);
  const mini = useMiniWatchlist();

  const watchMap = useMemo(() => new Map(watch.map((i) => [watchKey(i), i])), [watch]);
  /** 显示中的按存下来的顺序排；已被移出自选的那几条就地滤掉（悬浮窗读的时候也这么滤）。 */
  const shown = useMemo(
    () => mini.items.filter((i) => watchMap.has(watchKey(i))),
    [mini.items, watchMap],
  );
  const rest = useMemo(() => {
    const picked = new Set(shown.map(watchKey));
    return watch.filter((i) => !picked.has(watchKey(i)));
  }, [watch, shown]);

  const { dragId, ghost, ghostRef, rowProps, nudge } = useDragSort({
    list: shown.map(watchKey),
    flow: 'column',
    // 拖拽算的是 id 顺序，交回调用方时换回条目本身
    onChange: (keys) =>
      mini.setItems(keys.map((k) => watchMap.get(k)).filter((i): i is WatchItem => i != null)),
  });

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const pick = (item: WatchItem) =>
    mini.setItems((prev) =>
      prev.some((s) => watchKey(s) === watchKey(item)) ? prev : [...prev, item],
    );
  const drop = (item: WatchItem) =>
    mini.setItems((prev) => prev.filter((s) => watchKey(s) !== watchKey(item)));

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">悬浮窗列表</span>
          <span className="grow" />
          <button className="panel-close" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <p className="up-hint">
            悬浮窗上显示哪几条、按什么顺序都在这里定（顺序就是面板从上到下）。
            超过 {MINI_MAX_ROWS} 条窗口高度封顶，多出的在面板里滚动。
          </p>

          {watch.length === 0 ? (
            <p className="up-hint">自选是空的 —— 先用顶栏搜索框把标的加进自选。</p>
          ) : (
            <>
              <div className="up-label">
                显示中 · {shown.length} 条 · 直接拖动整行排序（也可用 ↑ ↓）
              </div>
              {shown.length === 0 ? (
                <p className="set-sub">一条都没勾：悬浮窗只显示一行「列表为空」。</p>
              ) : (
                <div className="mw-scroll">
                  {shown.map((item) => (
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

              {rest.length > 0 && (
                <>
                  <div className="up-label">未加入 · 点一下加到末尾</div>
                  <div className="mw-scroll">
                    {rest.map((item) => (
                      <div
                        className="mw-row pick"
                        key={watchKey(item)}
                        title={`加入悬浮窗（放在最后）· ${displaySymbol(item.symbol)}`}
                        onClick={() => pick(item)}
                      >
                        <RowInner item={item} onAdd={() => pick(item)} />
                      </div>
                    ))}
                  </div>
                </>
              )}

              <div className="up-actions">
                <button
                  className="up-btn"
                  disabled={rest.length === 0}
                  title="把全部自选都显示在悬浮窗里"
                  onClick={() => mini.setItems(watch)}
                >
                  全部显示
                </button>
                <button
                  className="up-btn"
                  title="取消单独配置，悬浮窗重新显示整份自选"
                  onClick={() => mini.reset()}
                >
                  恢复跟随自选
                </button>
              </div>
            </>
          )}
        </div>
      </section>

      {ghost &&
        createPortal(
          <div
            className="mw-row drag-row dragging drag-ghost"
            ref={ghostRef}
            style={{ width: ghost.w, height: ghost.h }}
          >
            <RowInner item={watchMap.get(ghost.id) ?? shown[0]} />
          </div>,
          document.body,
        )}
    </div>
  );
}
