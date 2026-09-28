import { invoke } from '@tauri-apps/api/core';
import { IS_TAURI } from './tauri';

export interface HttpResult {
  status: number;
  body: string;
}

/**
 * 行情请求一律走宿主侧（Rust 的 `market_request`），不用 webview 的 fetch：
 * 只有 Gate / Hyperliquid / 币安现货镜像的响应带 `Access-Control-Allow-Origin`，
 * 其余盘口在 webview 里连响应体都读不到 —— 多数据源只能走这条通道。
 * 好处还有两条：CSP 完全不用管，且与更新下载同一条 TLS 栈 + 跟随系统代理。
 *
 * 浏览器里 `npm run dev` 时退回 fetch：只有带 CORS 的盘口能用（够调界面）。
 */
export async function httpRequest(
  method: 'GET' | 'POST',
  url: string,
  body?: string,
): Promise<HttpResult> {
  if (IS_TAURI) {
    return await invoke<HttpResult>('market_request', { method, url, body: body ?? null });
  }
  const res = await fetch(url, {
    method,
    headers: body != null ? { 'Content-Type': 'application/json' } : undefined,
    body,
  });
  return { status: res.status, body: await res.text() };
}
