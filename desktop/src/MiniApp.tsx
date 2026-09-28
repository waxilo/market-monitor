import { useEffect, useMemo, useState } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { MARKET_LABEL, type MarketType } from './lib/api';
import { readWatchlist, watchKey, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { useInstruments } from './hooks/useMarketData';
import { changeClass, displaySymbol, formatChange, formatPrice } from './lib/format';
import { IS_TAURI, resizeMiniRows, showMainWindow, switchToMainWindow } from './lib/tauri';
import { syncFuturesSource } from './lib/sources';
import { useWindowDrag } from './lib/windowDrag';
import { CloseIcon, ExpandWindowIcon } from './components/icons';

const MARKET_KEY = 'mm.market';
/**
 * 行数不在这儿截断：≤5 条时面板按条数撑高，>5 条时高度封顶、行区内部滚动
 * （封顶行数在 theme.css 的 `--mini-max-rows`，窗口高度侧对应 Rust 的 `MINI_MAX_ROWS`）。
 * 截断成 5 条会让「固定高度」失去意义 —— 第 6 条之后的自选会直接看不到。
 */
/** 悬浮窗与主窗是两套 React 实例：自选/市场靠轮询 localStorage 同步，主窗改动最迟 4s 反映过来。 */
const SYNC_MS = 4000;
/** 悬浮窗轮询比主窗慢一档，避免两个窗口叠加把 Gate 的限频顶穿。 */
const POLL_MS = 3000;
/** 按住这么久才算「要搬窗口」，之前抬手一律当点击 —— 行本身是按钮，点选必须保留。 */
const HOLD_DRAG_MS = 200;

function readMarket(): MarketType {
  return localStorage.getItem(MARKET_KEY) === 'SPOT' ? 'SPOT' : 'FUTURES';
}

/**
 * 迷你悬浮窗：**只看不摆**——没有常驻标题栏，鼠标移上来才浮出一层遮罩，
 * 上面是市场标签与两个操作按钮（切回主窗 / 收起），平时整块面板都是价格。
 * 点某一行 = 让主窗起来并选中它（两窗互斥，主窗起来时这块自动收起）。
 */
export function MiniApp() {
  const [market, setMarket] = useState<MarketType>(readMarket);
  const [items, setItems] = useState<WatchItem[]>(readWatchlist);
  const rows = useMemo(() => items.filter((i) => i.market === market), [items, market]);
  const drag = useWindowDrag({ holdMs: HOLD_DRAG_MS, blankOnly: false });

  useEffect(() => {
    document.documentElement.dataset.window = 'mini';
    const timer = setInterval(() => {
      setMarket(readMarket());
      setItems(readWatchlist());
      // 主窗里可能刚换了合约数据源：跟进后 useSourceKey 变化会带动取数重来
      syncFuturesSource();
    }, SYNC_MS);
    return () => clearInterval(timer);
  }, []);

  // 窗口高度跟着自选条数走（Rust 按「下边缘不动」调）。只报条数不报高度：
  // 这个窗口启动时是隐藏的，量不到真实高度，条数却是现成的。
  useEffect(() => {
    resizeMiniRows(rows.length);
  }, [rows.length]);

  const instruments = useInstruments(market);
  const instrumentMap = useMemo(
    () => new Map(instruments.map((i) => [i.symbol, i])),
    [instruments],
  );
  const { cells } = useTickers(rows, POLL_MS);

  return (
    <div className="mini" {...drag}>
      <div className="mini-rows">
        {rows.map((item) => {
          const t = cells[watchKey(item)]?.data;
          const inst = instrumentMap.get(item.symbol);
          const cls = changeClass(t?.priceChangePercent);
          return (
            <button
              key={watchKey(item)}
              className="mini-row"
              onClick={() => showMainWindow(item)}
              title={`在主窗口里打开 ${displaySymbol(item.symbol)}`}
            >
              <span className="mini-sym">{inst?.baseAsset ?? displaySymbol(item.symbol)}</span>
              <span className={`mini-price num ${cls}`}>
                {formatPrice(t?.lastPrice, inst?.tickSize)}
              </span>
              <span className={`mini-chg num ${cls}`}>{formatChange(t?.priceChangePercent)}</span>
            </button>
          );
        })}
        {rows.length === 0 && <div className="mini-empty">自选为空，在主窗口里搜索添加</div>}
      </div>

      <div className="mini-overlay">
        <span className="overline">自选 · {MARKET_LABEL[market]}</span>
        <span className="grow" />
        <button className="mini-x" title="切到主窗口" onClick={switchToMainWindow} data-no-drag>
          <ExpandWindowIcon />
        </button>
        <button
          className="mini-x"
          title="隐藏（托盘菜单可再打开）"
          data-no-drag
          onClick={() => {
            if (IS_TAURI) void getCurrentWindow().hide();
          }}
        >
          <CloseIcon />
        </button>
      </div>
    </div>
  );
}
