import { useCallback, useEffect, useState } from 'react';
import {
  addPriceLine,
  parseLineStore,
  removePriceLine,
  setLineAlert,
  type LineStore,
  type PriceLine,
} from '../lib/priceLines';
import {
  addTrendLine,
  parseTrendStore,
  removeTrendLine,
  type TrendAnchor,
  type TrendLine,
  type TrendStore,
} from '../lib/trendLines';

/**
 * 画布上的两类用户画线：**水平线**（价格）与**两点直线**（时间+价格）。
 * 两种线都按「市场:标的」存（与自选同一个键，见 `watchKey`）—— 线画在这段行情上，
 * 换周期不该丢，换标的必须分开。
 *
 * 这里**没有锁**：画布对已有的线是只读的（没有抓取、没有拖动），所有增删都由右键菜单
 * 那条路进来，一次点击一次改动 —— 不再需要工具开关那类「现在能不能动线」的状态。
 */
const LINES_KEY = 'mm.priceLines';
const TRENDS_KEY = 'mm.trendLines';

/** 共享空数组：没有线的标的每帧都返回同一个引用，省掉无谓的重渲染。 */
const NO_LINES: PriceLine[] = [];
const NO_TRENDS: TrendLine[] = [];

function readRaw(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null; // 隐私模式等拿不到 storage
  }
}

export interface DrawingsApi {
  priceLines: PriceLine[];
  trendLines: TrendLine[];
  addPriceLine: (price: number, alert?: boolean) => void;
  removePriceLine: (index: number) => void;
  /** 开关某条水平线的价格告警（穿越时弹系统通知）。 */
  setPriceLineAlert: (index: number, alert: boolean) => void;
  addTrendLine: (a: TrendAnchor, b: TrendAnchor) => void;
  removeTrendLine: (index: number) => void;
  /** 清空当前标的的**全部**画线（两种一起）。 */
  clear: () => void;
}

/**
 * 画线的状态与落盘（localStorage）。
 *
 * 改动一律走**函数式 setState**：调用方是画布的回调，它们拿不到最新的 store，
 * 读闭包里的旧值会把连续两次改动里的第一次丢掉。
 *
 * `key` 为空（没选中标的）时一切写操作都是空转，读出来是空列表。
 */
export function useDrawings(key: string | null): DrawingsApi {
  const [lineStore, setLineStore] = useState<LineStore>(() => parseLineStore(readRaw(LINES_KEY)));
  const [trendStore, setTrendStore] = useState<TrendStore>(() => parseTrendStore(readRaw(TRENDS_KEY)));

  useEffect(() => {
    localStorage.setItem(LINES_KEY, JSON.stringify(lineStore));
  }, [lineStore]);

  useEffect(() => {
    localStorage.setItem(TRENDS_KEY, JSON.stringify(trendStore));
  }, [trendStore]);

  const editLines = useCallback(
    (change: (current: PriceLine[]) => PriceLine[]) => {
      if (!key) return;
      setLineStore((prev) => ({ ...prev, [key]: change(prev[key] ?? []) }));
    },
    [key],
  );

  const editTrends = useCallback(
    (change: (current: TrendLine[]) => TrendLine[]) => {
      if (!key) return;
      setTrendStore((prev) => ({ ...prev, [key]: change(prev[key] ?? []) }));
    },
    [key],
  );

  const addLine = useCallback(
    (price: number, alert = false) => editLines((cur) => addPriceLine(cur, price, alert)),
    [editLines],
  );
  const removeLine = useCallback(
    (index: number) => editLines((cur) => removePriceLine(cur, index)),
    [editLines],
  );
  const alertLine = useCallback(
    (index: number, alert: boolean) => editLines((cur) => setLineAlert(cur, index, alert)),
    [editLines],
  );

  const addTrend = useCallback(
    (a: TrendAnchor, b: TrendAnchor) => editTrends((cur) => addTrendLine(cur, a, b)),
    [editTrends],
  );
  const removeTrend = useCallback(
    (index: number) => editTrends((cur) => removeTrendLine(cur, index)),
    [editTrends],
  );

  const clear = useCallback(() => {
    editLines(() => []);
    editTrends(() => []);
  }, [editLines, editTrends]);

  return {
    priceLines: (key ? lineStore[key] : undefined) ?? NO_LINES,
    trendLines: (key ? trendStore[key] : undefined) ?? NO_TRENDS,
    addPriceLine: addLine,
    removePriceLine: removeLine,
    setPriceLineAlert: alertLine,
    addTrendLine: addTrend,
    removeTrendLine: removeTrend,
    clear,
  };
}
