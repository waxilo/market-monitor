import { useCallback, useEffect, useRef, useState } from 'react';
import type { MarketType } from '../lib/api';

const KEY = 'mm.watchlist';
/** 悬浮窗显示的那一份：自选的**子集**（可单独排序），与主窗侧栏分开配置。 */
const MINI_KEY = 'mm.miniWatchlist';

/** 自选条目 = 市场 + 标的（Gate 的标的名自带计价币，如 BTC_USDT）。 */
export interface WatchItem {
  market: MarketType;
  symbol: string;
}

export function watchKey(item: WatchItem): string {
  return `${item.market}:${item.symbol}`;
}

export function sameWatch(a: WatchItem | null, b: WatchItem | null): boolean {
  return a != null && b != null && a.market === b.market && a.symbol === b.symbol;
}

/** 出厂自选：两个市场各只有 BTC、ETH（0.1.11 起；老装机器的列表由下面的 `seedOnce` 一次性换掉）。 */
export const DEFAULT_WATCHLIST: WatchItem[] = ['BTC', 'ETH'].flatMap((base) => [
  { market: 'SPOT' as const, symbol: `${base}_USDT` },
  { market: 'FUTURES' as const, symbol: `${base}_USDT` },
]);

/** 旧版纯字符串列表（全 Aster 永续、无下划线）：BTCUSDT → { FUTURES, BTC_USDT }。 */
function migrate(value: unknown): WatchItem | null {
  if (typeof value === 'string') {
    const s = value.trim().toUpperCase();
    if (!s) return null;
    return { market: 'FUTURES', symbol: s.endsWith('USDT') && !s.includes('_') ? `${s.slice(0, -4)}_USDT` : s };
  }
  if (value && typeof value === 'object') {
    const o = value as { market?: unknown; symbol?: unknown };
    if (typeof o.symbol !== 'string' || !o.symbol) return null;
    if (o.market !== 'SPOT' && o.market !== 'FUTURES') return null;
    return { market: o.market, symbol: o.symbol };
  }
  return null;
}

/** 一次性种子迁移的记号：换成新出厂名单后落上，之后永不再动。 */
const SEED_KEY = 'mm.watchlistSeeded';

/**
 * 0.1.11 的出厂自选换了（原来是 8 条永续 → 现在两市场各 BTC、ETH）。
 *
 * 光改 `DEFAULT_WATCHLIST` 对升级上来的机器没用：「存过就是真相」，旧列表会原样留着，
 * 看起来就像「没生效」。所以这里做一次**强制替换**（用户要的就是「其他移除」），
 * 跑完立刻落记号 —— 以后自己加回来的标的不会再被清。两窗谁先读谁执行，写入是幂等的。
 */
function seedOnce(): void {
  try {
    if (localStorage.getItem(SEED_KEY) != null) return;
    localStorage.setItem(SEED_KEY, '1');
    localStorage.setItem(KEY, JSON.stringify(DEFAULT_WATCHLIST));
  } catch {
    /* 隐私模式等拿不到 storage：什么都不写，读的那头会走默认值 */
  }
}

/**
 * 从 localStorage 读一份当前自选（悬浮窗与主窗是两套 React 实例，各自读同一份数据）。
 *
 * **只有「没存过」才给默认自选；存过就是真相，空列表也算**。早先是 `list.length > 0`
 * 才认，结果「把自选删空」被当成了「首次启动」：主窗删空后悬浮窗每 4s 读一次又拿回
 * 默认 8 条，两窗显示不一致，悬浮窗的「自选为空」提示也就永远到不了。
 */
export function readWatchlist(): WatchItem[] {
  seedOnce();
  let raw: string | null;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    return DEFAULT_WATCHLIST; // 隐私模式等拿不到 storage：当成首次启动
  }
  return parseWatchlist(raw) ?? DEFAULT_WATCHLIST;
}

/** 解析存下来的列表；`null` = 没存过或坏数据（与「存了个空列表」是两回事）。 */
function parseWatchlist(raw: string | null): WatchItem[] | null {
  if (raw == null) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (Array.isArray(parsed)) {
      return dedupe(parsed.map(migrate).filter((x): x is WatchItem => x != null));
    }
  } catch {
    /* 坏数据 → 当成没存过 */
  }
  return null;
}

function dedupe(items: WatchItem[]): WatchItem[] {
  const seen = new Set<string>();
  return items.filter((i) => {
    const key = watchKey(i);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * 悬浮窗要显示的那几条。
 *
 * - **没单独配置过 = 整份自选**（老行为，升级上来的人不会看到空面板）；
 * - 配置过就是真相，空列表也算；
 * - 只保留仍在自选里的条目：主窗删掉某条自选，悬浮窗最迟 4s 跟着少一行，
 *   不需要谁去回写 `mm.miniWatchlist`（读的时候过滤，坏不了也漏不了）。
 */
export function readMiniWatchlist(): WatchItem[] {
  const watch = readWatchlist();
  let raw: string | null;
  try {
    raw = localStorage.getItem(MINI_KEY);
  } catch {
    return watch;
  }
  const stored = parseWatchlist(raw);
  if (stored == null) return watch;
  const allowed = new Set(watch.map(watchKey));
  return stored.filter((i) => allowed.has(watchKey(i)));
}

/**
 * 悬浮窗列表的读写（只有配置弹窗用）。
 *
 * 首次挂载**不回写**：这时值很可能是「没配置 → 整份自选」推导出来的，
 * 一写就把「跟随自选」固化成「定死的子集」，以后加进自选的标的再也进不了悬浮窗。
 */
export function useMiniWatchlist() {
  const [items, setItems] = useState<WatchItem[]>(readMiniWatchlist);
  const first = useRef(true);

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    localStorage.setItem(MINI_KEY, JSON.stringify(items));
  }, [items]);

  /** 恢复「跟随自选」：删掉这个键，下次读的时候重新取整份自选。 */
  const reset = useCallback(() => {
    localStorage.removeItem(MINI_KEY);
    setItems(readWatchlist());
  }, []);

  return { items, setItems, reset };
}

/** 自选列表持久化在 localStorage（App 端是 Room，且同样以市场为维度隔离）。 */
export function useWatchlist() {
  const [items, setItems] = useState<WatchItem[]>(readWatchlist);

  useEffect(() => {
    localStorage.setItem(KEY, JSON.stringify(items));
  }, [items]);

  /** 返回 false 表示已在自选里（调用方仍可据此选中它）。 */
  const add = useCallback((item: WatchItem) => {
    let ok = true;
    setItems((prev) => {
      if (prev.some((s) => s.market === item.market && s.symbol === item.symbol)) {
        ok = false;
        return prev;
      }
      return [...prev, item];
    });
    return ok;
  }, []);

  const remove = useCallback((item: WatchItem) => {
    setItems((prev) => prev.filter((s) => !(s.market === item.market && s.symbol === item.symbol)));
  }, []);

  // setItems 一并交出去：侧栏整行拖动排序要改顺序（改完照旧由上面的 effect 落盘）
  return { items, setItems, add, remove };
}
