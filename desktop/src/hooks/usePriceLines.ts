import { useCallback, useEffect, useState } from 'react';
import {
  addPriceLine,
  movePriceLine,
  parseLineStore,
  removePriceLine,
  type LineStore,
} from '../lib/priceLines';

/** 价目表按「市场:标的」存（与自选同一个键，见 `watchKey`）。 */
const STORE_KEY = 'mm.priceLines';
/** 锁是**全局**的：它管的是「画布现在能不能改线」，跟看哪个标的无关。 */
const LOCK_KEY = 'mm.priceLineLock';

/** 共享空数组：没有线的标的每帧都返回同一个引用，省掉无谓的重渲染。 */
const NO_LINES: number[] = [];

function readStore(): LineStore {
  try {
    return parseLineStore(localStorage.getItem(STORE_KEY));
  } catch {
    return {}; // 隐私模式等拿不到 storage
  }
}

export interface PriceLinesApi {
  lines: number[];
  locked: boolean;
  add: (price: number) => void;
  removeAt: (index: number) => void;
  moveAt: (index: number, price: number) => void;
  clear: () => void;
  toggleLock: () => void;
}

/**
 * 水平线的状态：一张按标的索引的价目表 + 一把全局锁，都落在 localStorage。
 *
 * 改动一律走**函数式 setState**：调用方是画布的手势回调（拖拽时每个 pointermove
 * 都改一笔），它们拿不到最新的 store，读闭包里的旧值会把一次拖拽里的改动丢掉。
 *
 * `key` 为空（没选中标的）时一切写操作都是空转，读出来是空列表。
 */
export function usePriceLines(key: string | null): PriceLinesApi {
  const [store, setStore] = useState<LineStore>(readStore);
  const [locked, setLocked] = useState(() => {
    try {
      return localStorage.getItem(LOCK_KEY) === '1';
    } catch {
      return false;
    }
  });

  useEffect(() => {
    localStorage.setItem(STORE_KEY, JSON.stringify(store));
  }, [store]);

  useEffect(() => {
    localStorage.setItem(LOCK_KEY, locked ? '1' : '0');
  }, [locked]);

  const edit = useCallback(
    (change: (current: number[]) => number[]) => {
      if (!key) return;
      setStore((prev) => ({ ...prev, [key]: change(prev[key] ?? []) }));
    },
    [key],
  );

  const add = useCallback((price: number) => edit((cur) => addPriceLine(cur, price)), [edit]);
  const removeAt = useCallback(
    (index: number) => edit((cur) => removePriceLine(cur, index)),
    [edit],
  );
  const moveAt = useCallback(
    (index: number, price: number) => edit((cur) => movePriceLine(cur, index, price)),
    [edit],
  );
  const clear = useCallback(() => edit(() => []), [edit]);
  const toggleLock = useCallback(() => setLocked((v) => !v), []);

  return {
    lines: (key ? store[key] : undefined) ?? NO_LINES,
    locked,
    add,
    removeAt,
    moveAt,
    clear,
    toggleLock,
  };
}
