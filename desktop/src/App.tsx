import { useEffect, useMemo, useRef, useState } from 'react';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { MARKET_LABEL, type Instrument, type MarketType } from './lib/api';
import { endpointOf, spotEndpointOf, useFuturesSourceUrl, useSpotSource } from './lib/sources';
import { useCooldownMs } from './lib/rateGate';
import { formatSpan } from './lib/format';
import { IS_TAURI, switchToMiniWindow } from './lib/tauri';
import { useWindowDrag } from './lib/windowDrag';
import { useShortcut } from './hooks/useShortcut';
import { useGlobalKey } from './hooks/useGlobalKey';
import { watchKey } from './hooks/useWatchlist';
import { useTabs } from './hooks/useTabs';
import { useTickers } from './hooks/useTickers';
import { usePriceAlerts } from './hooks/usePriceAlerts';
import { useSparks } from './hooks/useSparks';
import { useInstruments } from './hooks/useMarketData';
import { TabStrip } from './components/TabStrip';
import { SearchDropdown } from './components/SearchDropdown';
import { EnglishField, type EnglishFieldHandle } from './components/EnglishField';
import { SourcePanel } from './components/SourcePanel';
import { SettingsPanel } from './components/SettingsPanel';
import { ChartView } from './components/ChartView';
import { WindowChrome } from './components/WindowChrome';
import { MiniWindowIcon, SearchIcon } from './components/icons';
import { UpdateReady } from './components/UpdateReady';
import { useUpdate } from './hooks/useUpdate';

type Theme = 'dark' | 'light';

/** 主题：出厂浅色（`[data-theme='light']` 那套变量）；`:root` 仍是深色底 —— 标签页没带
 * `data-theme` 时（首帧脚本执行前）先按深色画，脚本一跑就定成存储里的值。 */
const THEME_KEY = 'mm.theme';

