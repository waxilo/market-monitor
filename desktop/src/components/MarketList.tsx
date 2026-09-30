import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MARKET_LABEL, MARKETS, type Instrument, type MarketType } from '../lib/api';
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
  /** 换市场（现货/永续）。切换按钮就长在这一列的顶上。 */
  onMarket: (market: MarketType) => void;
}

/**
 * 侧边栏 = **自选列表**：一行 = 币名 + 计价币 + 当前价格，行尾一个移除按钮。
 * 顶上还有**市场切换**（现货 / 永续）—— 它换的就是这一列显示哪个市场，所以长在这一列头上
 * （0.1.11 从顶栏搬下来的），两个胶囊平摊整行宽。
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
  onMarket,
}: Props) {
  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  /** 拖动算的是 id 顺序，交回调用方时换回条目本身。 */
  const itemMap = useMemo(() => new Map(items.map((i) => [watchKey(i), i])), [items]);
  /** 行右键菜单（「从自选移除」）的落点与对象；`null` = 没开。 */
  const [menu, setMenu] = useState<{ x: number; y: number; item: WatchItem } | null>(null);

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

  /** 行内两列：抽出来给列表行和拖动幽灵共用，幽灵才会长得和它替掉的那一行一模一样。 */
  const renderCells = (item: WatchItem) => {
    const inst = instrumentMap.get(item.symbol);
    const base = inst?.baseAsset ?? item.symbol.split('_')[0];
    const quote = inst?.quoteAsset ?? item.symbol.split('_')[1] ?? '';
    return (
      <>
        <span className="row-name">{base}</span>
        {quote && <span className="row-quote">{quote}</span>}
        <span className="row-tail">{renderPrice(item, inst)}</span>
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
        title={`${displaySymbol(item.symbol)} · 点一下看图表，按住拖动排序，右键移除自选`}
        // 拖完浏览器还会补一下 click：那一下不该再把标的切走
        onClick={() => {
          if (justDragged()) return;
          onSelect(item);
        }}
        // 行上不再挂删除按钮（0.1.11 用户要求撤掉 ×）：移除自选走这条右键菜单
        onContextMenu={(e) => {
          e.preventDefault();
          setMenu({ x: e.clientX, y: e.clientY, item });
        }}
        {...rowProps(key)}
      >
        {renderCells(item)}
      </div>
    );
  };

  return (
    <section className="panel panel-list">
      {/* 顶部这段 = 市场切换 + 自选计数。两个胶囊**平摊整行宽**（分段控件的样子）：
          它换的正是下面这一列显示什么，所以从顶栏搬到了这列的头上。 */}
      <div className="panel-head list-head">
        <div className="market-switch" role="radiogroup" aria-label="市场">
          {MARKETS.map((m) => (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={m === market}
              className={`market-pill${m === market ? ' on' : ''}`}
              onClick={() => onMarket(m)}
            >
              {MARKET_LABEL[m]}
            </button>
          ))}
        </div>
        <span className="overline">自选 · {items.length}</span>
      </div>

      <div className="list-scroll">
        {items.map((item) => renderRow(item))}

        {items.length === 0 && (
          <div className="list-hint">
            自选为空 —— 顶部搜索框搜{MARKET_LABEL[market]}全市场，点结果行的 ＋ 加入
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

      {menu && (
        <RowMenu
          x={menu.x}
          y={menu.y}
          symbol={menu.item.symbol}
          onRemove={() => onRemove(menu.item)}
          onClose={() => setMenu(null)}
        />
      )}
    </section>
  );
}

/**
 * 侧栏行的右键菜单：眼下只有「从自选移除」一条。
 *
 * 为什么撤掉行内那个常显的 ×：用户觉得行上不该挂按钮（0.1.11 明确要求「删除按钮去掉，支持右键移除自选」）。
 * 菜单壳与行为直接沿用图表右键菜单那套（`.chart-menu*` + 贴边翻转 + 点外面/Esc/滚轮就收）——
 * 两处菜单长得一样，用户不用学第二套；单条动作做完立刻收起，和图表菜单同规。
 */
function RowMenu({
  x,
  y,
  symbol,
  onRemove,
  onClose,
}: {
  x: number;
  y: number;
  symbol: string;
  onRemove: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: x, top: y });

  // 贴边翻转：侧栏贴着窗口左边，落点常靠下缘 —— 菜单比光标靠右下时就往左上挪
  useLayoutEffect(() => {
    const w = ref.current?.offsetWidth ?? 176;
    const h = ref.current?.offsetHeight ?? 40;
    setPos({
      left: Math.max(6, Math.min(x, window.innerWidth - w - 6)),
      top: Math.max(6, Math.min(y, window.innerHeight - h - 6)),
    });
  }, [x, y]);

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

  return createPortal(
    <div className="chart-menu" ref={ref} style={{ left: pos.left, top: pos.top }} role="menu">
      <button
        type="button"
        role="menuitem"
        className="chart-menu-item danger"
        onClick={() => {
          onRemove();
          onClose();
        }}
      >
        <span>从自选移除</span>
        <span className="chart-menu-aside">{displaySymbol(symbol)}</span>
      </button>
    </div>,
    document.body,
  );
}
