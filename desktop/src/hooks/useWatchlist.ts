import { useCallback, useEffect, useState } from 'react';
import type { MarketType } from '../lib/api';

const KEY = 'mm.watchlist';
/** 悬浮窗显示的那一份：**独立列表**（0.1.16 起），与主窗侧栏各自增删、互不影响。 */
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
 * 悬浮窗要显示的那几条 —— 0.1.16 起是**独立列表**：在设置 → 悬浮窗里搜索添加，
 * 增删/排序都不动自选（读的时候也不再拿自选过滤）。
 *
 * 没存过（升级上来的机器 / 首装）就把当前自选**拷一份**落盘 —— 老行为「跟随自选」的
 * 收尾：升级后面板内容不变，拷完两边各走各的。存过就是真相，空列表也算。
 */
export function readMiniWatchlist(): WatchItem[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(MINI_KEY);
  } catch {
    return readWatchlist(); // 隐私模式等拿不到 storage：当成没配置，只读不写
  }
  const stored = parseWatchlist(raw);
  if (stored != null) return stored;
  const seed = readWatchlist();
  try {
    localStorage.setItem(MINI_KEY, JSON.stringify(seed));
  } catch {
    /* 写不进去就只当这次读过，下次再拷 */
  }
  return seed;
}

/** 悬浮窗列表的读写（只有设置 → 悬浮窗 那一页用）。 */
export function useMiniWatchlist() {
  const [items, setItems] = useState<WatchItem[]>(readMiniWatchlist);

  useEffect(() => {
    localStorage.setItem(MINI_KEY, JSON.stringify(items));
  }, [items]);

  return { items, setItems };
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
