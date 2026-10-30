import { useEffect, useState } from 'react';
import type { MarketType } from '../lib/api';

/** 悬浮窗显示的那一份（0.1.16 起独立；主窗 0.1.48 起不再有自选，只剩这里一份名单）。 */
const MINI_KEY = 'mm.miniWatchlist';

/** 一条标的 = 市场 + 交易对（Gate 形态的 `BTC_USDT`，带不带下划线由市场决定）。 */
export interface WatchItem {
  market: MarketType;
  symbol: string;
}

export function watchKey(item: WatchItem): string {
  return `${item.market}:${item.symbol}`;
}

/** 悬浮窗出厂名单：两个市场各 BTC、ETH。 */
export const DEFAULT_MINI_LIST: WatchItem[] = ['BTC', 'ETH'].flatMap((base) => [
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

/** 解析存下来的条目表；`null` = 没存过或坏数据（与「存了个空列表」是两回事）。 */
export function parseItems(raw: string | null): WatchItem[] | null {
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
 * 悬浮窗要显示的那几条 —— 独立列表：在设置 → 悬浮窗里搜索添加，增删/排序都不影响主窗。
 *
 * 没存过（升级上来的机器 / 首装）就落一份出厂名单。**存过就是真相，空列表也算**：
 * 早先是 `list.length > 0` 才认，结果「把列表删空」被当成了「首次启动」，
 * 悬浮窗每 4s 读一次又拿回默认几条，「悬浮窗为空」的提示也就永远到不了。
 */
export function readMiniWatchlist(): WatchItem[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(MINI_KEY);
  } catch {
    return DEFAULT_MINI_LIST; // 隐私模式等拿不到 storage：当成首次启动
  }
  const stored = parseItems(raw);
  if (stored != null) return stored;
  try {
    localStorage.setItem(MINI_KEY, JSON.stringify(DEFAULT_MINI_LIST));
  } catch {
    /* 写不进去就只当这次读过，下次再写 */
  }
  return DEFAULT_MINI_LIST;
}

/** 悬浮窗列表的读写（只有设置 → 悬浮窗 那一页用）。 */
export function useMiniWatchlist() {
  const [items, setItems] = useState<WatchItem[]>(readMiniWatchlist);

  useEffect(() => {
    localStorage.setItem(MINI_KEY, JSON.stringify(items));
  }, [items]);

  return { items, setItems };
}
