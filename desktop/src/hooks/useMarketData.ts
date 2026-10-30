import { useEffect, useRef, useState } from 'react';
import { fetchInstruments, peekInstruments, type Instrument, type MarketType } from '../lib/api';
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
