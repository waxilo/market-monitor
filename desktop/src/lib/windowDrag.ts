import { useCallback, useEffect, useRef } from 'react';
import type { MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { IS_TAURI } from './tauri';
import { DRAG_THRESHOLD_PX, pastDragThreshold } from './dragThreshold';

/**
 * 拖窗口（主窗顶栏空白区 / 悬浮窗整块）。
 *
 * 刻意**不用** Tauri 注入脚本的 `data-tauri-drag-region`：
 * 那个属性只能标在某个元素上，标了之后连它内部的按钮一起变成拖拽热区
 * ——「点按钮变成拖窗口」；而 `="deep"` 又不能只让空白处可拖。
 * 这里自己判落点，规则明确：按钮/输入框/`[data-no-drag]` 上按下永远不拖。
 *
 * **起来的手感靠位移死区，不靠计时器。** 触发条件是「按下后指针移动超过
 * `DRAG_THRESHOLD_PX`（= 4px，与 Windows 的 `SM_CXDRAG` 默认值一致）」，
 * 判据收在 `lib/dragThreshold.ts` 里（纯函数，有边界单测）。
 *
 * ⚠️ 两个都不能碰的坑：
 *  - **别用 `setPointerCapture`**：按 Pointer Events 规范，捕获会把
 *    `mousedown` / `mouseup` 一并重定向到捕获元素，于是 `click` 也落在它上面
 *    —— 悬浮窗的行是 `button`，捕获到面板根节点就等于**把「点行选中」弄坏**。
 *    所以这里改用**窗口级**监听（捕获阶段），既能收到元素之外的移动，又不动 click 的落点。
 *  - **别用 `pointerleave` 取消**：指针离开元素是拖动过程中的常态（悬浮窗才那么点大），
 *    拿它当取消条件会让「从边缘往外拖」直接失效。
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
  /** 位移死区，默认 `DRAG_THRESHOLD_PX`。 */
  thresholdPx?: number;
  /** `true` = 只认空白处；`false` = 整块都能拖（悬浮窗：行也是入口，整块面板都要能搬）。 */
  blankOnly?: boolean;
  /** 双击空白处最大化（主窗顶栏有「最大化」按钮，双击是 Windows 的习惯）。 */
  dblClickMaximize?: boolean;
}

/**
 * 三个：按下记起点、松手后吞掉系统补发的 click、双击交给 `dblclick`。
 * 移动与松手挂在 window 上。
 */
export interface WindowDragHandlers {
  onPointerDown: (e: ReactPointerEvent) => void;
  onClickCapture: (e: ReactMouseEvent) => void;
  onDoubleClick: (e: ReactMouseEvent) => void;
}

export function useWindowDrag(options: DragOptions = {}): WindowDragHandlers {
  const { thresholdPx = DRAG_THRESHOLD_PX, blankOnly = true, dblClickMaximize = false } = options;
  /** 窗口句柄懒建一次：浏览器里 `IS_TAURI` 为假，压根走不到这儿。 */
  const winRef = useRef<ReturnType<typeof getCurrentWindow> | null>(null);
  /** 当前这次按下的起点（`null` = 没在按 / 已经拖起来了）。 */
  const press = useRef<{ id: number; x: number; y: number } | null>(null);
  /** 本次按下是否已进入拖拽：拖完系统会补一个 click，必须吞掉。只在下一次 `pointerdown` 复位。 */
  const dragged = useRef(false);
  /**
   * 这一对点击里拖动过窗口 —— 搬窗口的手势（按下-拖走、按下-拖走）经常被浏览器配成一次
   * 「双击」，靠它把随之而来的 `dblclick` 一起放过，否则拖完窗口顺手就最大化了。
   * 复位点只有一个：新配对的头一下那个**没被吞掉**的 `click`（`detail === 1`）。
   * 所以「拖了一次窗口」不会把之后的正常双击一起堵死。
   */
  const draggedPair = useRef(false);

  const onPointerDown = useCallback(
    (e: ReactPointerEvent) => {
      // 先无条件复位：万一上一轮的 pointerup 被系统移动循环吃掉，
      // 残留的 true 会把这次的 click 误吞。
      dragged.current = false;
      press.current = null;
      if (!IS_TAURI || e.button !== 0) return;
      if (noDrag(e.target)) return;
      if (blankOnly && interactive(e.target)) return;

      // 这里**只是**记下起点。真正的拖动要等指针走出去超过死区，
      // 那一步在下面的 window 级 pointermove 里 —— 所以抬手时若没越过死区，
      // 就什么也没发生过，浏览器该发的 click 照发（点击语义完整保留）。
      press.current = { id: e.pointerId, x: e.clientX, y: e.clientY };
    },
    [blankOnly],
  );

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      const p = press.current;
      if (p === null || p.id !== e.pointerId) return;
      if (!pastDragThreshold(p.x, p.y, e.clientX, e.clientY, thresholdPx)) return;
      // 越过死区 = 用户明确要搬窗口。清掉 press 让后续 move 不再重复触发，
      // 并且**先置 dragged 再发起**：startDragging 之后系统接管鼠标，
      // 之后的 pointerup/click 都可能到不了，标记必须提前立好。
      press.current = null;
      dragged.current = true;
      draggedPair.current = true;
      void winRef.current?.startDragging();
    };
    // 没有按下行为时这两条纯属空转，所以不区分「是否按下」，常驻即可。
    const onEnd = (e: PointerEvent) => {
      const p = press.current;
      if (p !== null && p.id !== e.pointerId) return;
      press.current = null;
    };
    // 捕获阶段：不让任何中间层 stopPropagation 把移动吞掉
    window.addEventListener('pointermove', onMove, true);
    window.addEventListener('pointerup', onEnd, true);
    window.addEventListener('pointercancel', onEnd, true);
    return () => {
      window.removeEventListener('pointermove', onMove, true);
      window.removeEventListener('pointerup', onEnd, true);
      window.removeEventListener('pointercancel', onEnd, true);
    };
  }, [thresholdPx]);

  const onClickCapture = useCallback((e: ReactMouseEvent) => {
    if (dragged.current) {
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    // 干净的一击 = 新一轮手势开始了：清掉上一轮拖拽留下的双击黑名单，
    // 否则「拖完窗口之后再双击顶栏」会被一直拦着（那一下正是用户要的最大化）。
    if (e.detail === 1) draggedPair.current = false;
  }, []);

  // 双击最大化认的是浏览器的 `dblclick`，不是第二次按下上的 `e.detail === 2`。
  // 后者要求两次按下落在**同一个元素**上才累计到 2：顶栏是十几个并排的 span / button，
  // 两下之间偏出一两个像素就换了元素，计数从头开始，于是「双击毫无反应」。
  // `dblclick` 由浏览器按时间窗和位移配对好再投给共同祖先，没这个毛病。
  const onDoubleClick = useCallback(
    (e: ReactMouseEvent) => {
      if (!dblClickMaximize || !IS_TAURI || draggedPair.current) return;
      if (noDrag(e.target)) return;
      if (blankOnly && interactive(e.target)) return;
      void (winRef.current ??= getCurrentWindow()).toggleMaximize();
    },
    [blankOnly, dblClickMaximize],
  );

  return { onPointerDown, onClickCapture, onDoubleClick };
}
