/**
 * 系统级快捷键的宿主侧：把和弦交出去注册、录制期间摘下来、状态读回来。
 *
 * 组合键本身只有一个来源（`lib/shortcuts.ts`），这里只管「交给 Rust 的那几次调用」：
 * 判定与切窗口都在 Rust（`src-tauri/src/global_key.rs`），因为全局热键的意义就是
 * 窗口没焦点也能按，而那时网页可能正被节流。
 *
 * 每一条都可能在壳外面跑（浏览器 `npm run dev`），所以一律给「系统级不可用」的返回值：
 * 调用方据此退回只在窗口里生效的那条网页监听，不抛错、不打断界面。
 */
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { IS_TAURI } from './tauri';
import { acceleratorOf, readChord, type ShortcutId } from './shortcuts';

/** 与 `global_key.rs` 的 `STATE_EVENT` 同一个字符串：跨 FFI 只能手工对齐。 */
const STATE_EVENT = 'global-shortcut-state';

export interface GlobalKeyStatus {
  /** 系统级是否已生效。 */
  registered: boolean;
  /** 当前这把键的 accelerator 写法（`Alt+D`），没设置过则是空串。 */
  accelerator: string;
  /** 注册失败的原因（生效时为 null）。 */
  error: string | null;
}

const UNAVAILABLE: GlobalKeyStatus = { registered: false, accelerator: '', error: null };

async function call(command: string, args?: Record<string, unknown>): Promise<GlobalKeyStatus> {
  if (!IS_TAURI) return UNAVAILABLE;
  try {
    return await invoke<GlobalKeyStatus>(command, args);
  } catch {
    // 宿主没答上（老壳没这条命令、或状态被谁改坏了）：当系统级不可用，窗口内那条兜着。
    return UNAVAILABLE;
  }
}

/** 按 `id` 当前落盘的和弦换键（先读 localStorage，再翻译成 accelerator）。 */
export function applyGlobalShortcut(id: ShortcutId): Promise<GlobalKeyStatus> {
  const accelerator = acceleratorOf(readChord(id));
  if (!accelerator) return Promise.resolve(UNAVAILABLE); // 翻译不出来的组合键：不去烦系统
  return call('set_global_shortcut', { accelerator });
}

/**
 * 摘掉全局键：录制新组合键之前必须走这一步。
 *
 * 系统级的键会被吃掉，网页的 keydown 收不到 —— 不摘的话用户按老键位不但录不上，
 * 还会顺手把窗口切走。录完（或取消）由调用方再发一次 `applyGlobalShortcut`：
 * 键的权威在 localStorage，那边现读出来的就是该挂回去的那把。
 */
export function clearGlobalShortcut(): Promise<GlobalKeyStatus> {
  return call('set_global_shortcut', { accelerator: null });
}

export function readGlobalShortcutStatus(): Promise<GlobalKeyStatus> {
  return call('global_shortcut_status');
}

/** 订阅状态变化（换键、录制都会改）。返回的是「取消订阅」的 Promise；壳外给空实现。 */
export function onGlobalShortcutState(
  handler: (status: GlobalKeyStatus) => void,
): Promise<() => void> {
  if (!IS_TAURI) return Promise.resolve(() => undefined);
  return listen<GlobalKeyStatus>(STATE_EVENT, (e) => handler(e.payload)).then(
    (unlisten) => () => unlisten(),
  );
}
