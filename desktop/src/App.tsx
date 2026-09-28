import { useEffect, useMemo, useRef, useState } from 'react';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { MARKETS, MARKET_LABEL, type MarketType } from './lib/api';
import { endpointOf, useFuturesSourceUrl } from './lib/sources';
import { IS_TAURI, switchToMiniWindow } from './lib/tauri';
import { useWindowDrag } from './lib/windowDrag';
import { useWatchlist, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { useSparks } from './hooks/useSparks';
import { useInstruments, useMarketTickers } from './hooks/useMarketData';
import { MarketList } from './components/MarketList';
import { ChartView } from './components/ChartView';
import { WindowChrome } from './components/WindowChrome';
import { MiniWindowIcon } from './components/icons';
import { SourcePanel } from './components/SourcePanel';
import { UpdatePanel } from './components/UpdatePanel';
import { useUpdate } from './hooks/useUpdate';

type Theme = 'dark' | 'light';

const MARKET_KEY = 'mm.market';

export default function App() {
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('mm.theme');
    return saved === 'light' ? 'light' : 'dark';
  });
  /** 当前市场（App 的 defaultMarket 同样「记住上次选择」）。 */
  const [market, setMarket] = useState<MarketType>(() =>
    localStorage.getItem(MARKET_KEY) === 'SPOT' ? 'SPOT' : 'FUTURES',
  );
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  const { items, add, remove } = useWatchlist();
  const instruments = useInstruments(market);
  const searchActive = query.trim().length > 0;
  const snapshot = useMarketTickers(market, searchActive);
  /** 顶栏空白处按住就能搬窗口（按钮/输入框上不生效，双击最大化）。 */
  const windowDrag = useWindowDrag({ dblClickMaximize: true });

  const marketItems = useMemo(() => items.filter((i) => i.market === market), [items, market]);
  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  const { cells, online } = useTickers(marketItems);
  const sparks = useSparks(marketItems);
  const update = useUpdate();
  const [updateOpen, setUpdateOpen] = useState(false);
  const [sourceOpen, setSourceOpen] = useState(false);
  const futuresSource = useFuturesSourceUrl();
  const sourceLabel = market === 'FUTURES' ? endpointOf(futuresSource).label : 'Gate';
  const [selected, setSelected] = useState<WatchItem | null>(() => items[0] ?? null);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('mm.theme', theme);
    // 原生标题栏跟应用主题走，否则深色界面顶一条白条
    if (IS_TAURI) void getCurrentWindow().setTheme(theme);
  }, [theme]);

  useEffect(() => {
    localStorage.setItem(MARKET_KEY, market);
  }, [market]);

  // Ctrl/Cmd+K 聚焦搜索：桌面端的肌肉记忆
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // 悬浮窗里点了某个标的：切到那个市场并选中它
  useEffect(() => {
    if (!IS_TAURI) return;
    const unlisten = listen<{ market: MarketType; symbol: string }>('select-symbol', (e) => {
      setMarket(e.payload.market);
      setSelected({ market: e.payload.market, symbol: e.payload.symbol });
    });
    return () => {
      void unlisten.then((off) => off());
    };
  }, []);

  // 选中项必须落在当前市场里：换市场、移除被选中的标的都会走到这里
  useEffect(() => {
    if (selected && selected.market === market && marketItems.some((i) => i.symbol === selected.symbol)) {
      return;
    }
    setSelected(marketItems[0] ?? null);
  }, [market, marketItems, selected]);

  const current = useMemo(() => {
    if (!selected) return null;
    return (
      cells[`${selected.market}:${selected.symbol}`]?.data ?? snapshot[selected.symbol] ?? null
    );
  }, [cells, selected, snapshot]);

  const instrument = selected ? instrumentMap.get(selected.symbol) : undefined;

  return (
    <div className="app">
      <header className="toolbar" {...windowDrag}>
        <div className="brand">
          <span className="brand-name">Market Monitor</span>
          <span className="overline">行情总览</span>
        </div>

        <div className="market-switch">
          {MARKETS.map((m) => (
            <button
              key={m}
              className={`market-pill${m === market ? ' on' : ''}`}
              onClick={() => setMarket(m)}
            >
              {MARKET_LABEL[m]}
            </button>
          ))}
        </div>

        <div className="search-field">
          <input
            ref={searchRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setQuery('');
            }}
            placeholder={`搜索${MARKET_LABEL[market]}全市场，如 BTC（Ctrl+K）`}
            spellCheck={false}
          />
          {searchActive && (
            <button className="icon-btn" onClick={() => setQuery('')} title="清空搜索（Esc）">
              清空
            </button>
          )}
        </div>

        <span className="grow" />

        <span className="status" title={`数据源 ${sourceLabel} ${MARKET_LABEL[market]} · 只读`}>
          <span className={`dot${online === false ? ' bad' : ''}`} />
          {marketItems.length === 0
            ? '无自选'
            : online == null
              ? '连接中'
              : online
                ? '已连接'
                : '断线'}
        </span>
        <button
          className="icon-btn"
          onClick={() => setSourceOpen(true)}
          title={`合约数据源：检测各接口延迟并切换（用完后自动收起）· 当前 ${sourceLabel}`}
        >
          数据源
        </button>
        <button
          className="icon-btn icon-only"
          onClick={switchToMiniWindow}
          title="切到悬浮窗：只看自选价格（悬浮窗上的图标切回这里）"
        >
          <MiniWindowIcon />
        </button>
        <button className="icon-btn" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
          {theme === 'dark' ? '浅色' : '深色'}
        </button>
        <button
          className="icon-btn up-entry"
          onClick={() => setUpdateOpen(true)}
          title="应用更新：检查 GitHub 上的新版本"
        >
          更新
          {update.info && <span className="up-badge" />}
        </button>
        <span className="winops-divider" />
        <WindowChrome />
      </header>

      <main className="main">
        <MarketList
          market={market}
          items={marketItems}
          instruments={instruments}
          snapshot={snapshot}
          selected={selected}
          query={query}
          onSelect={setSelected}
          onAdd={(item) => {
            add(item);
            setSelected(item);
          }}
          onRemove={remove}
        />
        <ChartView
          item={selected}
          ticker={current}
          instrument={instrument}
          spark={selected ? sparks[`${selected.market}:${selected.symbol}`] : undefined}
          theme={theme}
        />
      </main>

      {sourceOpen && <SourcePanel onClose={() => setSourceOpen(false)} />}
      {updateOpen && <UpdatePanel controller={update} onClose={() => setUpdateOpen(false)} />}
    </div>
  );
}
