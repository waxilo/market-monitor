import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  // 别名：`MouseEvent` 这个名字被 DOM 全局类型占着，直接 import 会把它遮掉
  type MouseEvent as ReactMouseEvent,
} from 'react';
import type { MarketType } from './lib/api';
import { readWatchlist, watchKey, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { useInstruments } from './hooks/useMarketData';
import { changeClass, displaySymbol, formatChange, formatPrice } from './lib/format';
import { openMiniMenu, resizeMiniRows, showMainWindow, switchToMainWindow } from './lib/tauri';
import { syncFuturesSource } from './lib/sources';
import { MINI_MAX_ROWS, MINI_ROW_HEIGHT } from './lib/layout';
import { usePointerInside } from './hooks/usePointerInside';
import { useWindowDrag } from './lib/windowDrag';

const MARKET_KEY = 'mm.market';
/**
 * 行数不在这儿截断：≤6 条时面板按条数撑高，>6 条时高度封顶、行区内部滚动
 * （行高与封顶行数由 `lib/layout.ts` 注入成 CSS 变量，窗口高度侧对齐 Rust 的
 * `MINI_ROW_H` / `MINI_MAX_ROWS`）。
 * 按封顶行数截断会让「固定高度」失去意义 —— 第 7 条之后的自选会直接看不到。
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
 * 迷你悬浮窗：**只看不摆** —— 没有标题栏、也没有遮罩层，整块面板都是价格。
 *
 * **左键：整块面板就是「换到主窗」的入口。** 点某一行 = 主窗起来并选中该标的；
 * 点空白（含自选为空时的提示）＝ 只切到主窗（两窗互斥，主窗起来时这块自动收起）。
 * 行仍然是 `button`，各自带更具体的语义（要带上自己的标的），所以面板级的
 * `onClick` 要按 `closest('button')` 让位。
 *
 * **右键：弹原生菜单**（目前一条「隐藏悬浮窗」）。菜单只能由宿主弹 ——
 * 悬浮窗就面板那么大，画在 webview 里的 HTML 菜单会被窗口边界裁掉，
 * 原生 popup 是独立窗口才能溢出到面板外面（见 `src-tauri/src/lib.rs` 的 `open_mini_menu`）。
 */
export function MiniApp() {
  const [market, setMarket] = useState<MarketType>(readMarket);
  const [items, setItems] = useState<WatchItem[]>(readWatchlist);
  const rows = useMemo(() => items.filter((i) => i.market === market), [items, market]);
  const drag = useWindowDrag({ holdMs: HOLD_DRAG_MS, blankOnly: false });
  /** 面板上有没有指针：行的高亮底色挂在它上面（不能直接用 `:hover`，原因见 usePointerInside）。 */
  const miniRef = useRef<HTMLDivElement>(null);
  const inside = usePointerInside(miniRef);

  /** 点面板任意处 = 换到主窗（行按钮自己会先接手，事件不会冒泡到这儿）。 */
  const onPanelClick = (e: ReactMouseEvent<HTMLDivElement>) => {
    if (e.target instanceof Element && e.target.closest('button')) return;
    switchToMainWindow();
  };

  /** 右键 = 弹「隐藏悬浮窗」菜单；`preventDefault` 挡住 webview 自带的空白菜单。 */
  const onPanelContextMenu = (e: ReactMouseEvent<HTMLDivElement>) => {
    e.preventDefault();
    openMiniMenu();
  };

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
    // 行高与封顶行数由 lib/layout.ts 注入（`--mini-row-h` / `--mini-max-rows`）：
    // 那两个值同时决定 Rust 侧的窗口尺寸，放在 TS 里才能被类型系统一起看见；
    // theme.css 里同名的声明退化成兜底默认值，不再有权威性。
    <div
      ref={miniRef}
      className={`mini${inside ? ' inside' : ''}`}
      style={
        {
          '--mini-row-h': `${MINI_ROW_HEIGHT}px`,
          '--mini-max-rows': MINI_MAX_ROWS,
        } as CSSProperties
      }
      onClick={onPanelClick}
      onContextMenu={onPanelContextMenu}
      {...drag}
    >
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
        {/* 空自选那一行也是「点面板换主窗」的入口（Rust 侧高度算法也留了这一行） */}
        {rows.length === 0 && <div className="mini-empty">自选为空 · 点这里去主窗添加</div>}
      </div>
    </div>
  );
}
