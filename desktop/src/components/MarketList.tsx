import { useMemo, type ReactNode } from 'react';
import { MARKET_LABEL, type Instrument, type MarketType, type Ticker24h } from '../lib/api';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { quotePriority, rankSymbol } from '../lib/search';
import { displaySymbol } from '../lib/format';

/** 搜索结果上限，与 App SearchViewModel.MAX_RESULTS 一致。 */
const MAX_RESULTS = 80;

interface Props {
  market: MarketType;
  /** 当前市场的自选条目。 */
  items: WatchItem[];
  /** 当前市场全量标的（搜索的数据源，也用来把 symbol 拆成币名/计价币）。 */
  instruments: Instrument[];
  /** 搜索结果里未自选标的的行情快照（只在搜索时有，用来按成交额排序）。 */
  snapshot: Record<string, Ticker24h>;
  selected: WatchItem | null;
  /** 搜索词（输入框在顶部工具条里）。 */
  query: string;
  onSelect: (item: WatchItem) => void;
  onAdd: (item: WatchItem) => void;
  onRemove: (item: WatchItem) => void;
}

/**
 * 侧边栏是**窄导航栏**，不是行情表：只列交易对名（币名 + 计价币）。
 *
 * 这里刻意不放现价/涨跌/成交额/迷你走势 —— 那些信息在图表面板头（选中标的）
 * 和悬浮窗（全部自选）里都有，而列表的职责只是「换看哪个标的」，越窄越好用。
 */
export function MarketList({
  market,
  items,
  instruments,
  snapshot,
  selected,
  query,
  onSelect,
  onAdd,
  onRemove,
}: Props) {
  const keyword = query.trim();

  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  const watched = useMemo(() => new Set(items.map(watchKey)), [items]);

  const results = useMemo(() => {
    if (!keyword) return [];
    return (
      instruments
        .map((inst) => ({ inst, rank: rankSymbol(inst.symbol, inst.baseAsset, keyword) }))
        .filter((r): r is { inst: Instrument; rank: number } => r.rank != null)
        // 排序优先级与 App 相同：自选置顶 → 匹配分层 → 计价币 → 成交额 → 交易对名兜底
        .sort((a, b) => {
          const aw = watched.has(watchKey({ market, symbol: a.inst.symbol })) ? 0 : 1;
          const bw = watched.has(watchKey({ market, symbol: b.inst.symbol })) ? 0 : 1;
          return (
            aw - bw ||
            a.rank - b.rank ||
            quotePriority(a.inst.quoteAsset) - quotePriority(b.inst.quoteAsset) ||
            (snapshot[b.inst.symbol]?.quoteVolume ?? 0) -
              (snapshot[a.inst.symbol]?.quoteVolume ?? 0) ||
            a.inst.symbol.localeCompare(b.inst.symbol)
          );
        })
        .slice(0, MAX_RESULTS)
    );
  }, [keyword, instruments, watched, market, snapshot]);

  const isSelected = (item: WatchItem) =>
    selected != null && selected.market === item.market && selected.symbol === item.symbol;

  /** 两种模式共用一行：左边交易对名（币名 + 计价币），行尾一个动作按钮。 */
  const renderRow = (
    item: WatchItem,
    base: string,
    quote: string,
    full: string,
    tail: ReactNode,
  ) => (
    <div
      key={watchKey(item)}
      className={`row${isSelected(item) ? ' selected' : ''}`}
      title={full}
      onClick={() => onSelect(item)}
    >
      <span className="row-name">{base}</span>
      {quote && <span className="row-quote">{quote}</span>}
      <span className="row-tail">{tail}</span>
    </div>
  );

  return (
    <section className="panel panel-list">
      <div className="panel-head">
        <span className="overline">
          {keyword ? '搜索结果' : '自选'} · {MARKET_LABEL[market]} ·{' '}
          {keyword ? results.length : items.length}
        </span>
      </div>

      <div className="list-scroll">
        {keyword
          ? results.map(({ inst }) => {
              const item: WatchItem = { market, symbol: inst.symbol };
              const added = watched.has(watchKey(item));
              return renderRow(
                item,
                inst.baseAsset,
                inst.quoteAsset,
                displaySymbol(inst.symbol),
                <button
                  className={`row-add${added ? ' on' : ''}`}
                  title={added ? `从自选移除 ${displaySymbol(inst.symbol)}` : `把 ${displaySymbol(inst.symbol)} 加入自选`}
                  onClick={(e) => {
                    e.stopPropagation();
                    if (added) onRemove(item);
                    else onAdd(item);
                  }}
                >
                  {added ? '✓' : '＋'}
                </button>,
              );
            })
          : items.map((item) => {
              const inst = instrumentMap.get(item.symbol);
              return renderRow(
                item,
                inst?.baseAsset ?? item.symbol.split('_')[0],
                inst?.quoteAsset ?? item.symbol.split('_')[1] ?? '',
                displaySymbol(item.symbol),
                <button
                  className="row-remove"
                  title={`从自选移除 ${displaySymbol(item.symbol)}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRemove(item);
                  }}
                >
                  ×
                </button>,
              );
            })}

        {keyword && results.length === 0 && (
          <div className="list-hint">
            {instruments.length === 0
              ? `正在拉取${MARKET_LABEL[market]}标的列表…`
              : `没有匹配「${keyword}」的标的`}
          </div>
        )}
        {!keyword && items.length === 0 && (
          <div className="list-hint">自选为空，搜索{MARKET_LABEL[market]}全市场并加入</div>
        )}
      </div>
    </section>
  );
}
