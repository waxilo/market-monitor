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
import { spotDialectOf, type SpotDialect, type SpotSourceId } from './spotDialects';

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
 * 与主域是同一套后端、同一份可达性（实测：本机直连时 6 个币安域一起全红，而 Gate
 * 照常）—— 一起有效一起失效的席位只是把探测弹窗填满，不提供额外逃生通道。所以这里**只留
 * 主域与 Aster 两个**，腾出的位置给真正独立的盘口（HTX）。
 *
 * ❌ **Bitunix 已于 2026-10-14 下线**（与 App 同步）：它的 REST K 线只返回已收盘蜡烛、
 * 永远不含进行中那一根，表象是「价格一致但 K 线图最后一根落后一个周期」。
 */
export const FUTURES_ENDPOINTS: FuturesEndpoint[] = [
  { label: 'Aster 官方', baseUrl: 'https://fapi.asterdex.com', note: 'Aster 盘口 · 大陆通常可直连 · 只有 USDT 合约', dialect: 'BINANCE' },
  { label: '币安合约 主域', baseUrl: 'https://fapi.binance.com', note: '币安盘口 · 大陆通常需代理 · 含 USDC 永续', dialect: 'BINANCE' },
  { label: 'OKX', baseUrl: 'https://www.okx.com', note: '独立盘口 · 大陆通常需代理', dialect: 'OKX' },
  { label: 'Bybit', baseUrl: 'https://api.bybit.com', note: '独立盘口 · 大陆通常需代理', dialect: 'BYBIT' },
  { label: 'Bitget', baseUrl: 'https://api.bitget.com', note: '独立盘口', dialect: 'BITGET' },
  { label: 'Gate', baseUrl: 'https://api.gateio.ws', note: '独立盘口 · 大陆通常可直连 · 永续无公开 USDC', dialect: 'GATE' },
  { label: 'MEXC', baseUrl: 'https://contract.mexc.com', note: '独立盘口', dialect: 'MEXC' },
  { label: 'Hyperliquid', baseUrl: 'https://api.hyperliquid.xyz', note: '独立盘口 · 链上永续', dialect: 'HYPERLIQUID' },
  { label: 'HTX 火币', baseUrl: 'https://api.hbdm.com', note: '独立盘口 · 域名偶有污染', dialect: 'HTX' },
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

/** 当前合约源的方言 id：要不要开实时通道、开哪种，都看它。 */
export function currentFuturesDialectId(): DialectId {
  return endpointOf(currentFuturesUrl()).dialect;
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

/**
 * 当前数据源（baseUrl），非 React 取数用。
 *
 * 每次先对一次 localStorage：自动换源可能发生在**另一个窗口**（主窗 ⇄ 悬浮窗），
 * 而主窗没有定时同步。只靠 setFuturesSource 的广播在本窗口内够用，跨窗口得在读的时候追平。
 */
export function currentFuturesUrl(): string {
  syncFuturesSource();
  return current;
}

export function useFuturesSourceUrl(): string {
  return useSyncExternalStore(subscribe, () => current, () => current);
}

// —————————————————————————— 现货源（可选，默认币安） ——————————————————————————

/**
 * 现货候选。与合约不同，这里**不做本机测速**：两家真正的区别只有「大陆能不能直连」，
 * 用户按自己的网络常识点一下即可，没必要为两个选项再打一轮探测。
 *
 * **出厂是币安**（2026-10-30 用户指定，与 App 端同口径）：`data-api.binance.vision` 优先
 * （实测大陆可直连），再退到 api1~4 与主域。这些域共享同一个 IP 限频池，所以这层只解决
 * 可达性，不解决限频；现货与合约是**两条独立**的限频/封禁线，合约被限不影响这里。
 *
 * 顺序即 SourcePanel 的列表顺序，也保证 `SPOT_ENDPOINTS[0]` 就是出厂值。
 */
export interface SpotEndpoint {
  id: SpotSourceId;
  label: string;
  /** 依次尝试的 REST 主机：当前一个失败（网络/5xx/网关类状态）才试下一个。 */
  hosts: string[];
  note: string;
}

export const SPOT_ENDPOINTS: SpotEndpoint[] = [
  {
    id: 'BINANCE',
    label: '币安现货',
    hosts: [
      'https://data-api.binance.vision',
      'https://api1.binance.com',
      'https://api2.binance.com',
      'https://api3.binance.com',
      'https://api4.binance.com',
      'https://api.binance.com',
    ],
    note: 'data-api 镜像优先 · 与合约限频/封禁分开计',
  },
  {
    id: 'GATE',
    label: 'Gate 现货',
    hosts: ['https://api.gateio.ws'],
    note: '大陆通常可直连 · 有 USDC 现货',
  },
];

export const DEFAULT_SPOT_SOURCE: SpotSourceId = 'BINANCE';

const SPOT_KEY = 'mm.spotSource';

export function normalizeSpotSource(raw: string | null): SpotSourceId {
  return SPOT_ENDPOINTS.some((e) => e.id === raw) ? (raw as SpotSourceId) : DEFAULT_SPOT_SOURCE;
}

export function spotEndpointOf(id: SpotSourceId): SpotEndpoint {
  return SPOT_ENDPOINTS.find((e) => e.id === id) ?? SPOT_ENDPOINTS[0];
}

let currentSpot: SpotSourceId = normalizeSpotSource(localStorage.getItem(SPOT_KEY));
const spotListeners = new Set<() => void>();

function emitSpot(): void {
  for (const listener of spotListeners) listener();
}

/** 现货换源广播：api.ts 在模块加载时挂上「清现货缓存」。 */
export function onSpotSourceChange(listener: () => void): void {
  spotListeners.add(listener);
}

function applySpot(next: SpotSourceId): void {
  currentSpot = next;
  localStorage.setItem(SPOT_KEY, next);
  emitSpot();
}

export function setSpotSource(id: SpotSourceId): void {
  const next = normalizeSpotSource(id);
  if (next !== currentSpot) applySpot(next);
}

/** 另一个窗口（主窗 ⇄ 悬浮窗）可能改过选择：读一次，变了就跟进。 */
export function syncSpotSource(): void {
  const stored = normalizeSpotSource(localStorage.getItem(SPOT_KEY));
  if (stored !== currentSpot) applySpot(stored);
}

/** 当前现货源（非 React 取数用）。 */
export function currentSpotSource(): SpotSourceId {
  syncSpotSource();
  return currentSpot;
}

function subscribeSpot(listener: () => void): () => void {
  spotListeners.add(listener);
  return () => {
    spotListeners.delete(listener);
  };
}

export function useSpotSource(): SpotSourceId {
  return useSyncExternalStore(subscribeSpot, () => currentSpot, () => currentSpot);
}

/** 当前现货方言（取数用）。 */
export function currentSpotDialect(): SpotDialect {
  return spotDialectOf(currentSpotSource());
}

/**
 * 取数 effect 的依赖键：两个市场各跟着自己的选择走。
 * 换源后所有按 (market, symbol) 缓存的取数都得重来，否则两个盘口的数据会混在一起。
 */
export function useSourceKey(market: MarketType | undefined): string {
  const futures = useFuturesSourceUrl();
  const spot = useSpotSource();
  if (market === 'FUTURES') return futures;
  if (market === 'SPOT') return spot;
  return '';
}

// —————————————————————————— 探测 ——————————————————————————

export interface ProbeOutcome {
  ok: boolean;
  /** 往返毫秒（ok 时才有）。 */
  latencyMs?: number;
  /** 失败的一行短结论（超时/域名/HTTP 码，由宿主侧归纳）。 */
  reason?: string;
}

/**
 * 测速超时：**5 秒不响应即判超时**（用户口径，两端同）。只掐探测 —— 数据请求
 * （K 线一次 300 根）在慢网直连上需要的时间比这长，不能吃这个口径。
 */
const PROBE_TIMEOUT_MS = 5_000;

/** 对指定候选发一次探活请求（弹窗「全部重新检测」并行调用）。 */
export async function probeEndpoint(endpoint: FuturesEndpoint): Promise<ProbeOutcome> {
  const call = dialectOf(endpoint.dialect).probe(endpoint.baseUrl);
  const started = performance.now();
  try {
    const res = await httpRequest(call.method, call.url, call.body, PROBE_TIMEOUT_MS);
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
