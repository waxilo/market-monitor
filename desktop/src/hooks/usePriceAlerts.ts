import { useEffect, useRef } from 'react';
import { listen } from '@tauri-apps/api/event';
import { MARKET_LABEL, fetchTicker, peekInstruments, type MarketType } from '../lib/api';
import { formatPrice } from '../lib/format';
import { alertLines, judgeTick, type Side, type WatchLine } from '../lib/priceAlerts';
import { parseLineStore } from '../lib/priceLines';
import { IS_TAURI, notifyPriceAlert } from '../lib/tauri';

/** 画线的落盘键，与 `hooks/useDrawings.ts` 同一个（那边写、这里只读）。 */
const LINES_KEY = 'mm.priceLines';

/**
 * 价格告警：宿主的节拍（`alert-tick`，5s 一拍）进来，这里读一遍画线、拉一遍现价，
 * 判定交给纯函数 `judgeTick`，判出来的穿越调 `notify_price_alert` 弹系统通知。
 *
 * **判定为什么不放 Rust**：告警线存在 localStorage，取价要过 `lib/dialects.ts` 那六家盘口
 * 的 URL 构造与解析 —— 两边各留一份必然会分叉，所以数据与判定都留在前端，
 * 宿主只出「节拍」和「通知通道」两样（见 src-tauri/src/alert.rs）。
 *
 * **节拍为什么不放前端**：主窗点关闭是收进托盘，窗口一隐藏，WebView2 就把网页定时器
 * 当后台页节流；宿主线程的节拍不受这条节流管，托盘常驻也照报。
 *
 * 只在主窗挂载（App.tsx）：悬浮窗是第二份 React 实例，两边都跑就会一个穿越弹两条。
 */
export function usePriceAlerts() {
  /** 每条线「现价在哪一侧」的档案（规矩见 `judgeTick`；不落盘，重启即重新静默建档）。 */
  const sides = useRef<Map<string, Side>>(new Map());
  // 一拍没跑完就跳过下一拍：慢网里两轮并发会把同一次穿越报两遍。
  const busy = useRef(false);

  useEffect(() => {
    if (!IS_TAURI) return;
    let alive = true;

    /**
     * 从 localStorage 现读：图上刚加/刚删的线要立刻算数，不等 React 的状态同步。
     * 键形如 `市场:标的`（取价时按第一个冒号拆）；坏键只会让那一拍取价失败、什么都不动。
     */
    function readLines(): WatchLine[] {
      let raw: string | null;
      try {
        raw = localStorage.getItem(LINES_KEY);
      } catch {
        return [];
      }
      return alertLines(parseLineStore(raw));
    }

    /** 一个标的问一次（同标的的几条线共用一次取价），返回 `市场:标的 → 现价`。 */
    async function fetchPrices(lines: WatchLine[]): Promise<Map<string, number>> {
      const keys: string[] = [];
      for (const l of lines) if (!keys.includes(l.key)) keys.push(l.key);
      const results = await Promise.allSettled(
        keys.map((k) => fetchTicker(k.slice(0, k.indexOf(':')) as MarketType, k.slice(k.indexOf(':') + 1))),
      );
      const prices = new Map<string, number>();
      results.forEach((r, i) => {
        if (r.status === 'fulfilled' && Number.isFinite(r.value.lastPrice)) {
          prices.set(keys[i], r.value.lastPrice);
        }
      });
      return prices;
    }

    async function check() {
      if (!alive || busy.current) return;
      busy.current = true;
      try {
        const lines = readLines();
        if (lines.length === 0) {
          sides.current = new Map();
          return;
        }
        const prices = await fetchPrices(lines);
        if (!alive) return;
        const result = judgeTick(lines, prices, sides.current);
        sides.current = result.sides;
        for (const c of result.crossings) notify(c.key, c.linePrice, c.price, c.up);
      } finally {
        busy.current = false;
      }
    }

    /** 弹一条系统通知；文案按方向分「上破 / 下破」。失败不往上报（宿主侧已记日志）。 */
    function notify(key: string, linePrice: number, price: number, up: boolean) {
      const sep = key.indexOf(':');
      const market = key.slice(0, sep) as MarketType;
      const symbol = key.slice(sep + 1);
      const instrument = peekInstruments(market)?.find((i) => i.symbol === symbol);
      const label = instrument?.baseAsset ?? symbol;
      const tickSize = instrument?.tickSize ?? null;
      const lineText = formatPrice(linePrice, tickSize);
      notifyPriceAlert(
        `${label} ${MARKET_LABEL[market]} ${up ? '上破' : '下破'} ${lineText}`,
        `告警线 ${lineText} · 现价 ${formatPrice(price, tickSize)}`,
      );
    }

    const unlisten = listen('alert-tick', () => {
      void check();
    });
    return () => {
      alive = false;
      void unlisten.then((off) => off());
    };
  }, []);
}
