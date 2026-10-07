/**
 * 告警 webhook：告警线穿越时，除系统通知外再把消息 POST 到用户配置的地址
 * （notify_hub 的 `/hook/:key` 约定 —— JSON 取 `message` 字段，标题由 key 名定）。
 *
 * 存储形如 [`WebhookEndpoint`]，默认空表：不配地址就只弹系统通知，与从前一样。
 * 只认 https（宿主侧 `market_request` 也只放行 https，见 src-tauri/src/market.rs）。
 *
 * 发送是**fire-and-forget**：调用方在告警节拍里同步调用，不 await —— 对端慢或挂掉
 * 只影响这一条消息（结果记进 last，设置页看得见），不许拖住 5s 的判定节拍。
 * 不做重试：宁可少一条推送，也不要出现「半夜补发一串早已过时的穿越」，
 * 系统通知已经报过一次，用户没看手机也能在应用里看到。
 *
 * 读的时候认不出/非法的条目一律丢掉 —— 手改坏的 localStorage 不会把界面弄崩
 * （与 chartViewPrefs 的落盘口径一致）。
 */

import { useSyncExternalStore } from 'react';
import { httpRequest } from './http';

const KEY = 'mm.webhooks';

/** 一次发送的结果（测试与真实告警共用一格展示）。 */
export interface WebhookSend {
  /** 毫秒时间戳。 */
  at: number;
  ok: boolean;
  /** 成功记 HTTP 码，失败记短句（连接超时 / HTTP 403…）。 */
  detail: string;
  /** true = 设置页「测试」按钮发的，false/缺省 = 真实告警。 */
  test?: boolean;
}

export interface WebhookEndpoint {
  id: string;
  /** 展示用名称，可空（QQ 里的标题是 notify_hub 那个 key 的名字，不归这里管）。 */
  name: string;
  url: string;
  last?: WebhookSend;
}

/** 测试消息文案：对端收到这一条即证明整条链路（含 key、机器人）是通的。 */
export const TEST_MESSAGE = 'Market Monitor 测试：通知链路正常';

function newId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

function parseLast(raw: unknown): WebhookSend | undefined {
  if (typeof raw !== 'object' || raw === null) return undefined;
  const d = raw as Record<string, unknown>;
  if (typeof d.at !== 'number' || !Number.isFinite(d.at)) return undefined;
  if (typeof d.ok !== 'boolean' || typeof d.detail !== 'string') return undefined;
  return { at: d.at, ok: d.ok, detail: d.detail, test: d.test === true };
}

export function parseWebhooks(raw: string | null): WebhookEndpoint[] {
  if (raw == null) return [];
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(data)) return [];
  const out: WebhookEndpoint[] = [];
  for (const item of data) {
    if (typeof item !== 'object' || item === null) continue;
    const d = item as Record<string, unknown>;
    // 地址空着的新增行**保留**（界面上正摆着这一行，重开面板却不见了才叫怪）；
    // 发送时按 validateWebhookUrl 过滤，不留神把半截地址当目的地。
    if (typeof d.url !== 'string') continue;
    out.push({
      id: typeof d.id === 'string' && d.id !== '' ? d.id : newId(),
      name: typeof d.name === 'string' ? d.name : '',
      url: d.url.trim(),
      last: parseLast(d.last),
    });
  }
  return out;
}

/**
 * URL 校验：返回 null 表示可用，否则为可直接展示的中文错误（与 App 端同口径）。
 */
export function validateWebhookUrl(raw: string): string | null {
  const url = raw.trim();
  if (url === '') return '请填写 Webhook 地址';
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return '地址格式不合法';
  }
  if (parsed.protocol !== 'https:') {
    return parsed.protocol === 'http:' ? '仅支持 HTTPS 地址' : `不支持的协议：${parsed.protocol.replace(':', '')}`;
  }
  if (parsed.hostname === '') return '缺少主机名';
  return null;
}

// —————————————————————————— 存储与广播 ——————————————————————————

let current: WebhookEndpoint[] = parseWebhooks(localStorage.getItem(KEY));
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function apply(next: WebhookEndpoint[]): void {
  current = next;
  localStorage.setItem(KEY, JSON.stringify(next));
  emit();
}

export function addWebhook(name: string, url: string): void {
  apply([...current, { id: newId(), name: name.trim(), url: url.trim() }]);
}

/** 改名称/地址：改地址等于换了一条路，旧的发送结果不再代表它，顺手清掉。 */
export function updateWebhook(id: string, patch: { name?: string; url?: string }): void {
  apply(
    current.map((e) =>
      e.id === id
        ? {
            ...e,
            name: patch.name !== undefined ? patch.name : e.name,
            url: patch.url !== undefined ? patch.url.trim() : e.url,
            last: patch.url !== undefined && patch.url.trim() !== e.url ? undefined : e.last,
          }
        : e,
    ),
  );
}

export function removeWebhook(id: string): void {
  apply(current.filter((e) => e.id !== id));
}

function record(id: string, send: WebhookSend): void {
  apply(current.map((e) => (e.id === id ? { ...e, last: send } : e)));
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useWebhooks(): WebhookEndpoint[] {
  return useSyncExternalStore(subscribe, () => current, () => current);
}

// —————————————————————————— 发送 ——————————————————————————

/** 失败原因短句：invoke 拒绝时抛的是 Rust 侧的短串（连接超时 / TLS 握手失败…）。 */
function shortError(error: unknown): string {
  if (typeof error === 'string' && error !== '') return error;
  if (error instanceof Error && error.message !== '') return error.message;
  return '发送失败';
}

/** 发一次 `POST {"message": …}`（notify_hub 的默认/自定义两种 key 模式都认这条）。 */
export async function sendWebhookMessage(
  url: string,
  message: string,
): Promise<{ ok: boolean; detail: string }> {
  try {
    const res = await httpRequest('POST', url, JSON.stringify({ message }));
    const ok = res.status >= 200 && res.status < 300;
    return { ok, detail: `HTTP ${res.status}` };
  } catch (error) {
    return { ok: false, detail: shortError(error) };
  }
}

/**
 * 告警触达：给每个配置的端点发一条，不等待结果 —— 节拍（usePriceAlerts）里同步调用。
 * 结果落回各端点的 last，设置页看得到「上次发送 … 已送达/失败」。
 * 空地址/不合规地址（还在编辑中的行、被手改坏的存储）直接跳过：那不是目的地。
 */
export function sendAlertWebhook(message: string): void {
  for (const endpoint of current) {
    if (validateWebhookUrl(endpoint.url) != null) continue;
    void sendWebhookMessage(endpoint.url, message).then((r) => {
      record(endpoint.id, { at: Date.now(), ok: r.ok, detail: r.detail });
      if (!r.ok) console.warn(`Webhook 发送失败（${endpoint.url}）：${r.detail}`);
    });
  }
}

/** 设置页「测试」：等结果再把整条短句交回去展示，同时落进 last。 */
export async function testWebhook(endpoint: WebhookEndpoint): Promise<WebhookSend> {
  const r = await sendWebhookMessage(endpoint.url, TEST_MESSAGE);
  const send: WebhookSend = { at: Date.now(), ok: r.ok, detail: r.detail, test: true };
  record(endpoint.id, send);
  return send;
}
