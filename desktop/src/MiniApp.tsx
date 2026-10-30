import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  // 别名：`MouseEvent` 这个名字被 DOM 全局类型占着，直接 import 会把它遮掉
  type MouseEvent as ReactMouseEvent,
} from 'react';
import type { Instrument } from './lib/api';
import { readMiniWatchlist, watchKey, type WatchItem } from './hooks/useWatchlist';
import { useTickers } from './hooks/useTickers';
import { useInstruments } from './hooks/useMarketData';
import { changeClass, displaySymbol, formatChange, formatPrice, formatSpan } from './lib/format';
import { openMiniMenu, resizeMiniRows, showMainWindow, switchToMainWindow } from './lib/tauri';
import { syncFuturesSource, syncSpotSource, useFuturesSourceUrl } from './lib/sources';
import { useCooldownMs } from './lib/rateGate';
import { MINI_MAX_ROWS, MINI_ROW_HEIGHT } from './lib/layout';
import { usePointerInside } from './hooks/usePointerInside';
import { useShortcut } from './hooks/useShortcut';
import { useGlobalKey } from './hooks/useGlobalKey';
import { useWindowDrag } from './lib/windowDrag';

/**
 * 行数不在这儿截断：≤6 条时面板按条数撑高，>6 条时高度封顶、行区内部滚动
 * （行高与封顶行数由 `lib/layout.ts` 注入成 CSS 变量，窗口高度侧对齐 Rust 的
 * `MINI_ROW_H` / `MINI_MAX_ROWS`）。
 * 按封顶行数截断会让「固定高度」失去意义 —— 第 7 条之后的条目会直接看不到。
 */
/** 悬浮窗与主窗是两套 React 实例：显示哪几条靠轮询 localStorage 同步，主窗改动最迟 4s 反映过来。 */
const SYNC_MS = 4000;
/** 悬浮窗轮询比主窗慢一档，避免两个窗口叠加把 Gate 的限频顶穿。 */
const POLL_MS = 3000;

/**
 * 迷你悬浮窗：**只看不摆** —— 没有常驻标题栏，常态下整块面板都是价格。
 *
 * 唯一的例外是断联遮罩（`.mini-offline`）：行情断了以后数字会**冻在原地**，
 * 不说出来就是「价格没变」的错觉 —— 这一层必须把整个面板盖住。
 *
 * **左键：整块面板就是「换到主窗」的入口。** 点某一行 = 主窗起来并选中该标的；
 * 点空白（含列表为空时的提示）＝ 只切到主窗（两窗互斥，主窗起来时这块自动收起）。
 * 行仍然是 `button`，各自带更具体的语义（要带上自己的标的），所以面板级的
 * `onClick` 要按 `closest('button')` 让位。断联遮罩不吃指针（`pointer-events: none`），
 * 所以断联期间这一套照常 —— 点一下正好去主窗看连接态 / 换数据源。
 *
 * **右键：弹原生菜单**（目前一条「隐藏悬浮窗」）。菜单只能由宿主弹 ——
 * 悬浮窗就面板那么大，画在 webview 里的 HTML 菜单会被窗口边界裁掉，
 * 原生 popup 是独立窗口才能溢出到面板外面（见 `src-tauri/src/lib.rs` 的 `open_mini_menu`）。
 */
