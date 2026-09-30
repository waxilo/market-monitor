import { useCallback, useEffect, useState } from 'react';
import {
  addPriceLine,
  movePriceLine,
  parseLineStore,
  removePriceLine,
  type LineStore,
} from '../lib/priceLines';
import {
  addTrendLine,
  moveTrendAnchor,
  moveTrendLineBy,
  parseTrendStore,
  removeTrendLine,
  type TrendAnchor,
  type TrendEnd,
  type TrendLine,
  type TrendStore,
} from '../lib/trendLines';

/**
 * 画布上的两类用户画线：**水平线**（价格）与**两点直线**（时间+价格）。
 * 两种线都按「市场:标的」存（与自选同一个键，见 `watchKey`）—— 线画在这段行情上，
 * 换周期不该丢，换标的必须分开。
 *
 * 这里**没有锁**：能不能动线由工具条现在选在哪一格决定（选中「画图」才动得了，
 * 见 ChartView 的 `BAR_TABS`），所以那把独立的全局锁是多余的第二层开关。
 */
const LINES_KEY = 'mm.priceLines';
const TRENDS_KEY = 'mm.trendLines';

/** 共享空数组：没有线的标的每帧都返回同一个引用，省掉无谓的重渲染。 */
const NO_LINES: number[] = [];
const NO_TRENDS: TrendLine[] = [];

function readRaw(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null; // 隐私模式等拿不到 storage
  }
}

export interface DrawingsApi {
  priceLines: number[];
  trendLines: TrendLine[];
  addPriceLine: (price: number) => void;
  removePriceLine: (index: number) => void;
  movePriceLine: (index: number, price: number) => void;
  addTrendLine: (a: TrendAnchor, b: TrendAnchor) => void;
  removeTrendLine: (index: number) => void;
  /** 线身整体平移（拖拽；两个锚点同加一个数据空间位移）。 */
  moveTrendLineBy: (index: number, deltaTime: number, deltaPrice: number) => void;
  /** 拖某一端改角度。 */
  moveTrendAnchor: (index: number, end: TrendEnd, anchor: TrendAnchor) => void;
  /** 清空当前标的的**全部**画线（两种一起）。 */
  clear: () => void;
}

/**
 * 画线的状态与落盘（localStorage）。
 *
 * 改动一律走**函数式 setState**：调用方是画布的手势回调（拖拽时每个 pointermove
 * 都改一笔），它们拿不到最新的 store，读闭包里的旧值会把一次拖拽里的改动丢掉。
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
    (change: (current: number[]) => number[]) => {
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

  const addLine = useCallback((price: number) => editLines((cur) => addPriceLine(cur, price)), [editLines]);
  const removeLine = useCallback(
    (index: number) => editLines((cur) => removePriceLine(cur, index)),
    [editLines],
  );
  const moveLine = useCallback(
    (index: number, price: number) => editLines((cur) => movePriceLine(cur, index, price)),
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
  const moveTrendBy = useCallback(
    (index: number, deltaTime: number, deltaPrice: number) =>
      editTrends((cur) => moveTrendLineBy(cur, index, deltaTime, deltaPrice)),
    [editTrends],
  );
  const moveTrendEnd = useCallback(
    (index: number, end: TrendEnd, anchor: TrendAnchor) =>
      editTrends((cur) => moveTrendAnchor(cur, index, end, anchor)),
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
    movePriceLine: moveLine,
    addTrendLine: addTrend,
    removeTrendLine: removeTrend,
    moveTrendLineBy: moveTrendBy,
    moveTrendAnchor: moveTrendEnd,
    clear,
  };
}
