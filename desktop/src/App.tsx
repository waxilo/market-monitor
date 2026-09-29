import { useEffect, useMemo, useRef, useState } from 'react';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { MARKETS, MARKET_LABEL, type MarketType } from './lib/api';
import { endpointOf, useFuturesSourceUrl } from './lib/sources';
import { IS_TAURI, switchToMiniWindow } from './lib/tauri';
import { useWindowDrag } from './lib/windowDrag';
import { useShortcut } from './hooks/useShortcut';
import { useWatchlist, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { useSparks } from './hooks/useSparks';
import { useInstruments, useMarketTickers } from './hooks/useMarketData';
import { MarketList } from './components/MarketList';
import { SearchDropdown } from './components/SearchDropdown';
import { SourcePanel } from './components/SourcePanel';
import { SettingsPanel } from './components/SettingsPanel';
import { ChartView } from './components/ChartView';
import { WindowChrome } from './components/WindowChrome';
import { MiniWindowIcon, SidebarIcon } from './components/icons';
import { UpdateReady } from './components/UpdateReady';
import { useUpdate } from './hooks/useUpdate';

type Theme = 'dark' | 'light';

const MARKET_KEY = 'mm.market';
/** 侧栏展开/收起（`0` = 收起）。收起时图表吃掉整行宽度。 */
const LIST_OPEN_KEY = 'mm.listOpen';
/** 主题：深色是原生底色（`:root`），浅色靠 `[data-theme='light']` 覆盖一套变量。 */
const THEME_KEY = 'mm.theme';

export default function App() {
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem(THEME_KEY);
    return saved === 'light' ? 'light' : 'dark';
  });
  /** 当前市场（App 的 defaultMarket 同样「记住上次选择」）。 */
  const [market, setMarket] = useState<MarketType>(() =>
    localStorage.getItem(MARKET_KEY) === 'SPOT' ? 'SPOT' : 'FUTURES',
  );
  const [query, setQuery] = useState('');
  /** 左侧自选栏的显隐。收起只改布局（见 theme.css 的 `.main`），列表状态一概不动。 */
  const [listOpen, setListOpen] = useState(() => localStorage.getItem(LIST_OPEN_KEY) !== '0');
  /** 搜索下拉框的显隐。与 `query` 分开：点击外部只收起浮层、保留已输入的关键词。 */
  const [searchOpen, setSearchOpen] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const { items, add, remove } = useWatchlist();
  const instruments = useInstruments(market);
  const searchActive = query.trim().length > 0;
  // 全量快照只为搜索结果的成交额排序服务：不进搜索就不拉
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
  /** 设置面板的显隐。主题、快捷键、更新都在里面（原来前两个是顶栏的两个按钮）。 */
  const [settingsOpen, setSettingsOpen] = useState(false);
  const futuresSource = useFuturesSourceUrl();
  const sourceLabel = market === 'FUTURES' ? endpointOf(futuresSource).label : 'Gate';
  /** 数据源弹窗的显隐（弹窗自己负责探测与切换，见 SourcePanel）。 */
  const [sourceOpen, setSourceOpen] = useState(false);
  const [selected, setSelected] = useState<WatchItem | null>(() => items[0] ?? null);

  // 主窗 ⇄ 悬浮窗：默认 Alt+M，可在设置里改（判定与落盘见 lib/shortcuts.ts）。
  useShortcut('toggleWindow', switchToMiniWindow);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem(THEME_KEY, theme);
    // 原生标题栏跟应用主题走，否则深色界面顶一条白条
    if (IS_TAURI) void getCurrentWindow().setTheme(theme);
  }, [theme]);

  useEffect(() => {
    localStorage.setItem(MARKET_KEY, market);
  }, [market]);

  useEffect(() => {
    localStorage.setItem(LIST_OPEN_KEY, listOpen ? '1' : '0');
  }, [listOpen]);

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

        {/* 侧栏开关：收起后图表整行铺开（列宽动画见 theme.css 的 .main）。
            放在顶栏而不是侧栏里 —— 收起之后侧栏整个不在了，开关必须留在外面。 */}
        <button
          className="icon-btn icon-only list-toggle"
          onClick={() => setListOpen((v) => !v)}
          title={listOpen ? '收起自选侧栏（图表占满宽度）' : '展开自选侧栏'}
        >
          <SidebarIcon open={listOpen} />
        </button>

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

        {/* 搜索框 + 结果浮层是一组：浮层贴着输入框下沿定位，所以外面要有个定位容器。
            输入即开、点外部/Esc 收起，关键词保留在框里。 */}
        <div className="search-wrap">
          <div className="search-field">
            <input
              ref={searchRef}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setSearchOpen(true);
              }}
              onFocus={() => setSearchOpen(true)}
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  setQuery('');
                  setSearchOpen(false);
                }
              }}
              placeholder={`搜索${MARKET_LABEL[market]}全市场，如 BTC（Ctrl+K）`}
              spellCheck={false}
            />
            {searchActive && (
              <button
                className="icon-btn"
                onClick={() => {
                  setQuery('');
                  setSearchOpen(false);
                  searchRef.current?.focus();
                }}
                title="清空搜索（Esc）"
              >
                清空
              </button>
            )}
          </div>

          {searchOpen && searchActive && (
            <SearchDropdown
              market={market}
              keyword={query.trim()}
              instruments={instruments}
              snapshot={snapshot}
              items={marketItems}
              onPick={(item) => {
                setSelected(item);
                setSearchOpen(false);
              }}
              onAdd={add}
              onRemove={remove}
              onClose={() => setSearchOpen(false)}
            />
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
          title={`合约数据源：检测各接口延迟并切换 · 当前 ${sourceLabel}`}
        >
          数据源
        </button>
        {/* 主题、快捷键、更新都在这一个入口里（`title` 会跟着更新状态变），
            顶栏右端因此只剩「数据源 | 设置」两个内容按钮。
            圆点 = 更新的存在感：自动下载本身没有声音，这里必须看得见（见 .up-badge）。 */}
        <button
          className="icon-btn up-entry"
          onClick={() => setSettingsOpen(true)}
          title={
            update.ready
              ? `设置：v${update.readyVersion} 已下载好，点这里进去安装`
              : '设置：主题 / 快捷键 / 更新'
          }
        >
          设置
          {update.info && (
            <span
              className="up-badge"
              data-state={update.download.state === 'running' ? 'running' : update.ready ? 'ready' : 'idle'}
            />
          )}
        </button>
        <span className="winops-divider" />
        {/* 悬浮窗开关放在窗口控件组的最左（紧邻最小化）：它和「最小化」是同一类操作
            —— 都是把窗口收起来，跟左边的设置（改的是应用内容）不同组。
            不能塞进 WindowChrome，那个组件只管窗口控件与拉伸热区。 */}
        <button
          className="icon-btn icon-only mini-entry"
          onClick={switchToMiniWindow}
          title="切到悬浮窗：只看自选价格（在悬浮窗上点一下切回这里，Alt+M 也行）"
        >
          <MiniWindowIcon />
        </button>
        <WindowChrome />
      </header>

      <main className={`main${listOpen ? '' : ' list-collapsed'}`}>
        <MarketList
          market={market}
          items={marketItems}
          instruments={instruments}
          cells={cells}
          selected={selected}
          onSelect={setSelected}
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

      {/* 数据源弹窗：打开即并行探测全部候选，点一行就切换（交互见 SourcePanel）。 */}
      {sourceOpen && <SourcePanel onClose={() => setSourceOpen(false)} />}
      {settingsOpen && (
        <SettingsPanel
          theme={theme}
          onTheme={setTheme}
          update={update}
          onClose={() => setSettingsOpen(false)}
        />
      )}
      {/* 自动下载把下载过程变成了无声的，这条提示就是它唯一的出口 —— 别删。
          「稍后」之后仍可从设置里装（设置按钮上的圆点也会变回 idle）。 */}
      {update.ready && <UpdateReady controller={update} />}
    </div>
  );
}
