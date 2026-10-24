import { useCallback, useEffect, useState } from 'react';
import {
  addPriceLine,
  movePriceLine,
  parseLineStore,
  removePriceLine,
  setLineAlert,
  setLineNote,
  type LineStore,
  type PriceLine,
} from '../lib/priceLines';
import {
  addTrendLine,
  moveTrendAnchor,
  moveTrendLineBy,
  parseTrendStore,
  removeTrendLine,
  setTrendNote,
  type TrendAnchor,
  type TrendEnd,
  type TrendLine,
  type TrendStore,
} from '../lib/trendLines';
import {
  LINES_LOCK_KEY,
  parseLinesLock,
  withLinesLock,
  type LinesLockStore,
} from '../lib/linesLock';
import {
  LINES_HIDDEN_KEY,
  parseLinesHidden,
  withLinesHidden,
  type LinesHiddenStore,
} from '../lib/linesHidden';

/**
 * 画布上的两类用户画线：**水平线**（价格）与**两点直线**（时间+价格）。
 * 两种线都按「市场:标的」存（与自选同一个键，见 `watchKey`）—— 线画在这段行情上，
 * 换周期不该丢，换标的必须分开。
 *
 * 画线有一个按标的的**锁定开关**（`mm.linesLock`，默认解锁）：解锁时单击画布上的线先选中它，
 * 再按住才能拖（水平线上下拖、直线整体拖 / 端点转角度）；锁上后画布不抓任何线、也不画把手，
 * 连选中都不成立，画新线 / 清空也一并收掉（见 ChartView 的划线区）。
 * **新增 / 删除 / 清空 / 改备注都只在划线区里**（2026-10-24 起右键菜单整档移除）：
 * 一次点击一次改动，且改哪条线由「当前选中的那条」唯一确定。
 *
 * 另有一个按标的的**显隐开关**（`mm.linesHidden`，默认显示）：眼睛只藏不删，
 * 落盘与告警判定都不受影响（见 lib/linesHidden）。
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
  /** 整张图的画线是否锁定（按标的存；默认解锁 = 可选中可拖，且能画新线）。 */
  linesLocked: boolean;
  setLinesLocked: (locked: boolean) => void;
  /** 整张图的画线是否隐藏（按标的存；默认显示 = 眼睛睁着）。只藏不删，告警照常。 */
  linesHidden: boolean;
  setLinesHidden: (hidden: boolean) => void;
  addPriceLine: (price: number, alert?: boolean, note?: string) => void;
  removePriceLine: (index: number) => void;
  /** 开关某条水平线的价格告警（穿越时弹系统通知）。 */
  setPriceLineAlert: (index: number, alert: boolean) => void;
  /** 改某条水平线的备注（空串 = 删掉备注）。 */
  setPriceLineNote: (index: number, note: string) => void;
  /** 改某条直线的备注（空串 = 删掉备注）。 */
  setTrendLineNote: (index: number, note: string) => void;
  /** 拖动：水平线改到新价（画布按指针位置绝对定位）。 */
  movePriceLine: (index: number, price: number) => void;
  /** 拖动：直线整体平移（画布按指针位移换算出数据空间增量）。 */
  moveTrendBy: (index: number, deltaTime: number, deltaPrice: number) => void;
  /** 拖动：直线某一端改点位（另一端不动）。 */
  moveTrendAnchor: (index: number, end: TrendEnd, anchor: TrendAnchor) => void;
  addTrendLine: (a: TrendAnchor, b: TrendAnchor) => void;
  removeTrendLine: (index: number) => void;
  /** 清空当前标的的**全部**画线（两种一起）。 */
  clear: () => void;
  /**
   * 整体替换当前标的的线表（传进来的就是落盘结果）。
   *
   * 与上面那些「一次改一个字段」的接口并列，专供**一次改动同时改好几个字段**的场合：
   * EMA 分析面板点一下会同时改价位 / 告警 / 来源 / 备注，逐条调用会落中间态存档
   * （见 `lib/emaAnalysis.applyEmaLine`）。面板是这条路的唯一写入口，两下点击之间
   * 必然隔着一次渲染，所以不存在「读到上一帧线表把前一下冲掉」的问题。
   */
  setPriceLines: (lines: PriceLine[]) => void;
  /**
   * 以**当前线表**为基准改一次（读闭包里的旧表不安全时用它）。
   *
   * 唯一的调用方是 EMA 的分钟自动对齐：它发生在一次异步取数回来之后，与「上一帧线表」
   * 之间隔着整整一两秒 —— 用户完全可能刚把某条线拖到别处。拿闭包里的旧表去改，
   * 就会把这一拖连本带利地抹掉（不只是那条 EMA 线，所有线都会退回一两秒前的样子）。
   * 这里走的是函数式 setState，读到的永远是提交那一刻的线表。
   */
  editPriceLines: (change: (current: PriceLine[]) => PriceLine[]) => void;
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
  const [lockStore, setLockStore] = useState<LinesLockStore>(() => parseLinesLock(readRaw(LINES_LOCK_KEY)));
  const [hiddenStore, setHiddenStore] = useState<LinesHiddenStore>(() =>
    parseLinesHidden(readRaw(LINES_HIDDEN_KEY)),
  );

  useEffect(() => {
    localStorage.setItem(LINES_KEY, JSON.stringify(lineStore));
  }, [lineStore]);

  useEffect(() => {
    localStorage.setItem(TRENDS_KEY, JSON.stringify(trendStore));
  }, [trendStore]);

  useEffect(() => {
    localStorage.setItem(LINES_LOCK_KEY, JSON.stringify(lockStore));
  }, [lockStore]);

  useEffect(() => {
    localStorage.setItem(LINES_HIDDEN_KEY, JSON.stringify(hiddenStore));
  }, [hiddenStore]);

  const setLocked = useCallback(
    (locked: boolean) => {
      if (!key) return;
      setLockStore((prev) => withLinesLock(prev, key, locked));
    },
    [key],
  );

  const setHidden = useCallback(
    (hidden: boolean) => {
      if (!key) return;
      setHiddenStore((prev) => withLinesHidden(prev, key, hidden));
    },
    [key],
  );

  const editLines = useCallback(
    (change: (current: PriceLine[]) => PriceLine[]) => {
      if (!key) return;
      setLineStore((prev) => {
        const current = prev[key] ?? NO_LINES;
        const next = change(current);
        // 原样返回 = 这次没有实际改动（自动对齐一轮下来常常一条都不用动）：
        // 别造新数组，省掉一次重渲染、一次落盘，以及下游那一串 memo 的失效
        return next === current ? prev : { ...prev, [key]: next };
      });
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
    (price: number, alert = false, note = '') =>
      editLines((cur) => addPriceLine(cur, price, alert, note)),
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
  const moveLine = useCallback(
    (index: number, price: number) => editLines((cur) => movePriceLine(cur, index, price)),
    [editLines],
  );
  const noteLine = useCallback(
    (index: number, note: string) => editLines((cur) => setLineNote(cur, index, note)),
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
  const dragTrendBy = useCallback(
    (index: number, deltaTime: number, deltaPrice: number) =>
      editTrends((cur) => moveTrendLineBy(cur, index, deltaTime, deltaPrice)),
    [editTrends],
  );
  const dragTrendAnchor = useCallback(
    (index: number, end: TrendEnd, anchor: TrendAnchor) =>
      editTrends((cur) => moveTrendAnchor(cur, index, end, anchor)),
    [editTrends],
  );
  const noteTrend = useCallback(
    (index: number, note: string) => editTrends((cur) => setTrendNote(cur, index, note)),
    [editTrends],
  );

  const clear = useCallback(() => {
    editLines(() => []);
    editTrends(() => []);
  }, [editLines, editTrends]);

  const replaceLines = useCallback(
    (next: PriceLine[]) => {
      if (!key) return;
      setLineStore((prev) => ({ ...prev, [key]: next }));
    },
    [key],
  );

  return {
    priceLines: (key ? lineStore[key] : undefined) ?? NO_LINES,
    trendLines: (key ? trendStore[key] : undefined) ?? NO_TRENDS,
    linesLocked: key ? lockStore[key] === true : false,
    setLinesLocked: setLocked,
    linesHidden: key ? hiddenStore[key] === true : false,
    setLinesHidden: setHidden,
    addPriceLine: addLine,
    removePriceLine: removeLine,
    setPriceLineAlert: alertLine,
    setPriceLineNote: noteLine,
    setTrendLineNote: noteTrend,
    movePriceLine: moveLine,
    moveTrendBy: dragTrendBy,
    moveTrendAnchor: dragTrendAnchor,
    addTrendLine: addTrend,
    removeTrendLine: removeTrend,
    clear,
    setPriceLines: replaceLines,
    editPriceLines: editLines,
  };
}
