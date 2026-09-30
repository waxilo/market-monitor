import { useEffect, useState } from 'react';
import {
  applyGlobalShortcut,
  onGlobalShortcutState,
  readGlobalShortcutStatus,
  type GlobalKeyStatus,
} from '../lib/globalKey';
import type { ShortcutId } from '../lib/shortcuts';
import { IS_TAURI } from '../lib/tauri';

/**
 * 系统级快捷键现在归不归宿主管（true = 归它，网页那条 `useShortcut` 就该歇着）。
 *
 * 传 `id` 的那个窗口（只有主窗）顺带在启动时把落盘的和弦推给宿主 —— 两个窗口都推会
 * 互相抢注册（换键是「先清空再注册」，后到的那个会把前一个刚挂上的摘掉）。
 * 悬浮窗因此只读状态、只听变化。
 *
 * 为什么要跟着状态走而不是让两份监听同时挂着：组合键一旦注册成系统级，多数平台上
 * 这一下按键会被系统吞掉、网页收不到，于是两份监听互不相干；但不是所有平台都吞
 * （Linux/X11 的 grab 会照常投递一份），那时同一个键切两次窗口 = 看着像没反应。
 */
export function useGlobalKey(id?: ShortcutId): boolean {
  const [taken, setTaken] = useState(false);

  useEffect(() => {
    if (!IS_TAURI) return;
    let alive = true;
    const track = (status: GlobalKeyStatus) => {
      if (alive) setTaken(status.registered);
    };
    // 先挂监听，再推和弦：推完宿主广播的那一下要能被收到（收不到也不影响，apply 的返回值同样会更新）
    const unlisten = onGlobalShortcutState(track);
    const first = id ? applyGlobalShortcut(id) : readGlobalShortcutStatus();
    void first.then(track);
    return () => {
      alive = false;
      void unlisten.then((off) => off());
    };
  }, [id]);

  return taken;
}
