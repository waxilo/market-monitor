import { useEffect, useRef, useState } from 'react';
import {
  fetchAllTickers,
  fetchInstruments,
  peekInstruments,
  type Instrument,
  type MarketType,
  type Ticker24h,
} from '../lib/api';
import { useSourceKey } from '../lib/sources';

/**
 * 该市场全部可交易标的（搜索的数据源）。接口在 api 层只取一次并缓存；换源时缓存会被清掉。
 * 传 `undefined` = 这一路暂时用不上（比如悬浮窗里没有现货自选），连请求都不发。
 */
export function useInstruments(market: MarketType | undefined): Instrument[] {
  const [list, setList] = useState<Instrument[]>(() => (market ? (peekInstruments(market) ?? []) : []));
  const source = useSourceKey(market);
  /** 上一次拉到手的「市场 + 数据源」：同一路重跑（命中缓存）不该先退回空清单。 */
  const loadedRef = useRef(`${market}|${source}`);

  useEffect(() => {
    const key = `${market}|${source}`;
    if (!market) {
      loadedRef.current = key;
      setList([]);
      return;
    }
    let alive = true;
    // 命中 api 层缓存就直接用。原来这里无条件 `setList([])`，于是每次重跑都要先渲染一帧
    // 空清单 —— 那一帧里币名只能按 symbol 回退，用户看到的就是「闪过 ETHUSDT」。
    // 换了市场或换了数据源才清：那是另一家的清单，留着会把两个盘口的元数据混在一起。
    if (loadedRef.current !== key) setList(peekInstruments(market) ?? []);
    loadedRef.current = key;
    fetchInstruments(market)
      .then((rows) => {
        if (alive) setList(rows);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [market, source]);

  return list;
}

const SNAPSHOT_POLL_MS = 8000;

/**
 * 该市场的全量行情快照，只在搜索时才拉：
 * 搜索结果里未自选的标的也要显示价格、并按成交额排序，逐标的轮询不够用。
 */
export function useMarketTickers(market: MarketType, enabled: boolean): Record<string, Ticker24h> {
  const [snapshot, setSnapshot] = useState<Record<string, Ticker24h>>({});
  const source = useSourceKey(market);

  useEffect(() => {
    if (!enabled) return;
    let alive = true;
    // 换市场或换数据源：快照整表作废，留着会把上一批价格摆给搜索行
    setSnapshot({});

    async function load() {
      try {
        const rows = await fetchAllTickers(market);
        if (!alive) return;
        setSnapshot(Object.fromEntries(rows.map((t) => [t.symbol, t])));
      } catch {
        /* 快照失败只影响搜索行的价格，下一次轮询再试 */
      }
    }

    load();
    const timer = setInterval(load, SNAPSHOT_POLL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [market, enabled, source]);

  return snapshot;
}
