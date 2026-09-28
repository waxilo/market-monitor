import { useCallback, useRef } from 'react';
import type { MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { IS_TAURI } from './tauri';

/**
 * 拖窗口（主窗顶栏空白区 / 悬浮窗整块）。
 *
 * 刻意**不用** Tauri 注入脚本的 `data-tauri-drag-region`：
 * 那个属性只能标在某个元素上，标了之后连它内部的按钮一起变成拖拽热区
 * ——「点按钮变成拖窗口」；而 `="deep"` 又不能只让空白处可拖。
 * 这里自己判落点，规则明确：按钮/输入框/`[data-no-drag]` 上按下永远不拖。
 */
const INTERACTIVE = 'button, input, textarea, select, a, [data-no-drag]';

function isElement(target: EventTarget | null): target is Element {
  return target instanceof Element;
}

function noDrag(target: EventTarget | null): boolean {
  return isElement(target) && target.closest('[data-no-drag]') !== null;
}

function interactive(target: EventTarget | null): boolean {
  return isElement(target) && target.closest(INTERACTIVE) !== null;
}

interface DragOptions {
  /**
   * `0` = 按下即拖（主窗顶栏：空白处本来就没有别的交互）；
   * `> 0` = 按住这么久才拖（悬浮窗：行是按钮，「点选一行」必须保留，
   * 只有**按住**才表示要搬窗口）。
   */
  holdMs?: number;
  /** `true` = 只认空白处；`false` = 整块都能按住拖（配合 `holdMs` 用）。 */
  blankOnly?: boolean;
  /** 双击空白处最大化（主窗顶栏有「最大化」按钮，双击是 Windows 的习惯）。 */
  dblClickMaximize?: boolean;
}

export interface WindowDragHandlers {
  onPointerDown: (e: ReactPointerEvent) => void;
  onPointerUp: () => void;
  onPointerCancel: () => void;
  onPointerLeave: () => void;
  onClickCapture: (e: ReactMouseEvent) => void;
}

export function useWindowDrag(options: DragOptions = {}): WindowDragHandlers {
  const { holdMs = 0, blankOnly = true, dblClickMaximize = false } = options;
  const timer = useRef<number | null>(null);
  /** 本次按下是否已经进入拖拽：拖完系统会补一个 click，必须吞掉。 */
  const dragged = useRef(false);

  const clearTimer = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  const onPointerDown = useCallback(
    (e: ReactPointerEvent) => {
      dragged.current = false;
      if (!IS_TAURI || e.button !== 0) return;
      if (noDrag(e.target)) return;
      if (blankOnly && interactive(e.target)) return;

      const win = getCurrentWindow();
      // 双击最大化必须抢在拖拽之前：一旦进了系统的窗口移动循环，第二个 down 就到了
      if (dblClickMaximize && e.detail === 2) {
        clearTimer();
        void win.toggleMaximize();
        return;
      }
      if (holdMs <= 0) {
        dragged.current = true;
        void win.startDragging();
        return;
      }
      clearTimer();
      timer.current = window.setTimeout(() => {
        timer.current = null;
        dragged.current = true;
        void win.startDragging();
      }, holdMs);
    },
    [blankOnly, clearTimer, dblClickMaximize, holdMs],
  );

  const onClickCapture = useCallback((e: ReactMouseEvent) => {
    if (!dragged.current) return;
    dragged.current = false;
    e.preventDefault();
    e.stopPropagation();
  }, []);

  const endPress = useCallback(() => clearTimer(), [clearTimer]);

  return {
    onPointerDown,
    onPointerUp: endPress,
    onPointerCancel: endPress,
    onPointerLeave: endPress,
    onClickCapture,
  };
}
