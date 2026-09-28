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

/** 启动时的静默检查节流：一天一次，够用且不碰 GitHub 的 60 次/小时限额。 */
export const CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;

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
