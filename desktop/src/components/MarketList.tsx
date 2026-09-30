import { useMemo } from 'react';
import { createPortal } from 'react-dom';
import { MARKET_LABEL, type Instrument, type MarketType } from '../lib/api';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { useDragSort } from '../hooks/useDragSort';
import type { TickerCell } from '../hooks/useTickers';
import { changeClass, displaySymbol, formatPrice } from '../lib/format';

interface Props {
  market: MarketType;
  /** 当前市场的自选条目。 */
  items: WatchItem[];
  /** 当前市场全量标的：只用来把 symbol 拆成币名/计价币与取 tickSize。 */
  instruments: Instrument[];
  /** 自选标的的行情格子（按 `${market}:${symbol}` 索引），行尾价格取自这里。 */
  cells: Record<string, TickerCell>;
  selected: WatchItem | null;
  onSelect: (item: WatchItem) => void;
  onRemove: (item: WatchItem) => void;
  /** 拖动排好的新顺序（**只在当前市场内**，另一市场的条目原地不动；写回见 App）。 */
  onReorder: (next: WatchItem[]) => void;
}

/**
 * 侧边栏 = **自选列表**：一行 = 币名 + 计价币 + 当前价格，行尾一个移除按钮。
 *
 * 整行能拖排序（手感与周期条、悬浮窗配置弹窗同一套，见 `hooks/useDragSort`）。
 * 排序**只在本市场内**：这一列只显示当前市场的自选，拖出来的顺序写回全局列表时
 * 把新顺序填回「本市场那些格子」（见 App 的 reorderWithinMarket），
 * 另一市场的条目原地不动 —— 换市场时不会莫名看到别人被搅乱。
 *
 * 搜索不在这儿了：结果进「搜索下拉框」（`SearchDropdown`），跟着输入框走。
 * 这条分界不是随手挪的 —— 侧栏的职责是「在已收藏的标的里换着看」，它得一直
 * 停在那儿、内容稳定；而搜索结果是**临时的、由输入驱动的**，塞进侧栏会把列表
 * 冲掉，清空关键词才恢复，来回打断视线。两者放在一起，也就势必共用一套行样式，
 * 于是自选行永远得迁就搜索行的形态（比如搜索结果没有价格，自选行就不敢显价格）。
 *
 * 价格只给自选行：`cells` 本来就在为图表轮询，显示它是零额外代价。
 * 涨跌幅不在这列里堆 —— 图表面板头（选中标的）和悬浮窗（全部自选）都有完整读数，
 * 而这一列越窄越好用。
 */
export function MarketList({
  market,
  items,
  instruments,
  cells,
  selected,
  onSelect,
  onRemove,
  onReorder,
}: Props) {
  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  /** 拖动算的是 id 顺序，交回调用方时换回条目本身。 */
  const itemMap = useMemo(() => new Map(items.map((i) => [watchKey(i), i])), [items]);

  const { dragId, ghost, ghostRef, rowProps, justDragged } = useDragSort({
    list: items.map(watchKey),
    flow: 'column', // 侧栏一行一条，落点只看纵坐标
    onChange: (keys) =>
      onReorder(keys.map((k) => itemMap.get(k)).filter((i): i is WatchItem => i != null)),
  });

  const isSelected = (item: WatchItem) =>
    selected != null && selected.market === item.market && selected.symbol === item.symbol;

  /**
   * 行尾价格。**固定占位**：拿不到行情时渲染 `--` 而不是整块塌掉，
   * 否则价格出现/消失会让「币名 + 计价币」左右抖动。
   */
  const renderPrice = (item: WatchItem, inst: Instrument | undefined) => {
    const t = cells[watchKey(item)]?.data;
    if (!t) return <span className="row-price num flat">{'--'}</span>;
    return (
      <span className={`row-price num ${changeClass(t.priceChangePercent)}`}>
        {formatPrice(t.lastPrice, inst?.tickSize)}
      </span>
    );
  };

  /** 行内三列：抽出来给列表行和拖动幽灵共用，幽灵才会长得和它替掉的那一行一模一样。 */
  const renderCells = (item: WatchItem) => {
    const inst = instrumentMap.get(item.symbol);
    const base = inst?.baseAsset ?? item.symbol.split('_')[0];
    const quote = inst?.quoteAsset ?? item.symbol.split('_')[1] ?? '';
    return (
      <>
        <span className="row-name">{base}</span>
        {quote && <span className="row-quote">{quote}</span>}
        <span className="row-tail">
          {renderPrice(item, inst)}
          <button
            className="row-remove"
            title={`从自选移除 ${displaySymbol(item.symbol)}`}
            onClick={(e) => {
              e.stopPropagation();
              onRemove(item);
            }}
          >
            ×
          </button>
        </span>
      </>
    );
  };

  const renderRow = (item: WatchItem) => {
    const key = watchKey(item);
    return (
      <div
        key={key}
        className={`row${isSelected(item) ? ' selected' : ''} drag-row${
          dragId === key ? ' slot' : ''
        }`}
        title={`${displaySymbol(item.symbol)} · 点一下看图表，按住拖动排序`}
        // 拖完浏览器还会补一下 click：那一下不该再把标的切走
        onClick={() => {
          if (justDragged()) return;
          onSelect(item);
        }}
        {...rowProps(key)}
      >
        {renderCells(item)}
      </div>
    );
  };

  return (
    <section className="panel panel-list">
      <div className="panel-head">
        <span className="overline">
          自选 · {MARKET_LABEL[market]} · {items.length}
        </span>
      </div>

      <div className="list-scroll">
        {items.map((item) => renderRow(item))}

        {items.length === 0 && (
          <div className="list-hint">
            自选为空 —— 用顶部搜索框搜{MARKET_LABEL[market]}全市场并加入
          </div>
        )}
      </div>

      {ghost &&
        createPortal(
          <div
            className="row drag-row dragging drag-ghost"
            ref={ghostRef}
            style={{ width: ghost.w, height: ghost.h }}
          >
            {renderCells(itemMap.get(ghost.id) ?? items[0])}
          </div>,
          document.body,
        )}
    </section>
  );
}
