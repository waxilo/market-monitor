/**
 * 当前合约数据源的自动兜底。
 *
 * 触发条件（任一）：一次取数请求失败、或成功但往返超过 500ms。
 *
 * 两条触发路径的取舍不同：
 * - **失败**：全部候选（含当前源）并行探测，第一个成功返回的立即采用 —— 当前源已经
 *   答不上话，不赌它更快，也不等其余候选。
 * - **慢响应**：探测照旧全并行，但必须**等当前源的结果**再判。当前源可达时，只有候选
 *   明显更快（见 shouldSwitchOnSlow）才切；否则保持不动。探测是小请求、整包 K 线是大请求：
 *   后者超过 500ms 很常见，若照「第一个返回即最快」去切，会在几个盘口之间来回跳，而图表
 *   每次换源都要清空重拉、且各盘口 OHLC 略有差异 —— 看起来就是蜡烛与影线在漂。
 *
 * 冷却按触发原因分开：失败 10s、响应慢 30s。前者保证新源也挂掉时能较快再换一次，
 * 后者避免「全网都慢」时不停换源。冷却时间戳落在 localStorage：主窗与悬浮窗各跑一套
 * 选源逻辑，共用一个时间戳才不会互相触发、来回抢源。探测本身不参与上报，不会自己触发下一轮。
 */

import {
  FUTURES_ENDPOINTS,
  currentFuturesUrl,
  probeEndpoint,
  setFuturesSource,
  type FuturesEndpoint,
} from './sources';

/** 成功响应超过这个时长就值得重新选一次源。 */
export const SLOW_RESPONSE_MS = 500;
const FAILURE_COOLDOWN_MS = 10_000;
const SLOW_COOLDOWN_MS = 30_000;
/** 跨窗口共享的「上一轮探测开始时间」。 */
const LAST_RACE_KEY = 'mm.autoSource.lastRaceAt';

let racing = false;

function lastRaceAt(): number {
  const raw = Number(localStorage.getItem(LAST_RACE_KEY));
  return Number.isFinite(raw) ? raw : Number.NEGATIVE_INFINITY;
}

/**
 * 上报一次永续取数的结果。`failed` 只表示**源不可用**（网络失败 / 5xx / 网关类状态），
 * 标的不存在这类业务性 4xx 由调用方挡掉，不在这里触发换源。
 */
export function noteFuturesRequest(durationMs: number, failed: boolean): void {
  if (!failed && durationMs <= SLOW_RESPONSE_MS) return;
  if (racing) return;
  const now = Date.now();
  const cooldown = failed ? FAILURE_COOLDOWN_MS : SLOW_COOLDOWN_MS;
  if (now - lastRaceAt() < cooldown) return;
  racing = true;
  localStorage.setItem(LAST_RACE_KEY, String(now));
  void raceFastest(failed).finally(() => {
    racing = false;
  });
}

/** 这些 HTTP 状态说明「这个源现在不可用」，而不是「这次参数不对」。 */
export function isSourceFailureStatus(status: number): boolean {
  return (
    status >= 500 ||
    status === 403 ||
    status === 404 ||
    status === 408 ||
    status === 418 ||
    status === 425 ||
    status === 429 ||
    status === 451
  );
}

/**
 * 慢路径的换源门槛：候选必须比当前源**明显**更快才值得切。
 *
 * 只差几十毫秒的探测结果不足以抵消换源的代价（图表清空重拉、两个盘口的 K 线不完全一致）。
 * 取「1.5 倍」与「多 300ms」中更严的一条：低延迟时看绝对差，高延迟时看相对差。
 */
export function shouldSwitchOnSlow(currentMs: number, alternativeMs: number): boolean {
  return currentMs > Math.max(alternativeMs * 1.5, alternativeMs + 300);
}

async function raceFastest(failed: boolean): Promise<void> {
  const origin = currentFuturesUrl();
  const winner = failed
    ? ((await firstReachable(FUTURES_ENDPOINTS))?.baseUrl ?? null)
    : await bestReplacement(origin);
  // 探测期间用户可能手动换过源：以手动选择为准，丢弃这轮结果
  if (winner != null && currentFuturesUrl() === origin) setFuturesSource(winner);
}

/** 全部候选同时开测；第一个成功即 resolve，不等其余。 */
function firstReachable(candidates: FuturesEndpoint[]): Promise<FuturesEndpoint | null> {
  return new Promise((resolve) => {
    if (candidates.length === 0) {
      resolve(null);
      return;
    }
    let remaining = candidates.length;
    let settled = false;
    const finish = (result: FuturesEndpoint | null) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };
    for (const endpoint of candidates) {
      void probeEndpoint(endpoint)
        .then((outcome) => {
          if (outcome.ok) finish(endpoint);
        })
        .catch(() => {
          /* probeEndpoint 自己会折失败；这里只兜住意外异常 */
        })
        .finally(() => {
          remaining -= 1;
          if (remaining === 0) finish(null);
        });
    }
  });
}

interface ProbeResult {
  endpoint: FuturesEndpoint;
  /** 成功时的往返毫秒；失败为 null。 */
  latencyMs: number | null;
}

/**
 * 慢响应的换源判定：等全部探测落地后，拿当前源与最快的候选比。
 * 当前源探测失败（或不在候选清单里）→ 采用最快的可用候选；可达 → 只有明显更快才切。
 */
async function bestReplacement(origin: string): Promise<string | null> {
  const results: ProbeResult[] = await Promise.all(
    FUTURES_ENDPOINTS.map(async (endpoint) => {
      const outcome = await probeEndpoint(endpoint);
      return {
        endpoint,
        latencyMs: outcome.ok
          ? (outcome.latencyMs ?? Number.POSITIVE_INFINITY)
          : null,
      };
    }),
  );

  let current: ProbeResult | null = null;
  let fastest: ProbeResult & { latencyMs: number } | null = null;
  for (const result of results) {
    if (result.endpoint.baseUrl === origin) current = result;
    if (result.latencyMs == null || result.endpoint.baseUrl === origin) continue;
    if (fastest == null || result.latencyMs < fastest.latencyMs) {
      fastest = result as ProbeResult & { latencyMs: number };
    }
  }

  if (fastest == null) return null;
  if (current == null || current.latencyMs == null) return fastest.endpoint.baseUrl;
  return shouldSwitchOnSlow(current.latencyMs, fastest.latencyMs)
    ? fastest.endpoint.baseUrl
    : null;
}
