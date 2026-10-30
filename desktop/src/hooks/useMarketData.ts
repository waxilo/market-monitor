import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchInstruments, peekInstruments, type Instrument, type MarketType } from '../lib/api';
import { useSourceKey } from '../lib/sources';

/** 失败后的自动重试节奏（毫秒）：一次抖动不该让人去点按钮，但也不无限重试打死接口。 */
const RETRY_DELAYS_MS = [1_000, 3_000, 8_000];

export interface InstrumentsState {
  list: Instrument[];
  /** 清单拿不到时的一句话（错误原文），`null` = 正常或还在取。 */
  error: string | null;
  /** 手动重试（搜索列里的「重试」按钮；自动重试仍在后台按上面的节奏跑）。 */
  reload: () => void;
}

/**
 * 该市场全部可交易标的（搜索的数据源）。接口在 api 层只取一次并缓存；换源时缓存会被清掉。
 * 传 `undefined` = 这一路暂时用不上（比如悬浮窗里没有现货条目），连请求都不发。
 *
 * **失败要说出来**：早先这里把异常整个吞掉，清单停在空数组，而搜索列的判据是
 * 「列表为空 = 正在拉取」—— 一次失败就等于**永远**显示「正在拉取现货标的…」。
 * 现在异常进 `error`（列里给「拉取失败 · 重试」），并在 1s / 3s / 8s 自动重试三次。
 */
export function useInstruments(market: MarketType | undefined): InstrumentsState {
  const source = useSourceKey(market);
  /** 首屏直接用「内存或上次落盘」的那份：清单没变的话，搜索列一打开就是满的。 */
  const [state, setState] = useState<{ list: Instrument[]; error: string | null }>(() => ({
    list: market ? (peekInstruments(market) ?? []) : [],
    error: null,
  }));
  /** 「重来一次」的计数：手动重试按下就 +1，让下面的 effect 整段重跑。 */
  const [seq, setSeq] = useState(0);
  /** 上一次拉到手的「市场 + 数据源」：同一路重跑（命中缓存）不该先退回空清单。 */
  const loadedRef = useRef(`${market}|${source}`);

  useEffect(() => {
    const key = `${market}|${source}`;
    if (!market) {
      loadedRef.current = key;
      setState({ list: [], error: null });
      return;
    }
    const target = market;
    let alive = true;
    // 命中 api 层缓存就直接用。原来这里无条件清空，于是每次重跑都要先渲染一帧
    // 空清单 —— 那一帧里币名只能按 symbol 回退，用户看到的就是「闪过 ETHUSDT」。
    // 换了市场或换了数据源才清：那是另一家的清单，留着会把两个盘口的元数据混在一起。
    if (loadedRef.current !== key) setState({ list: peekInstruments(target) ?? [], error: null });
    loadedRef.current = key;

    const timers: number[] = [];
    let attempt = 0;

    async function load(): Promise<void> {
      try {
        const rows = await fetchInstruments(target);
        if (!alive) return;
        attempt = 0;
        setState({ list: rows, error: null });
      } catch (e) {
        if (!alive) return;
        setState((prev) => ({ list: prev.list, error: e instanceof Error ? e.message : String(e) }));
        const delay = RETRY_DELAYS_MS[attempt];
        if (delay == null) return;
        attempt += 1;
        timers.push(
          window.setTimeout(() => {
            void load();
          }, delay),
        );
      }
    }

    void load();
    return () => {
      alive = false;
      for (const timer of timers) window.clearTimeout(timer);
    };
  }, [market, source, seq]);

  const reload = useCallback(() => setSeq((n) => n + 1), []);

  // 返回值的身份要稳：搜索下拉拿它当 useMemo 依赖，每渲染一个新对象等于每秒重排一次
  // 三千条清单（行情一秒一跳）。只有清单/错误真的变了才换新对象。
  return useMemo(() => ({ list: state.list, error: state.error, reload }), [state, reload]);
}
