/**
 * 当前合约数据源的自动兜底。
 *
 * 触发条件（任一）：一次取数请求失败、或成功但往返超过 500ms。
 * 触发后**并行探测全部候选（含当前源）**，第一个成功返回的即视为最快源并立即切换 ——
 * 不等其他候选的探测结束。当前源若先返回，则保持不动（它本来就是这轮最快的）。
 *
 * 冷却按触发原因分开：失败 10s、响应慢 30s。前者保证新源也挂掉时能较快再换一次，
 * 后者避免「全网都慢」时不停换源。探测本身不参与上报，不会自己触发下一轮。
 */

import {
  FUTURES_ENDPOINTS,
  currentFuturesUrl,
  probeEndpoint,
  setFuturesSource,
  type FuturesEndpoint,
  type ProbeOutcome,
} from './sources';

/** 成功响应超过这个时长就值得重新选一次源。 */
export const SLOW_RESPONSE_MS = 500;
const FAILURE_COOLDOWN_MS = 10_000;
const SLOW_COOLDOWN_MS = 30_000;

let racing = false;
let lastRaceAt = Number.NEGATIVE_INFINITY;

/**
 * 上报一次永续取数的结果。`failed` 只表示**源不可用**（网络失败 / 5xx / 网关类状态），
 * 标的不存在这类业务性 4xx 由调用方挡掉，不在这里触发换源。
 */
export function noteFuturesRequest(durationMs: number, failed: boolean): void {
  if (!failed && durationMs <= SLOW_RESPONSE_MS) return;
  if (racing) return;
  const now = performance.now();
  const cooldown = failed ? FAILURE_COOLDOWN_MS : SLOW_COOLDOWN_MS;
  if (now - lastRaceAt < cooldown) return;
  racing = true;
  lastRaceAt = now;
  void raceFastest().finally(() => {
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

async function raceFastest(): Promise<void> {
  const origin = currentFuturesUrl();
  const winner = await firstReachable(FUTURES_ENDPOINTS);
  // 探测期间用户可能手动换过源：以手动选择为准，丢弃这轮结果
  if (winner != null && currentFuturesUrl() === origin) setFuturesSource(winner.baseUrl);
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
        .then((outcome: ProbeOutcome) => {
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