export function MiniApp() {
  /**
   * 显示哪几条由主窗「设置 → 悬浮窗」那份**独立列表**决定（搜索添加、可单独排序，
   * 0.1.16 起不再挂在主窗名单下面）；也不跟主窗看哪个标签页联动 —— 合约和现货同屏混着看。
   */
  const [rows, setRows] = useState<WatchItem[]>(readMiniWatchlist);
  /**
   * 整块面板都能拖（`blankOnly: false`）—— 行本身也是入口，别让「点在行上」就搬不动窗口。
   * 「点行选中」与「搬窗口」的分界**不是时长而是位移**（超过 4px 才算搬）：
   * 早先用「按住 200ms」手感和行为都不对，判据与踩坑见 `lib/dragThreshold.ts`。
   */
  const drag = useWindowDrag({ blankOnly: false });
  /** 面板上有没有指针：行的高亮底色挂在它上面（不能直接用 `:hover`，原因见 usePointerInside）。 */
  const miniRef = useRef<HTMLDivElement>(null);
  const inside = usePointerInside(miniRef);
  /**
   * 快捷键（默认 Alt+D，可在主窗的设置里改）在悬浮窗里同样有效：按一下就换回主窗。
   * 平时那一下由宿主收（系统级，见 lib/globalKey.ts），这边这条只是**兜底** ——
   * 组合键被别的程序占了、注册不上时才轮到它。悬浮窗是启动后才加载的，
   * 挂载时读一次状态就够用（之后再跟 `global-shortcut-state` 事件）。
   */
  const globalTaken = useGlobalKey();
  useShortcut('toggleWindow', switchToMainWindow, !globalTaken);

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
      setRows(readMiniWatchlist());
      // 主窗里可能刚换了数据源（现货或合约）：跟进后 useSourceKey 变化会带动取数重来
      syncFuturesSource();
      syncSpotSource();
    }, SYNC_MS);
    return () => clearInterval(timer);
  }, []);

  /**
   * 窗口一被唤起来就立刻重读一次列表。
   * 两窗是互斥的：在设置里勾完再切到悬浮窗，那 4 秒的轮询空窗会让人先看到一屏旧内容，
   * 看起来就像「没保存」。focus / visibilitychange 哪个到都算（Webview2 两个都会发）。
   */
  useEffect(() => {
    const sync = () => setRows(readMiniWatchlist());
    window.addEventListener('focus', sync);
    document.addEventListener('visibilitychange', sync);
    return () => {
      window.removeEventListener('focus', sync);
      document.removeEventListener('visibilitychange', sync);
    };
  }, []);

  // 窗口高度跟着条数走（Rust 按「下边缘不动」调）。只报条数不报高度：
  // 这个窗口启动时是隐藏的，量不到真实高度，条数却是现成的。
  useEffect(() => {
    resizeMiniRows(rows.length);
  }, [rows.length]);

  // 面板上可能混着两个市场的条目：**价格精度**要从各自那份清单里查
  // （名字直接取符号，不依赖清单）；只有一份市场时就不拉另一份（传 undefined 连请求都不发）
  const needFutures = rows.some((r) => r.market === 'FUTURES');
  const needSpot = rows.some((r) => r.market === 'SPOT');
  const futuresList = useInstruments(needFutures ? 'FUTURES' : undefined);
  const spotList = useInstruments(needSpot ? 'SPOT' : undefined);
  const instrumentMap = useMemo(() => {
    const map = new Map<string, Instrument>();
    for (const i of futuresList) map.set(`FUTURES:${i.symbol}`, i);
    for (const i of spotList) map.set(`SPOT:${i.symbol}`, i);
    return map;
  }, [futuresList, spotList]);
  const { cells, online } = useTickers(rows, POLL_MS);
  /** 已确认断联（`online === null` = 还没结论 / 悬浮窗列表为空，那种情况不该盖遮罩）。 */
  const offline = online === false;
  /** 合约 REST 的限频冷却：和断联一样要盖遮罩，但文案不同 —— 实时价还在走 WS。 */
  const futuresUrl = useFuturesSourceUrl();
  const cooldownMs = useCooldownMs(needFutures ? futuresUrl : null);

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
          const inst = instrumentMap.get(watchKey(item));
          const cls = changeClass(t?.priceChangePercent);
          return (
            <button
              key={watchKey(item)}
              className="mini-row"
              onClick={() => showMainWindow(item)}
              title={`在主窗口里打开 ${displaySymbol(item.symbol)}`}
            >
              {/* 交易对整串（BTCUSDT / BTCUSDC）——2026-10-30 用户指定：只给币名时
                  同一币的不同计价币（BTC/USDT 与 BTC/USDC）在两行里读起来一模一样。
                  名字直接由规范符号去掉下划线得来，不等标的清单 —— 也就不会先闪一下币名。 */}
              <span className="mini-sym">{displaySymbol(item.symbol)}</span>
              <span className={`mini-price num ${cls}`}>
                {formatPrice(t?.lastPrice, inst?.tickSize)}
              </span>
              <span className={`mini-chg num ${cls}`}>{formatChange(t?.priceChangePercent)}</span>
            </button>
          );
        })}
        {/* 空列表那一行也是「点面板换主窗」的入口（Rust 侧高度算法也留了这一行） */}
        {rows.length === 0 && <div className="mini-empty">悬浮窗列表为空 · 去主窗设置里搜索添加</div>}
      </div>

      {/* 断联遮罩：**盖满整块面板**（`.mini` 的 `overflow: hidden` 顺手把四角裁成面板圆角）。
          底下那几行价格是**冻住的旧值**，所以这层要近乎实底（见 --scrim-mini）——
          半透明透出来只会让用户继续把旧价当现价读。
          本层不吃指针：面板级的点击 / 右键 / 拖窗口都挂在 `.mini` 上，断联期间照常可用。 */}
      {offline && (
        <div className="mini-offline" role="status">
          <span className="mini-offline-dot" />
          {/* 限频与断联分开说：前者是币安 REST 被封（WS 也断了才会走到这层遮罩），
              用户看到「限频 6h」比「已断联」更容易判断该换数据源还是等。 */}
          <span className="mini-offline-text">
            {cooldownMs > 0 ? `限频 ${formatSpan(cooldownMs)}` : '已断联'}
          </span>
        </div>
      )}
    </div>
  );
}
