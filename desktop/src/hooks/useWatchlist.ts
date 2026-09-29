import { useCallback, useEffect, useState } from 'react';
import type { MarketType } from '../lib/api';

const KEY = 'mm.watchlist';

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

export const DEFAULT_WATCHLIST: WatchItem[] = [
  'BTC',
  'ETH',
  'SOL',
  'BNB',
  'XRP',
  'DOGE',
  'ADA',
  'AVAX',
].map((base) => ({ market: 'FUTURES' as const, symbol: `${base}_USDT` }));

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

/**
 * 从 localStorage 读一份当前自选（悬浮窗与主窗是两套 React 实例，各自读同一份数据）。
 *
 * **只有「没存过」才给默认自选；存过就是真相，空列表也算**。早先是 `list.length > 0`
 * 才认，结果「把自选删空」被当成了「首次启动」：主窗删空后悬浮窗每 4s 读一次又拿回
 * 默认 8 条，两窗显示不一致，悬浮窗的「自选为空」提示也就永远到不了。
 */
export function readWatchlist(): WatchItem[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    return DEFAULT_WATCHLIST; // 隐私模式等拿不到 storage：当成首次启动
  }
  if (raw == null) return DEFAULT_WATCHLIST;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (Array.isArray(parsed)) {
      return parsed.map(migrate).filter((x): x is WatchItem => x != null);
    }
  } catch {
    /* 坏数据 → 落回默认 */
  }
  return DEFAULT_WATCHLIST;
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

  return { items, add, remove };
}
