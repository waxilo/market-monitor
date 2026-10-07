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
 *
 * `timeoutMs` 是**请求级**时限（测速用：「5 秒不响应即判超时」）；数据请求不传，
 * 宿主侧保持默认 20s —— K 线一次 300 根，慢网直连要的时间比 5 秒长得多。
 */
export async function httpRequest(
  method: 'GET' | 'POST',
  url: string,
  body?: string,
  timeoutMs?: number,
): Promise<HttpResult> {
  if (IS_TAURI) {
    return await invoke<HttpResult>('market_request', {
      method,
      url,
      body: body ?? null,
      timeoutMs: timeoutMs ?? null,
    });
  }
  try {
    const res = await fetch(url, {
      method,
      headers: body != null ? { 'Content-Type': 'application/json' } : undefined,
      body,
      signal: timeoutMs == null ? undefined : AbortSignal.timeout(timeoutMs),
    });
    return { status: res.status, body: await res.text() };
  } catch (error) {
    // 浏览器回退没有宿主侧的短结论，超时在这里折成同一句（与 market.rs 的 short_error 同口径）
    if (error instanceof DOMException && error.name === 'TimeoutError') throw new Error('连接超时');
    throw error;
  }
}
