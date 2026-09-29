import { invoke } from '@tauri-apps/api/core';
import { IS_TAURI } from './tauri';

/** 加速站下拉项：key 与 Rust 侧 `Mirror` 一一对应（前缀、候选链规则都在 Rust）。 */
export const MIRRORS = [
  { key: 'native', label: 'GitHub 原生' },
  { key: 'gh-proxy.com', label: 'gh-proxy.com' },
  { key: 'ghfast.top', label: 'ghfast.top' },
  { key: 'ghproxy.net', label: 'ghproxy.net' },
  { key: 'gh-proxy.org', label: 'gh-proxy.org' },
] as const;

const MIRROR_KEY = 'mm.updateMirror';
const LAST_CHECK_KEY = 'mm.updateLastCheck';
const READY_DISMISS_KEY = 'mm.updateReadyDismissed';

/**
 * 启动时的静默检查节流。
 *
 * ⚠️ 这不是 GitHub API 调用：manifest 走的是
 * `github.com/<o>/<r>/releases/download/<通道>/latest.json`，普通的文件下载，
 * 「匿名 API 60 次/小时」那条限额管不着它。所以节流的目的是少发无谓请求而不是自保：
 * 定成 1 小时 —— 新版本在一小时内就会被发现（发现即**自动下载**），
 * 又不至于因为一天开关几十次应用而把请求打成一串。
 */
export const CHECK_INTERVAL_MS = 60 * 60 * 1000;

export function readMirror(): string {
  const saved = localStorage.getItem(MIRROR_KEY);
  return MIRRORS.some((m) => m.key === saved) ? (saved as string) : 'native';
}

export function writeMirror(key: string) {
  localStorage.setItem(MIRROR_KEY, key);
}

/** 上次检查成功的时间戳；只记成功，失败不占用当天额度。 */
export function readLastCheck(): number {
  return Number(localStorage.getItem(LAST_CHECK_KEY)) || 0;
}

export function markChecked() {
  localStorage.setItem(LAST_CHECK_KEY, String(Date.now()));
}

/**
 * 「安装包已就绪」提示条被「稍后」关掉的那个版本号。
 *
 * 记版本而不是记布尔：同一版关掉就不再烦他，但**下一版必须重新提示** ——
 * 否则一次「稍后」等于把用户永久锁在旧版本上（安装包会一版一版地悄悄下、再也不出声）。
 */
export function readReadyDismissed(): string {
  return localStorage.getItem(READY_DISMISS_KEY) ?? '';
}

export function writeReadyDismissed(version: string) {
  localStorage.setItem(READY_DISMISS_KEY, version);
}

export interface UpdateInfo {
  currentVersion: string;
  latestVersion: string;
  notes: string;
  publishedAt: string | null;
  pageUrl: string;
}

/** progress 为 0..1，-1 表示总量未知（服务端没给 Content-Length）。 */
export type DownloadState =
  | { state: 'idle' }
  | { state: 'running'; progress: number; downloaded: number; total: number }
  | { state: 'done' }
  | { state: 'failed'; message: string };

export async function fetchAppVersion(): Promise<string> {
  if (!IS_TAURI) return '0.1.0 (preview)';
  return await invoke<string>('app_version');
}

/** 检查更新；返回 null 表示已是最新（含更新通道还没发布过比当前更新的版本）。失败时抛出原因。 */
export async function checkUpdate(mirror: string): Promise<UpdateInfo | null> {
  if (!IS_TAURI) throw new Error('浏览器预览不检查更新');
  return await invoke<UpdateInfo | null>('check_update', { mirror });
}

export async function startDownload(mirror: string): Promise<void> {
  await invoke('start_update_download', { mirror });
}

export async function fetchDownloadState(): Promise<DownloadState> {
  if (!IS_TAURI) return { state: 'idle' };
  return await invoke<DownloadState>('get_update_download_state');
}

/** 安装已下载并验签通过的安装包；Windows 交由安装器接管，macOS 原地替换后自动重启。 */
export async function installUpdate(): Promise<void> {
  await invoke('install_update');
}

export async function openUrl(url: string): Promise<void> {
  if (!IS_TAURI) {
    window.open(url, '_blank', 'noopener');
    return;
  }
  await invoke('open_url', { url });
}
