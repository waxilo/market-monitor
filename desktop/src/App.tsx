import { useEffect, useMemo, useRef, useState } from 'react';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { MARKET_LABEL, type MarketType } from './lib/api';
import { endpointOf, useFuturesSourceUrl } from './lib/sources';
import { IS_TAURI, switchToMiniWindow } from './lib/tauri';
import { useWindowDrag } from './lib/windowDrag';
import { useShortcut } from './hooks/useShortcut';
import { useGlobalKey } from './hooks/useGlobalKey';
import { useWatchlist, watchKey, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { usePriceAlerts } from './hooks/usePriceAlerts';
import { useSparks } from './hooks/useSparks';
import { useInstruments, useMarketTickers } from './hooks/useMarketData';
import { MarketList } from './components/MarketList';
import { SearchDropdown } from './components/SearchDropdown';
import { EnglishField, type EnglishFieldHandle } from './components/EnglishField';
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
/** 主题：出厂浅色（`[data-theme='light']` 那套变量）；`:root` 仍是深色底 —— 标签页没带
 * `data-theme` 时（首帧脚本执行前）先按深色画，脚本一跑就定成存储里的值。 */
const THEME_KEY = 'mm.theme';

export default function App() {
  // 出厂浅色（`index.html` 里有一段同样判据的首帧脚本 —— 两处必须一致）
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem(THEME_KEY);
    return saved === 'dark' ? 'dark' : 'light';
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
  const searchRef = useRef<EnglishFieldHandle>(null);
  const { items, setItems, add, remove } = useWatchlist();
  const instruments = useInstruments(market);
  const searchActive = query.trim().length > 0;
  // 全量快照只为搜索结果的成交额排序服务：不进搜索就不拉
  const snapshot = useMarketTickers(market, searchActive);
  /** 顶栏空白处按住就能搬窗口（按钮/输入框上不生效，双击最大化）。 */
  const windowDrag = useWindowDrag({ dblClickMaximize: true });

  const marketItems = useMemo(() => items.filter((i) => i.market === market), [items, market]);
  /**
   * 侧栏拖动排序的写回：侧栏只看得到当前市场那几条，所以拖出来的是**本市场内**的顺序。
   * 把它填回全局列表时只动「当前市场占据的那些位置」，另一市场的条目原地不动 ——
   * 换市场时不会看到自己没拖过的列表被搅乱。
   */
  const reorderWithinMarket = (nextVisible: WatchItem[]) => {
    setItems((prev) => {
      const slots: number[] = [];
      prev.forEach((it, idx) => {
        if (it.market === market) slots.push(idx);
      });
      // 拖动过程中另一边加了/删了自选（搜索面板能这么干）：对不上就放弃这次写回
      if (slots.length !== nextVisible.length) return prev;
      const next = [...prev];
      slots.forEach((pos, i) => {
        next[pos] = nextVisible[i];
      });
      return next;
    });
  };
  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  const update = useUpdate();
  // 价格告警（右键设的告警线 → 系统通知）：只在主窗跑，宿主每 5s 敲一拍（见 hooks/usePriceAlerts）
  usePriceAlerts();
  /** 设置面板的显隐。主题、快捷键、更新都在里面（原来前两个是顶栏的两个按钮）。 */
  const [settingsOpen, setSettingsOpen] = useState(false);
  const futuresSource = useFuturesSourceUrl();
  const sourceLabel = market === 'FUTURES' ? endpointOf(futuresSource).label : 'Gate';
  /** 数据源弹窗的显隐（弹窗自己负责探测与切换，见 SourcePanel）。 */
  const [sourceOpen, setSourceOpen] = useState(false);
  const [selected, setSelected] = useState<WatchItem | null>(() => items[0] ?? null);
  /**
   * 轮询哪些标的 = 当前市场自选 + 「正在看但不是自选」的那一条。
   * 悬浮窗列表独立于自选（0.1.16 起），点它的行切到主窗时这一条可能不在自选里，
   * 图表头部的现价、迷你走势也得跟着来（侧栏按 key 取价，多一条互不影响）。
   */
  const tickItems = useMemo(() => {
    if (selected == null || selected.market !== market) return marketItems;
    return marketItems.some((i) => watchKey(i) === watchKey(selected))
      ? marketItems
      : [...marketItems, selected];
  }, [marketItems, selected, market]);
  const { cells, online } = useTickers(tickItems);
  const sparks = useSparks(tickItems);

  // 主窗 ⇄ 悬浮窗：默认 Alt+D，可在设置里改（判定与落盘见 lib/shortcuts.ts）。
  // 这条是**系统级**的：主窗负责把落盘的和弦推给宿主（只有主窗推，见 hooks/useGlobalKey），
  // 归宿主管了以后本窗口就不必再绑一份网页监听。
  const globalTaken = useGlobalKey('toggleWindow');
  useShortcut('toggleWindow', switchToMiniWindow, !globalTaken);

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

  // 选中项只要求「属于当前市场」：搜索里点一条不入自选也能直接看（搜索行只查看，加自选走行内 ＋），
  // 正在看的那条被删掉（行内 × / 详情页 ☆）图也不动 —— 所以不再检查它在不在自选里。
  // 真正需要回落的只剩换市场（自选清空 ⇒ marketItems[0] 为 undefined ⇒ 回到空图）。
  useEffect(() => {
    if (selected == null || selected.market !== market) {
      setSelected(marketItems[0] ?? null);
    }
  }, [market, marketItems, selected]);

  const current = useMemo(() => {
    if (!selected) return null;
    return (
      cells[`${selected.market}:${selected.symbol}`]?.data ?? snapshot[selected.symbol] ?? null
    );
  }, [cells, selected, snapshot]);

  const instrument = selected ? instrumentMap.get(selected.symbol) : undefined;
  /** 详情页那枚 ☆/★ 的状态：当前标的在不在自选里。 */
  const watched = selected != null && items.some((i) => watchKey(i) === watchKey(selected));
  /** 详情页星标：★ → ☆ 从自选删掉（左侧列表里那条也消失），☆ → ★ 加回来。 */
  const toggleWatch = (it: WatchItem) => {
    if (items.some((i) => watchKey(i) === watchKey(it))) remove(it);
    else add(it);
  };

  /**
   * 「已连接」与「数据源」合成一个按钮（0.1.16 起）：两者本来就是同一个系统的两面 ——
   * 点开都进数据源弹窗。常态安静（只留 ● 数据源），连接态异常（连接中/断线）才把字说出来；
   * 完整信息（数据源 + 连接态）都在悬停提示里。
   */
  const connText =
    marketItems.length === 0 ? '无自选' : online == null ? '连接中' : online ? '已连接' : '断线';
  const connWord = connText === '连接中' || connText === '断线' ? connText : null;

  return (
    <div className="app">
      <header className="toolbar" {...windowDrag}>
        {/* 顶栏 = 左右两组等宽（`.tb-side`，basis 0 / 等分余量）+ 正中一个搜索框 ——
            两组各占一半余量，搜索框自然落在整条顶栏的正中（见 theme.css 的 .toolbar）。 */}
        <div className="tb-side">
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
        </div>

        {/* 市场切换（现货/永续）挪到侧栏顶部了 —— 它换的是「左边这一列显示哪个市场」，
            放在它管着的那列上面比搁顶栏贴切（见 MarketList 的 panel-head）。 */}

        {/* 搜索框 + 结果浮层是一组：浮层贴着输入框下沿定位，所以外面要有个定位容器。
            输入即开、点外部/Esc 收起，关键词保留在框里。
            框体是自绘的 EnglishField（非编辑控件）—— 选着中文输入法也不弹候选词、
            字母直接进，见该组件的头注与 README 的搜索框条目。 */}
        <div className="search-wrap">
          <div className="search-field">
            <EnglishField
              ref={searchRef}
              value={query}
              onChange={(next) => {
                setQuery(next);
                setSearchOpen(true);
              }}
              onFocus={() => setSearchOpen(true)}
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  setQuery('');
                  setSearchOpen(false);
                }
                // 浮层开着时 ↑↓ 归列表翻高亮（SearchDropdown 挂在 document 上收 ——
                // preventDefault 拦不住它上行；这儿的 preventDefault 只是告诉
                // EnglishField「别把 ↑↓ 当 Home/End 用」，与原生单行框一致）
                if (searchOpen && searchActive && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) e.preventDefault();
              }}
              placeholder={`搜索${MARKET_LABEL[market]}全市场，如 BTC（Ctrl+K）`}
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

        <div className="tb-side right">
          {/* 「已连接」状态与「数据源」按钮合并成一个（0.1.16 起）：两者本来就是同一个
              系统的两面，点开的入口也只有一个。常态安静 —— 只画 ● 数据源；连接态异常
              （连接中/断线）才把字说出来（见 connWord）。悬停提示里两样信息都保留。 */}
          <button
            className={`icon-btn src-entry${online === false ? ' bad' : ''}`}
            onClick={() => setSourceOpen(true)}
            title={`合约数据源：检测各接口延迟并切换 · 当前 ${sourceLabel}｜${MARKET_LABEL[market]}：${connText} · 只读`}
          >
            <span className="dot" />
            {connWord ? `${connWord} · 数据源` : '数据源'}
          </button>
          {/* 主题、快捷键、悬浮窗列表、更新都在这一个入口里（`title` 会跟着更新状态变），
              圆点 = 更新的存在感：自动下载本身没有声音，这里必须看得见（见 .up-badge）。 */}
          <button
            className="icon-btn up-entry"
            onClick={() => setSettingsOpen(true)}
            title={
              update.ready
                ? `设置：v${update.readyVersion} 已下载好，点这里进去安装`
                : '设置：主题 / 悬浮窗列表 / 快捷键 / 告警通知 / 更新'
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
            title="切到悬浮窗：只看悬浮窗列表里的那几条（显示哪些去设置 → 悬浮窗里搜，点悬浮窗切回这里，Alt+D 也行）"
          >
            <MiniWindowIcon />
          </button>
          <WindowChrome />
        </div>
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
          onReorder={reorderWithinMarket}
          onMarket={setMarket}
        />
        <ChartView
          item={selected}
          ticker={current}
          instrument={instrument}
          spark={selected ? sparks[`${selected.market}:${selected.symbol}`] : undefined}
          theme={theme}
          watched={watched}
          onToggleWatch={toggleWatch}
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
