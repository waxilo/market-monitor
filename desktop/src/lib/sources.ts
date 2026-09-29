/**
 * 合约行情数据源：内置候选清单 + 用户选定的那个（持久化在 localStorage）。
 *
 * 与 App 的 FuturesEndpoints / SettingsViewModel 同一套语义：候选固定打包，**在用户
 * 本机上**并行实测连通性与延迟，再由用户点选 —— 网络环境因地区/代理而异，开发机上的
 * 探测结果不作数。切换后上层会清掉合约缓存并重新同步交易对（见 api.ts 的注册）。
 *
 * 这一个模块同时管两半：**候选与探测**（弹窗用，见 `probeEndpoint`）与
 * **当前选择**（取数用，见 `useFuturesSourceUrl` / `useSourceKey`）。选择存在 localStorage、
 * 跨窗口同步、换源广播清缓存；写入口只有 [`setFuturesSource`]。
 */

import { useSyncExternalStore } from 'react';
import type { MarketType } from './api';
import { DIALECTS, type DialectId, type FuturesDialect } from './dialects';
import { httpRequest } from './http';

export interface FuturesEndpoint {
  /** 展示名。 */
  label: string;
  /** REST base URL，不带路径。 */
  baseUrl: string;
  /** 一行补充说明（盘口来源 / 可达性预期）。 */
  note: string;
  /** 取数走哪套适配器。 */
  dialect: DialectId;
}

/**
 * 内置候选：与 App 的 FuturesEndpoints.ALL 同一份清单（名称与说明照抄）。
 *
 * **清单长度不等于容灾能力**：币安主域的镜像 1/2/3 与老镜像曾是 4 个独立席位，但它们
 * 与主域是同一套后端、同一份可达性（实测：本机直连时 6 个币安域一起全红，而 Gate / Bitunix
 * 照常）—— 一起有效一起失效的席位只是把探测弹窗填满，不提供额外逃生通道。所以这里**只留
 * 主域与 Aster 两个**，腾出的位置给真正独立的盘口（HTX / Bitunix）。
 */
export const FUTURES_ENDPOINTS: FuturesEndpoint[] = [
  { label: 'Aster 官方', baseUrl: 'https://fapi.asterdex.com', note: 'Aster 盘口 · 大陆通常可直连', dialect: 'BINANCE' },
  { label: '币安合约 主域', baseUrl: 'https://fapi.binance.com', note: '币安盘口 · 大陆通常需代理', dialect: 'BINANCE' },
  { label: 'OKX', baseUrl: 'https://www.okx.com', note: '独立盘口 · 大陆通常需代理', dialect: 'OKX' },
  { label: 'Bybit', baseUrl: 'https://api.bybit.com', note: '独立盘口 · 大陆通常需代理', dialect: 'BYBIT' },
  { label: 'Bitget', baseUrl: 'https://api.bitget.com', note: '独立盘口', dialect: 'BITGET' },
  { label: 'Gate', baseUrl: 'https://api.gateio.ws', note: '独立盘口 · 大陆通常可直连', dialect: 'GATE' },
  { label: 'MEXC', baseUrl: 'https://contract.mexc.com', note: '独立盘口', dialect: 'MEXC' },
  { label: 'Hyperliquid', baseUrl: 'https://api.hyperliquid.xyz', note: '独立盘口 · 链上永续', dialect: 'HYPERLIQUID' },
  { label: 'HTX 火币', baseUrl: 'https://api.hbdm.com', note: '独立盘口 · 域名偶有污染', dialect: 'HTX' },
  { label: 'Bitunix', baseUrl: 'https://fapi.bitunix.com', note: '独立盘口 · 大陆通常可直连', dialect: 'BITUNIX' },
];

/**
 * 出厂默认仍是 Gate：桌面端一直用它、且实测可直连（App 的默认是 Aster，那是 Android
 * 设备上的结论）。想换哪个，由用户在弹窗里测速后点选。
 */
export const DEFAULT_FUTURES_URL = 'https://api.gateio.ws';

