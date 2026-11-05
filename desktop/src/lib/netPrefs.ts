/**
 * 网络代理口径：**跟随系统**（默认，与浏览器一致）或**强制直连**。
 *
 * 存在 localStorage 而不是宿主侧：主窗与悬浮窗是两个 JS 实例，但共享同一份
 * localStorage；宿主侧的标志只由前端在启动/切换时推过去。
 */
import { invoke } from '@tauri-apps/api/core';
import { IS_TAURI } from './tauri';

const DIRECT_KEY = 'mm.directConnection';

/** 切换后通知同窗口的消费方（WebSocket 是否还允许开、轮询是否要重排）。 */
export const DIRECT_CONNECTION_EVENT = 'mm:direct-connection';

export function readDirectConnection(): boolean {
  return localStorage.getItem(DIRECT_KEY) === '1';
}

let applied: Promise<void> | null = null;

/** 把开关推给宿主；失败只记一句 —— 设置没推上去不该把行情请求一起拖垮。 */
function push(enabled: boolean): Promise<void> {
  if (!IS_TAURI) return Promise.resolve();
  const pending = invoke('set_direct_connection', { enabled }).then(
    () => undefined,
    (error: unknown) => {
      console.warn('设置代理模式失败', error);
    },
  );
  applied = pending;
  return pending;
}

/**
 * 首批请求之前必须先把这个开关推给宿主，否则第一拍仍按默认（跟随系统）发。
 * 多个并发请求共用同一个 promise，不会重复 invoke。
 */
export function ensureDirectConnectionApplied(): Promise<void> {
  applied ??= push(readDirectConnection());
  return applied;
}

/** 设置页切换：先落盘再推宿主；随后各消费方收到 [DIRECT_CONNECTION_EVENT] 自行重排。 */
export function setDirectConnection(enabled: boolean): Promise<void> {
  localStorage.setItem(DIRECT_KEY, enabled ? '1' : '0');
  window.dispatchEvent(new Event(DIRECT_CONNECTION_EVENT));
  return push(enabled);
}
