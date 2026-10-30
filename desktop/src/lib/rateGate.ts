/**
 * 合约 REST 的限频闸门。
 *
 * 背景（实测 2026-10-30，经本机代理）：币安对合约 REST 返回 418 时，只有响应头
 * `retry-after: 23070` 和响应体里的 `IP(xxx) banned until ...`。**封禁期内继续发请求，
 * 币安会把封禁一路往后推**（2 分钟 → 5 分钟 → 30 分钟 → 2 小时 → 3 天），所以
 * 「被封了还按 1.2 秒一拍地重试」正是 418 越封越久的直接原因。
 *
 * 这个模块只回答一件事：现在允许向**这个数据源**发合约 REST 请求吗？
 * - 429/418 按服务端给的 `retry-after` 进入冷却；没给就指数退避（418 从 2 分钟起）；
 * - 冷却按源隔离：封的是币安（或 Aster）那一个域名，切到独立盘口后不应继续被卡；
 * - 截止时间落 localStorage：主窗与悬浮窗各跑一套轮询，共用一个截止时间，
 *   才不会你退我进地对着封禁打；
 * - 币安系的 `x-mbx-used-weight-1m` 到高水位时主动收手，不等它变 429。
 *
 * 冷却期丢的是「历史 / 清单 / 持仓量 / REST 兜底」。实时价走 WebSocket，不经过这里；
 * 但别指望冷却期行情就一定有 —— 受限 IP 上币安的 WS 同样一帧不推（见 lib/stream.ts），
 * 那种情况界面要如实说「限频」并引导换源，而不是装作在线。
 */

import { useEffect, useState } from 'react';

const KEY_PREFIX = 'mm.rateREST.';
const UNTIL_PREFIX = `${KEY_PREFIX}until.`;
const STRIKES_PREFIX = `${KEY_PREFIX}strikes.`;

/** 429 没给 retry-after 时的保底冷却。 */
const FALLBACK_429_MS = 5_000;
/** 418 没给 retry-after 时的保底冷却 —— 币安最短的 IP 封禁就是 2 分钟。 */
const FALLBACK_418_MS = 120_000;
/** 退避上限：再久也不如等冷却结束后由下一次真实请求重新判断。 */
const MAX_FALLBACK_MS = 30 * 60_000;

/** 币安合约 IP 权重上限 2400/分钟，这里留出余量。 */
const USED_WEIGHT_WARN = 2_000;
const USED_WEIGHT_PAUSE_MS = 10_000;

function readNumber(key: string): number {
  try {
    const value = Number(localStorage.getItem(key));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

function writeNumber(key: string, value: number): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    /* 隐私模式写不进：本次会话内仍按内存里的值走，只是跨窗口不共享 */
  }
}

/** 冷却截止时间（0 = 这个源从没被限频过）。 */
export function cooldownUntil(origin: string): number {
  return readNumber(UNTIL_PREFIX + origin);
}

export function cooldownRemainingMs(origin: string, now: number = Date.now()): number {
  return Math.max(0, cooldownUntil(origin) - now);
}

export function isCoolingDown(origin: string, now: number = Date.now()): boolean {
  return cooldownRemainingMs(origin, now) > 0;
}

/**
 * 冷却期还没结束就发请求时抛它。`resumeAt` 给上层显示剩余时间用。
 * 名字带 REST：WS 通道不受影响，别把这个错误当成「行情断了」。
 */
export class RateLimitedError extends Error {
  readonly resumeAt: number;

  constructor(resumeAt: number) {
    super('合约接口限频冷却中');
    this.name = 'RateLimitedError';
    this.resumeAt = resumeAt;
  }
}

/** 冷却期拦截。合约 REST 请求发出前调用；不冷却时是空操作。 */
export function assertFuturesAllowed(origin: string, now: number = Date.now()): void {
  const until = cooldownUntil(origin);
  if (until > now) throw new RateLimitedError(until);
}

/**
 * 记一次限频响应。冷却**只延长不缩短**：两个窗口可能一前一后各撞一次 418，
 * 后到的那个拿到更晚的截止时间，应当以它为准。
 *
 * 返回最终的截止时间，调用方拿它做提示。
 */
export function noteRateLimited(
  origin: string,
  status: number,
  retryAfterSecs?: number | null,
  now: number = Date.now(),
): number {
  const strikesKey = STRIKES_PREFIX + origin;
  const strikes = readNumber(strikesKey) + 1;
  const fallback = status === 418 ? FALLBACK_418_MS : FALLBACK_429_MS;
  const backoff = Math.min(fallback * 2 ** (strikes - 1), MAX_FALLBACK_MS);
  // retry-after 是权威口径（币安给的是秒）；没有才退避
  const wait = retryAfterSecs != null && retryAfterSecs > 0 ? retryAfterSecs * 1_000 : backoff;
  const until = Math.max(cooldownUntil(origin), now + wait);
  writeNumber(strikesKey, strikes);
  writeNumber(UNTIL_PREFIX + origin, until);
  emit();
  return until;
}

/**
 * 币安系的分钟权重高水位。到线就短暂停一下 —— 这一分钟余下的额度让给同 IP 的
 * 其他客户端，比撞一次 429 再被封 2 分钟划算得多。
 */
export function noteUsedWeight(
  origin: string,
  usedWeight: number | null | undefined,
  now: number = Date.now(),
): void {
  if (usedWeight == null || usedWeight < USED_WEIGHT_WARN) return;
  const until = Math.max(cooldownUntil(origin), now + USED_WEIGHT_PAUSE_MS);
  writeNumber(UNTIL_PREFIX + origin, until);
  emit();
}

/** 一次成功的合约响应：退避计数清零（冷却本身不受影响，它只由时间决定）。 */
export function noteRateOk(origin: string): void {
  const strikesKey = STRIKES_PREFIX + origin;
  if (readNumber(strikesKey) !== 0) {
    writeNumber(strikesKey, 0);
    emit();
  }
}

const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

/** 订阅冷却变化（本窗口内直接通知；另一窗口靠 storage 事件跟进）。 */
export function subscribeCooldown(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key?.startsWith(KEY_PREFIX)) emit();
  });
}

/** 指定源的冷却剩余毫秒（React 用，每秒刷新一次用于倒计时）。没有源时恒为 0。 */
export function useCooldownMs(origin: string | null): number {
  const [ms, setMs] = useState(0);
  useEffect(() => {
    if (origin == null) {
      setMs(0);
      return;
    }
    const tick = () => setMs(cooldownRemainingMs(origin));
    tick();
    const timer = window.setInterval(tick, 1_000);
    const off = subscribeCooldown(tick);
    return () => {
      window.clearInterval(timer);
      off();
    };
  }, [origin]);
  return ms;
}