export default function App() {
  // 出厂浅色（`index.html` 里有一段同样判据的首帧脚本 —— 两处必须一致）
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem(THEME_KEY);
    return saved === 'dark' ? 'dark' : 'light';
  });
  const [query, setQuery] = useState('');
  /** 搜索下拉框的显隐。与 `query` 分开：点击外部只收起浮层、保留已输入的关键词。 */
  const [searchOpen, setSearchOpen] = useState(false);
  const searchRef = useRef<EnglishFieldHandle>(null);
  const searchActive = query.trim().length > 0;

  /**
   * 打开的标签页（= 打开过哪些图）。主窗没有「自选」了（0.1.48 起）：
   * 图表的去留由标签页自己决定 —— 用户不关，它就一直在。
   */
  const { tabs, active, activeKey, locked, open, close, closeAll, toggleLock, setActive } = useTabs();
  /**
   * 两个市场的全量标的：搜索是**跨市场**的（不再先选现货还是合约），
   * 图表头部与标签也要按市场取币名 / 精度，所以两份都常备。接口层各只拉一次并缓存。
   */
  const futuresInstruments = useInstruments('FUTURES');
  const spotInstruments = useInstruments('SPOT');
  const instrumentMap = useMemo(() => {
    const map = new Map<string, Instrument>();
    for (const i of futuresInstruments.list) map.set(`FUTURES:${i.symbol}`, i);
    for (const i of spotInstruments.list) map.set(`SPOT:${i.symbol}`, i);
    return map;
  }, [futuresInstruments.list, spotInstruments.list]);

  /** 顶栏空白处按住就能搬窗口（按钮/输入框上不生效，双击最大化）。 */
  const windowDrag = useWindowDrag({ dblClickMaximize: true });

  const update = useUpdate();
  // 价格告警（图上设的告警线 → 系统通知）：只在主窗跑，宿主每 5s 敲一拍（见 hooks/usePriceAlerts）
  usePriceAlerts();
  /** 设置面板的显隐。主题、快捷键、更新都在里面（原来前两个是顶栏的两个按钮）。 */
  const [settingsOpen, setSettingsOpen] = useState(false);
  const futuresSource = useFuturesSourceUrl();
  const spotSource = useSpotSource();
  /** 数据源弹窗的显隐（弹窗自己负责探测与切换，见 SourcePanel）。 */
  const [sourceOpen, setSourceOpen] = useState(false);

  /**
   * 轮询哪些标的 = **打开着的标签页**。图表头部的现价、迷你走势都从这里来；
   * 关掉一个标签页，它就退出轮询（这是「关掉」唯一的副作用，也是它该有的副作用）。
   */
  const { cells, online } = useTickers(tabs);
  const sparks = useSparks(tabs);

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

  // Ctrl/Cmd+K 聚焦搜索：桌面端的肌肉记忆（设置页盖住顶栏时不抢焦点）
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (settingsOpen) return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [settingsOpen]);

  // 悬浮窗里点了某个标的：在主窗打开它的标签页（已经开着就切过去）
  useEffect(() => {
    if (!IS_TAURI) return;
    const unlisten = listen<{ market: MarketType; symbol: string }>('select-symbol', (e) => {
      open({ market: e.payload.market, symbol: e.payload.symbol });
    });
    return () => {
      void unlisten.then((off) => off());
    };
  }, [open]);

  const current = active ? (cells[watchKey(active)]?.data ?? null) : null;
  const instrument = active ? instrumentMap.get(watchKey(active)) : undefined;

  /**
   * 「已连接」与「数据源」合成一个按钮（0.1.16 起）：两者本来就是同一个系统的两面 ——
   * 点开都进数据源弹窗。常态安静（只留 ● 数据源），连接态异常（连接中/断线）才把字说出来；
   * 完整信息（数据源 + 连接态）都在悬停提示里。
   *
   * 状态跟着**当前标签页**的市场走：现货与永续是两个盘口、两套限频，看哪个说哪个。
   */
  const market = active?.market ?? null;
  const sourceLabel =
    market === 'SPOT' ? spotEndpointOf(spotSource).label : endpointOf(futuresSource).label;
  /**
   * 合约 REST 的限频冷却（418/429 后按 retry-after 等）。冷却期实时价仍走 WS，
   * 只是历史 / 清单 / 持仓量拿不到 —— 所以它要单独说出来，不能混成「断线」。
   */
  const cooldownMs = useCooldownMs(market === 'FUTURES' ? futuresSource : null);
  const cooling = market === 'FUTURES' && cooldownMs > 0;
  const connText =
    active == null
      ? '无标签页'
      : cooling
        ? `限频 ${formatSpan(cooldownMs)}`
        : online == null
          ? '连接中'
          : online
            ? '已连接'
            : '断线';
  const connWord = connText === '连接中' || connText === '断线' || cooling ? connText : null;

  return (
    <div className="app">
      <header className="toolbar" {...windowDrag}>
        {/* 顶栏 = 左右两组等宽（`.tb-side`，basis 0 / 等分余量）+ 正中一个搜索框 ——
            两组各占一半余量，搜索框自然落在整条顶栏的正中（见 theme.css 的 .toolbar）。 */}
        <div className="tb-side">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              M
            </span>
            <div className="brand-text">
              <span className="brand-name">Market Monitor</span>
              <span className="overline">行情总览</span>
            </div>
          </div>
        </div>

        {/* 搜索框 + 结果浮层是一组：浮层贴着输入框下沿定位，所以外面要有个定位容器。
            输入即开、点外部/Esc 收起，关键词保留在框里。
            框体是自绘的 EnglishField（非编辑控件）—— 选着中文输入法也不弹候选词、
            字母直接进，见该组件的头注与 README 的搜索框条目。 */}
        <div className="search-wrap">
          <div className="search-field">
            <SearchIcon />
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
              placeholder="搜索现货 + 永续全市场，如 BTC（Ctrl+K）"
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
              keyword={query.trim()}
              futures={futuresInstruments}
              spot={spotInstruments}
              onPick={(item) => {
                open(item);
                setSearchOpen(false);
              }}
              onClose={() => setSearchOpen(false)}
            />
          )}
        </div>

        <div className="tb-side right">
          {/* 「已连接」状态与「数据源」按钮合并成一个（0.1.16 起）：两者本来就是同一个
              系统的两面，点开的入口也只有一个。常态安静 —— 只画 ● 数据源；连接态异常
              （连接中/断线）才把字说出来（见 connWord）。悬停提示里两样信息都保留。 */}
          <button
            className={`icon-btn src-entry${online === false || cooling ? ' bad' : ''}`}
            onClick={() => setSourceOpen(true)}
            title={`数据源：现货与永续分别选择 · 当前 ${sourceLabel}${
              active ? `｜${MARKET_LABEL[active.market]}：${connText}` : ''
            } · 只读${
              cooling
                ? `｜币安系 IP 限频：REST 暂停 ${formatSpan(cooldownMs)}（实时价走 WebSocket，受限 IP 上也可能收不到推送）—— 建议切到独立盘口，如 Aster / OKX / Bybit`
                : ''
            }`}
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
          {/* 设置页是整页视图，自带一份窗口控件与拉伸热区；这里让位，避免两套
              `.rz` 热区叠在同一个窗口边缘上。 */}
          {!settingsOpen && <WindowChrome />}
        </div>
      </header>

      <main className="main">
        <TabStrip
          tabs={tabs}
          activeKey={activeKey}
          locked={locked}
          onActivate={setActive}
          onClose={close}
          onCloseAll={closeAll}
          onToggleLock={toggleLock}
        />
        <ChartView
          item={active}
          ticker={current}
          instrument={instrument}
          spark={active ? sparks[watchKey(active)] : undefined}
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
