import { useEffect, useRef, useState } from 'react';
import { fetchTicker, type Ticker24h } from '../lib/api';
import { endpointOf, useFuturesSourceUrl, useSpotSource } from '../lib/sources';
import { isCoolingDown } from '../lib/rateGate';
import { openBinanceStream, streamHostOf, streamSymbolOf } from '../lib/stream';
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

/**
 * 按标的轮询 24h 行情。
 *
 * **币安系合约改走 WebSocket**（见 lib/stream.ts）：REST 逐标的轮询既费权重、又是
 * 429/418 的来源，WS 权重为零。WS 活着时合约那几个标的不再轮询；连不上就回退到
 * 下面的 REST 轮询，冷却期（418/429）则连轮询也不发。
 * 现货在桌面端恒走 Gate（没开 WS），仍按拍轮询。
 */
export function useTickers(items: WatchItem[], pollMs: number = POLL_MS) {
  const [cells, setCells] = useState<Record<string, TickerCell>>({});
  /** 连接态：`null` = 还没结论（首次轮询之前 / 一个标签页都没开），`false` = 已确认断联。 */
  const [online, setOnline] = useState<boolean | null>(null);
  const prevRef = useRef<Record<string, number>>({});
  /** 连续「全败」的拍数，攒够 OFFLINE_MISSES 才翻成断联。 */
  const missRef = useRef(0);
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const itemsKey = items.map(watchKey).join(',');
  // 悬浮窗那份列表是跨市场混排的，不能拿第一条代表全部：两个市场各跟着自己的源走，
  // 任一源变了都要作废旧价格（否则两家盘口的价格会混在一个格子里）
  const futuresUrl = useFuturesSourceUrl();
  const spotSource = useSpotSource();
  const hasFutures = items.some((i) => i.market === 'FUTURES');
  const hasSpot = items.some((i) => i.market === 'SPOT');
  const source = `${hasFutures ? futuresUrl : ''}|${hasSpot ? spotSource : ''}`;
  const sourceRef = useRef(source);

  useEffect(() => {
    const list = itemsRef.current;
    let alive = true;
    // 换数据源：两个盘口的价不能混在一个格子里，旧价格表与闪现代代一起作废
    if (sourceRef.current !== source) {
      sourceRef.current = source;
      prevRef.current = {};
      // 换源等于换了一个「通不通」的判定对象：上一家的断联账不能记到这一家头上
      missRef.current = 0;
      setCells({});
    }
    // 没有标签页不是「断线」：没东西可轮询时保持未定状态，别把空列表说成网络故障
    if (list.length === 0) {
      missRef.current = 0;
      setOnline(null);
      return;
    }

    /** 一条行情落地：闪现代际、价格表、单元格三处一起更（轮询与 WS 共用）。 */
    function applyOne(item: WatchItem, ticker: Ticker24h) {
      if (!alive) return;
      const key = watchKey(item);
      setCells((prev) => {
        const next = { ...prev };
        const now = Date.now();
        const before = prevRef.current[key];
        const after = ticker.lastPrice;
        let flash = prev[key]?.flash ?? null;
        let flashAt = prev[key]?.flashAt ?? 0;
        if (before != null && after !== before) {
          flash = after > before ? 'up' : 'down';
          flashAt = now;
        }
        prevRef.current[key] = after;
        next[key] = { data: ticker, flash, flashAt };
        return next;
      });
    }

    // ————— 实时通道：只有币安系（含 Aster）有流；其余盘口继续轮询 —————
    const wsBase = endpointOf(futuresUrl).dialect === 'BINANCE' ? futuresUrl : null;
    const futuresItems = list.filter((it) => it.market === 'FUTURES');
    const bySymbol = new Map(futuresItems.map((it) => [it.symbol, it]));
    let wsLive = false;
    let stopStream = () => {};

    if (wsBase != null && streamHostOf(wsBase) != null && bySymbol.size > 0) {
      stopStream = openBinanceStream(
        wsBase,
        futuresItems.map((it) => `${streamSymbolOf(it.symbol)}@ticker`),
        {
          onClose: () => {
            // 不立刻翻红：重连多半马上就好，兜底的轮询会给出结论
            wsLive = false;
          },
          onTicker: (symbol, snapshot) => {
            const item = bySymbol.get(symbol);
            if (item == null) return;
            // 「连上」不等于「有数据」：币安对受限 IP 会接受 WS 握手但一帧都不推
            // （实测 2026-10-30）。所以只有收到第一帧才停掉 REST 兜底、才算在线，
            // 否则实时价会冻在旧值上而状态还是绿的。
            if (!wsLive) {
              wsLive = true;
              missRef.current = 0;
              setOnline(true);
            }
            applyOne(item, {
              symbol: item.symbol,
              lastPrice: snapshot.lastPrice,
              priceChangePercent:
                snapshot.openPrice > 0
                  ? ((snapshot.lastPrice - snapshot.openPrice) / snapshot.openPrice) * 100
                  : 0,
              highPrice: snapshot.highPrice,
              lowPrice: snapshot.lowPrice,
              quoteVolume: snapshot.quoteVolume,
            });
          },
        },
      );
    }

    async function poll() {
      if (!alive) return;
      // WS 活着时合约那部分不轮询；冷却期（418/429）合约连兜底都不发，等冷却结束。
      // 现货走的是 Gate（另一家、另一套限频），不受合约冷却影响，照常轮询。
      const cooling = futuresItems.length > 0 && isCoolingDown(futuresUrl);
      const pending = list.filter((it) => it.market !== 'FUTURES' || (!wsLive && !cooling));
      if (pending.length === 0) {
        // 合约既没有 WS、又在冷却：这一路确实是断的
        if (!wsLive && cooling && ++missRef.current >= OFFLINE_MISSES) setOnline(false);
        return;
      }

      const results = await Promise.allSettled(pending.map((it) => fetchTicker(it.market, it.symbol)));
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
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') applyOne(pending[i], r.value);
      });
    }

    poll();
    const timer = setInterval(poll, pollMs);
    return () => {
      alive = false;
      clearInterval(timer);
      stopStream();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemsKey, source]);

  return { cells, online };
}
