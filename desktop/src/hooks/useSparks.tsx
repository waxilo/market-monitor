import { useEffect, useRef, useState } from 'react';
import { loadKlines } from '../lib/klineStore';
import { useSourceKey } from '../lib/sources';
import { watchKey, type WatchItem } from './useWatchlist';

const REFRESH_MS = 60_000;

/** 迷你走势：与 App 一致取 24 根 1h 收盘。 */
const SPARK_BARS = 24;

/**
 * 这么近之内别人刚取过的 1h 数据可以直接借（主图正在看 1h、或均线面板刚扫过）。
 *
 * 比 [REFRESH_MS] 小得多是**故意的**：借用的是「15s 内别人取的那份」，
 * 而自己上一轮（60s 前）写的那份必须重取 —— 否则末根停在一分钟前，走势图永远不更新。
 */
const SPARK_FRESH_MS = 15_000;

export function useSparks(items: WatchItem[]) {
  const [sparks, setSparks] = useState<Record<string, number[]>>({});
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const itemsKey = items.map(watchKey).join(',');
  // 列表可能混着现货与合约，两边的源各自取（同一个 hook 顺序，不会有条件调用）
  const futuresSource = useSourceKey('FUTURES');
  const spotSource = useSourceKey('SPOT');
  const source = `${futuresSource}|${spotSource}`;
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
          // 统一取数入口：与主图 / 面板共用同一份 1h 缓存，撞上时搭同一趟车。
          // 缓存里可能是一整段（主图那 500 根），迷你图只认**最后** 24 根。
          const bars = await loadKlines({
            market: it.market,
            symbol: it.symbol,
            source: it.market === 'FUTURES' ? futuresSource : spotSource,
            interval: '1h',
            want: SPARK_BARS,
            freshMs: SPARK_FRESH_MS,
          });
          return [watchKey(it), bars.slice(-SPARK_BARS).map((b) => b.close)] as const;
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
