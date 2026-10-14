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

/**
 * 连续这么多拍**全部失败**才算断联。
 *
 * 单拍失败多半是一过性抖动（丢包 / DNS / 盘口限频），一拍就翻红会「闪一下又恢复」——
 * 主窗顶栏那枚圆点尚可忍，悬浮窗那层遮罩闪起来就是噪声。悬浮窗 3s 一拍 ⇒ 确认 ≈ 6s，
 * 主窗 1.2s 一拍 ⇒ ≈ 2.4s。**恢复不设门槛**：哪一拍里有成功就立刻回到在线。
 */
const OFFLINE_MISSES = 2;

/** 按标的逐个轮询 24h 行情：Gate 两个市场的 ticker 接口都支持按标的过滤，全量拉一次太重。 */
export function useTickers(items: WatchItem[], pollMs: number = POLL_MS) {
  const [cells, setCells] = useState<Record<string, TickerCell>>({});
  /** 连接态：`null` = 还没结论（首次轮询之前 / 自选为空），`false` = 已确认断联。 */
  const [online, setOnline] = useState<boolean | null>(null);
  const prevRef = useRef<Record<string, number>>({});
  /** 连续「全败」的拍数，攒够 OFFLINE_MISSES 才翻成断联。 */
  const missRef = useRef(0);
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const itemsKey = items.map(watchKey).join(',');
  // 悬浮窗那份列表是跨市场混排的，不能拿第一条代表全部：只要有合约条目就得跟着合约源走
  const source = useSourceKey(items.some((i) => i.market === 'FUTURES') ? 'FUTURES' : 'SPOT');
  const sourceRef = useRef(source);

  useEffect(() => {
    const list = itemsRef.current;
    let alive = true;
    // 换数据源：两个盘口的价不能混在一个格子里，旧价格表与闪现代际一起作废
    if (sourceRef.current !== source) {
      sourceRef.current = source;
      prevRef.current = {};
      // 换源等于换了一个「通不通」的判定对象：上一家的断联账不能记到这一家头上
      missRef.current = 0;
      setCells({});
    }
    // 自选为空不是「断线」：没东西可轮询时保持未定状态，别把空列表说成网络故障
    if (list.length === 0) {
      missRef.current = 0;
      setOnline(null);
      return;
    }

    async function poll() {
      const results = await Promise.allSettled(list.map((it) => fetchTicker(it.market, it.symbol)));
      if (!alive) return;
      // 一拍里**只要有一条成功**就算通（逐标的轮询，个别标的取不到不算断网）；
      // 全败才记账，攒够 OFFLINE_MISSES 拍才真的翻成断联 —— 在此之前保持原判不动
      const okCount = results.filter((r) => r.status === 'fulfilled').length;
      if (okCount > 0) {
        missRef.current = 0;
        setOnline(true);
      } else if (++missRef.current >= OFFLINE_MISSES) {
        setOnline(false);
      }
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
