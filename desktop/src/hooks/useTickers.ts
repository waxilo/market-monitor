import { useEffect, useRef, useState } from 'react';
import { fetchTicker, type Ticker24h } from '../lib/api';
import { useSourceKey } from '../lib/sources';
import { watchKey, type WatchItem } from './useWatchlist';

export interface TickerCell {
  data: Ticker24h | null;
  /** 最近一次价格变动的方向与时刻，用于闪现动画的 key。 */
  flash: 'up' | 'down' | null;
  flashAt: number;
}

const POLL_MS = 1200;

/** 按标的逐个轮询 24h 行情：Gate 两个市场的 ticker 接口都支持按标的过滤，全量拉一次太重。 */
export function useTickers(items: WatchItem[], pollMs: number = POLL_MS) {
  const [cells, setCells] = useState<Record<string, TickerCell>>({});
  const [online, setOnline] = useState<boolean | null>(null);
  const prevRef = useRef<Record<string, number>>({});
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const itemsKey = items.map(watchKey).join(',');
  // 调用方传进来的都是同一市场的条目，取第一条判断即可（现货恒为 ''）
  const source = useSourceKey(items[0]?.market);
  const sourceRef = useRef(source);

  useEffect(() => {
    const list = itemsRef.current;
    let alive = true;
    // 换数据源：两个盘口的价不能混在一个格子里，旧价格表与闪现代际一起作废
    if (sourceRef.current !== source) {
      sourceRef.current = source;
      prevRef.current = {};
      setCells({});
    }
    // 自选为空不是「断线」：没东西可轮询时保持未定状态，别把空列表说成网络故障
    if (list.length === 0) {
      setOnline(null);
      return;
    }

    async function poll() {
      const results = await Promise.allSettled(list.map((it) => fetchTicker(it.market, it.symbol)));
      if (!alive) return;
      const okCount = results.filter((r) => r.status === 'fulfilled').length;
      setOnline(okCount > 0);
      setCells((prev) => {
        const next = { ...prev };
        const now = Date.now();
        results.forEach((r, i) => {
          if (r.status !== 'fulfilled') return;
          const key = watchKey(list[i]);
          const before = prevRef.current[key];
          const after = r.value.lastPrice;
          let flash = prev[key]?.flash ?? null;
          let flashAt = prev[key]?.flashAt ?? 0;
          if (before != null && after !== before) {
            flash = after > before ? 'up' : 'down';
            flashAt = now;
          }
          prevRef.current[key] = after;
          next[key] = { data: r.value, flash, flashAt };
        });
        return next;
      });
    }

    poll();
    const timer = setInterval(poll, pollMs);
    return () => {
      alive = false;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemsKey, source]);

  return { cells, online };
}
