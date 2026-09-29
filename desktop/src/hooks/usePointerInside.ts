import { useEffect, useState, type RefObject } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import type { UnlistenFn } from '@tauri-apps/api/event';
import { IS_TAURI } from '../lib/tauri';

/**
 * 「指针是否真的落在面板上」，**默认不在**。
 *
 * 为什么不用 CSS `:hover`：`:hover` 只在浏览器真的收到鼠标消息时才更新，而这个窗口会
 * 自己动 —— 拖拽会搬走它，按自选条数改高度时「下边缘不动」会让上边缘跑，收起时整个隐藏。
 * 鼠标位置一旦在这种变化里落到了窗口外，Chromium 就再也收不到 `mouseleave`，
 * `:hover` 于是永久卡在「亮着」：鼠标早就不在上面了，行的 hover 底色却一直赖着 ——
 * 这种静态痕迹比整层遮罩卡住更难发现（曾经的表现就是「某一行一直亮」）。
 *
 * 所以这里显式维护状态，规则只有一条：**只有指针自己的事件能点亮；任何能证明
 * 「指针位置已经不可信」的信号一律熄灭**。行的 hover 底色挂在返回的 `.inside` 上。
 *
 * 指针事件挂在面板元素上而不是 window 上：要的是「指针在面板上」，不是「指针在这个
 * 视口里任意位置」—— 窗口比面板大时（测试视口就是这样）两者会得出不同结论。
 */
export function usePointerInside<T extends HTMLElement>(panel: RefObject<T | null>): boolean {
  const [inside, setInside] = useState(false);

  useEffect(() => {
    const el = panel.current;
    if (!el) return;

    const enter = () => setInside(true);
    const leave = () => setInside(false);
    /** 页面不可见（窗口被收起）之后再也收不到指针事件，只能由我们自己熄灭。 */
    const onVisibility = () => {
      if (document.hidden) leave();
    };

    el.addEventListener('pointermove', enter);
    // pointerleave 只在真正离开元素边界时触发（子元素之间穿行不算），正是要的语义
    el.addEventListener('pointerleave', leave);
    // 窗口失焦 = 鼠标已经在别的窗口上（点了任务栏、点了别的应用、右键菜单弹出来了）
    window.addEventListener('blur', leave);
    // 视口尺寸变了，鼠标相对窗口的位置随之失效
    window.addEventListener('resize', leave);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      el.removeEventListener('pointermove', enter);
      el.removeEventListener('pointerleave', leave);
      window.removeEventListener('blur', leave);
      window.removeEventListener('resize', leave);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [panel]);

  // 还有两种「指针位置已失效」只有窗口自己知道：被搬走（onMoved）与被改尺寸
  // （onResized，自选条数变化时触发）。浏览器里没有对应事件，故只在壳内挂。
  useEffect(() => {
    if (!IS_TAURI) return;
    const leave = () => setInside(false);
    const win = getCurrentWindow();
    const offs: Promise<UnlistenFn>[] = [
      win.onMoved(leave),
      win.onResized(leave),
      win.onFocusChanged(({ payload }) => {
        if (!payload) leave();
      }),
    ];
    return () => {
      for (const off of offs) void off.then((f) => f());
    };
  }, []);

  return inside;
}