const DEFAULT_ENDPOINT =
  FUTURES_ENDPOINTS.find((e) => e.baseUrl === DEFAULT_FUTURES_URL) ?? FUTURES_ENDPOINTS[0];

/** 规整存储值：去空白与尾部斜杠；不在清单里的旧值（如手填镜像）回落到默认。 */
export function normalizeFuturesSource(raw: string | null): string {
  const trimmed = (raw ?? '').trim().replace(/\/+$/, '');
  return FUTURES_ENDPOINTS.find((e) => e.baseUrl === trimmed)?.baseUrl ?? DEFAULT_FUTURES_URL;
}

/** 按（规整后的）URL 找候选条目。 */
export function endpointOf(url: string): FuturesEndpoint {
  return FUTURES_ENDPOINTS.find((e) => e.baseUrl === url) ?? DEFAULT_ENDPOINT;
}

export function dialectOf(id: DialectId): FuturesDialect {
  return DIALECTS[id];
}

// —————————————————————————— 选择与广播 ——————————————————————————

const KEY = 'mm.futuresSource';

let current = normalizeFuturesSource(localStorage.getItem(KEY));
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

/**
 * 换源广播：api.ts 在模块加载时挂上「清合约缓存」。
 * 改选择的入口只有本模块，挂在这里就不会漏（顺带避开 api ⇄ sources 的循环依赖）。
 */
export function onFuturesSourceChange(listener: () => void): void {
  listeners.add(listener);
}

function apply(next: string): void {
  current = next;
  localStorage.setItem(KEY, next);
  emit();
}

/** 选定数据源：持久化 + 广播。 */
export function setFuturesSource(url: string): void {
  const next = normalizeFuturesSource(url);
  if (next !== current) apply(next);
}

/** 另一个窗口（主窗 ⇄ 悬浮窗）可能改过选择：读一次，变了就跟进。 */
export function syncFuturesSource(): void {
  const stored = normalizeFuturesSource(localStorage.getItem(KEY));
  if (stored !== current) apply(stored);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** 当前数据源（baseUrl），非 React 取数用。 */
export function currentFuturesUrl(): string {
  return current;
}

export function useFuturesSourceUrl(): string {
  return useSyncExternalStore(subscribe, () => current, () => current);
}

/**
 * 取数 effect 的依赖键：现货固定走 Gate（与所选无关，恒为空串），永续跟着选择走。
 * 换源后所有按 (market, symbol) 缓存的取数都得重来，否则两个盘口的数据会混在一起。
 */
export function useSourceKey(market: MarketType | undefined): string {
  const url = useFuturesSourceUrl();
  return market === 'FUTURES' ? url : '';
}

// —————————————————————————— 探测 ——————————————————————————

export interface ProbeOutcome {
  ok: boolean;
  /** 往返毫秒（ok 时才有）。 */
  latencyMs?: number;
  /** 失败的一行短结论（超时/域名/HTTP 码，由宿主侧归纳）。 */
  reason?: string;
}

/** 对指定候选发一次探活请求（弹窗「全部重新检测」并行调用）。 */
export async function probeEndpoint(endpoint: FuturesEndpoint): Promise<ProbeOutcome> {
  const call = dialectOf(endpoint.dialect).probe(endpoint.baseUrl);
  const started = performance.now();
  try {
    const res = await httpRequest(call.method, call.url, call.body);
    const latencyMs = Math.round(performance.now() - started);
    // 答了但状态码不对也算失败：能连通但取不到数，对用户没意义（与 App 的 probe 同）
    return res.status >= 200 && res.status < 300
      ? { ok: true, latencyMs }
      : { ok: false, reason: `HTTP ${res.status}` };
  } catch (error) {
    // invoke 拒绝时抛的是 Rust 侧那个短字符串（连接超时 / 域名解析失败 / TLS 握手失败）
    const reason =
      typeof error === 'string' ? error : error instanceof Error ? error.message : '探测失败';
    return { ok: false, reason };
  }
}
