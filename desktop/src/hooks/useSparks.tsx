import { useEffect, useRef, useState } from 'react';
import { fetchKlines } from '../lib/api';
import { useSourceKey } from '../lib/sources';
import { watchKey, type WatchItem } from './useWatchlist';

const REFRESH_MS = 60_000;

/** 迷你走势：与 App 一致取 24 根 1h 收盘。 */
export function useSparks(items: WatchItem[]) {
  const [sparks, setSparks] = useState<Record<string, number[]>>({});
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const itemsKey = items.map(watchKey).join(',');
  const source = useSourceKey(items[0]?.market);
  const sourceRef = useRef(source);

  useEffect(() => {
    const list = itemsRef.current;
    let alive = true;
    // 换数据源：旧源的 24h 走势不属于新盘口，清掉等重拉
    if (sourceRef.current !== source) {
      sourceRef.current = source;
      setSparks({});
    }

    async function load() {
      const results = await Promise.allSettled(
        list.map(async (it) => {
          const bars = await fetchKlines(it.market, it.symbol, '1h', 24);
          return [watchKey(it), bars.map((b) => b.close)] as const;
        }),
      );
      if (!alive) return;
      setSparks((prev) => {
        const next = { ...prev };
        for (const r of results) {
          if (r.status === 'fulfilled') next[r.value[0]] = r.value[1];
        }
        return next;
      });
    }

    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemsKey, source]);

  return sparks;
}

export function Sparkline({ closes }: { closes: number[] | undefined }) {
  if (!closes || closes.length < 2) return null;
  const w = 56;
  const h = 18;
  const min = Math.min(...closes);
  const max = Math.max(...closes);
  const span = max - min || 1;
  const pts = closes
    .map((c, i) => `${((i / (closes.length - 1)) * w).toFixed(1)},${(h - 2 - ((c - min) / span) * (h - 4)).toFixed(1)}`)
    .join(' ');
  const rising = closes[closes.length - 1] >= closes[0];
  return (
    <svg className="spark" width={w} height={h}>
      <polyline
        points={pts}
        fill="none"
        stroke={rising ? 'var(--up)' : 'var(--down)'}
        strokeWidth="1.2"
      />
    </svg>
  );
}
