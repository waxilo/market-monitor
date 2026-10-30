import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { parseItems, watchKey, type WatchItem } from './useWatchlist';

/** 打开的标签页（每个 = 一个「市场 + 标的」）、当前标签页、被锁住不能关的那些。 */
const TABS_KEY = 'mm.tabs';
const ACTIVE_KEY = 'mm.tabActive';
const LOCKS_KEY = 'mm.tabLocked';

/** 首装（没存过）先开一张 BTC 永续的图：空桌面连从哪搜起都不知道。 */
const DEFAULT_TABS: WatchItem[] = [{ market: 'FUTURES', symbol: 'BTC_USDT' }];

function readTabs(): WatchItem[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(TABS_KEY);
  } catch {
    return DEFAULT_TABS;
  }
  return parseItems(raw) ?? DEFAULT_TABS;
}

/** 存着的高亮项只有在标签页里还在时才认，否则回落到第一个。 */
function initialActive(tabs: WatchItem[]): string | null {
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(ACTIVE_KEY);
  } catch {
    /* 拿不到 storage 就等于没存过 */
  }
  if (saved != null && tabs.some((t) => watchKey(t) === saved)) return saved;
  return tabs.length > 0 ? watchKey(tabs[0]) : null;
}

/** 锁记号只保留还开着的标签页：关掉的标签页重新打开时**不该继承**上一轮的锁。 */
function readLocks(tabs: WatchItem[]): string[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(LOCKS_KEY);
  } catch {
    return [];
  }
  if (raw == null) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    const open = new Set(tabs.map(watchKey));
    return parsed.filter((k): k is string => typeof k === 'string' && open.has(k));
  } catch {
    return [];
  }
}

/**
 * 浏览器的标签页：一个标签 = 一个「市场 + 标的」，打开就一直挂着，只有用户自己关它才没。
 *
 * 和旧侧栏的分别：侧栏是「一份收藏名单」，换市场会把另一边的条目整列换掉；标签页是
 * 「打开过什么」，现货与永续混在同一条里，一个市场的标签不会因为你在看另一个市场而消失。
 *
 * **锁**（浏览器里叫「固定标签」）只是不给关：`close` 与 `closeAll` 都跳过它。开关锁
 * 本身不落「是否可关」以外的任何状态，所以关掉标签页时顺手把它的锁记号删掉。
 */
export function useTabs() {
  const [tabs, setTabs] = useState<WatchItem[]>(readTabs);
  // 初值直接读上一行的 `tabs`：两者必须来自同一次读盘，否则「存着的高亮项」会对不上刚解析出的列表
  const [activeKey, setActiveKeyState] = useState<string | null>(() => initialActive(tabs));
  const [lockedList, setLockedList] = useState<string[]>(() => readLocks(tabs));

  /**
   * 三个 ref 各跟一份最新状态：关闭 / 关闭全部要**同步**读到当前列表，而 setState 的
   * updater 里不能再触发别的 setState。ref 在每次写状态之前先自更新，连续两次关闭
   * （`closeAll` 里逐条走 `close` 的老写法）也不会互相看见对方的旧值。
   */
  const tabsRef = useRef(tabs);
  const activeRef = useRef(activeKey);
  const lockedRef = useRef(lockedList);
  tabsRef.current = tabs;
  activeRef.current = activeKey;
  lockedRef.current = lockedList;

  useEffect(() => {
    localStorage.setItem(TABS_KEY, JSON.stringify(tabs));
  }, [tabs]);
  useEffect(() => {
    if (activeKey != null) localStorage.setItem(ACTIVE_KEY, activeKey);
    else localStorage.removeItem(ACTIVE_KEY);
  }, [activeKey]);
  useEffect(() => {
    localStorage.setItem(LOCKS_KEY, JSON.stringify(lockedList));
  }, [lockedList]);

  /** 已经开着就只切过去（**不重复开**）：搜索里点同一个标的不该长出第二张图。 */
  const open = useCallback((item: WatchItem) => {
    const key = watchKey(item);
    const prev = tabsRef.current;
    if (!prev.some((t) => watchKey(t) === key)) {
      const next = [...prev, item];
      tabsRef.current = next;
      setTabs(next);
    }
    activeRef.current = key;
    setActiveKeyState(key);
  }, []);

  const setActive = useCallback((key: string) => {
    activeRef.current = key;
    setActiveKeyState(key);
  }, []);

  const close = useCallback((key: string) => {
    if (lockedRef.current.includes(key)) return;
    const prev = tabsRef.current;
    const idx = prev.findIndex((t) => watchKey(t) === key);
    if (idx < 0) return;
    const next = prev.filter((_, i) => i !== idx);
    tabsRef.current = next;
    setTabs(next);
    const locks = lockedRef.current.filter((k) => k !== key);
    lockedRef.current = locks;
    setLockedList(locks);
    // 关掉的正是当前那张：接着看原位置的下一个，没有就退回上一个（与浏览器一致）
    if (activeRef.current === key) {
      const fallback = next[idx] ?? next[idx - 1];
      const nextActive = fallback ? watchKey(fallback) : null;
      activeRef.current = nextActive;
      setActiveKeyState(nextActive);
    }
  }, []);

  /** 一键关闭：锁住的不动；当前那张被关掉就落到第一个留下的标签页。 */
  const closeAll = useCallback(() => {
    const prev = tabsRef.current;
    const next = prev.filter((t) => lockedRef.current.includes(watchKey(t)));
    if (next.length === prev.length) return;
    tabsRef.current = next;
    setTabs(next);
    if (next.length === 0) {
      activeRef.current = null;
      setActiveKeyState(null);
    } else if (activeRef.current == null || !next.some((t) => watchKey(t) === activeRef.current)) {
      const nextActive = watchKey(next[0]);
      activeRef.current = nextActive;
      setActiveKeyState(nextActive);
    }
  }, []);

  const toggleLock = useCallback((key: string) => {
    const prev = lockedRef.current;
    const next = prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key];
    lockedRef.current = next;
    setLockedList(next);
  }, []);

  const active = useMemo(
    () => tabs.find((t) => watchKey(t) === activeKey) ?? null,
    [tabs, activeKey],
  );
  const locked = useMemo(() => new Set(lockedList), [lockedList]);

  return { tabs, active, activeKey, locked, open, close, closeAll, toggleLock, setActive };
}
