import { useEffect, useRef } from 'react';
import { matchesChord, readChord, type ShortcutId } from '../lib/shortcuts';

/**
 * 把一条快捷键绑到 `window` 的 keydown 上。
 *
 * 两个刻意的设计：
 *
 * 1. **和弦在按下时才读**（`readChord` 现读 localStorage），不作为 state 传进来。
 *    主窗与悬浮窗是两套 React 实例，设置面板只活在主窗 ⇒ 用 prop 传的话，
 *    在设置里改完快捷键，悬浮窗要等下一次 localStorage 轮询（4s）才会跟上；
 *    现读的话下一次按键就是新键。代价是每按键一次读一次 localStorage，可忽略。
 * 2. `handler` 放 ref 里、`useEffect` 的依赖只留 `enabled` —— 事件监听不随
 *    每次渲染重绑（绑定/解绑发生在按键之间才安全，否则手势中途会丢事件）。
 *
 * `enabled=false` 时不响应（设置面板打开期间就是这种状态：录制要用同一批按键）。
 */
export function useShortcut(id: ShortcutId, handler: () => void, enabled = true) {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!enabled) return;
    function onKey(e: KeyboardEvent) {
      // 长按重复触发 = 连点好几下窗口开关；已被别人处理过的（如录制中）也不抢
      if (e.repeat || e.defaultPrevented) return;
      if (!matchesChord(e, readChord(id))) return;
      e.preventDefault();
      handlerRef.current();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [id, enabled]);
}
