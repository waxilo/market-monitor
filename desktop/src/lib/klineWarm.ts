/**
 * 非当前周期的 K 线预热调度。
 *
 * 起因：切周期慢的根子是「别的周期根本没数据」—— 缓存只记看过的那几个（`klineCache`
 * 只有 6 条，默认周期条就有 8 个 chip），于是一轮扫过去必然互相挤掉，大多数切换其实是
 * 冷启动、要等一次完整往返。这里把「等用户切过去才取」改成「后台轮着取」：
 * 每个周期各自有一份新鲜度预算，到点了就取一次写进缓存。它不负责「切过去不转圈」
 * （切换一律走加载态，见 `ChartView` 的取数 effect），负责的是**切换那一次请求的退路**：
 * 接口抖了 / 回来是空的，手上还有一份几十秒前的完整序列可以顶上去，而不是一块空图；
 * `mergeRefreshedKlines` 也靠它接住翻旧页补回来的历史。顺带 5s 内来回对比不用等。
 *
 * 三条硬约束（都是为了让「多打几次请求」不变成负担）：
 * - **跳过当前周期**：它自己有 2s 的末根链路，再暖一遍纯属重复；
 * - **同一时刻只做一件**：调用方串行执行，相邻两次之间还留 `WARM_GAP_MS`，
 *   冷启铺开时不至于一秒里打 8 个请求；
 * - **失败要退避**：一次抖动不该让某个周期每分钟重试一次（`WARM_FAIL_BACKOFF_MS`）。
 *
 * 调度本身是纯函数（`planWarm`），时间一律由调用方传进来 —— 好断言，也不受 fake timer 摆布。
 */

/** 非当前周期的目标新鲜度：这么久没取过就该重取一次（用户口径「频率很低」，60s 一轮）。 */
export const WARM_FRESH_MS = 60_000;

/** 相邻两次预热之间的最小间隔（冷启铺开的节奏；稳态下各周期自己错开，基本吃不到这个）。 */
export const WARM_GAP_MS = 1_500;

/** 某个周期失败后的冷却：这段时间内不再碰它，把机会让给别的周期。 */
export const WARM_FAIL_BACKOFF_MS = 30_000;

export interface WarmSlot {
  /** 周期 id（`1m` / `4h` / `1w`…）。 */
  interval: string;
  /** 上次成功写入缓存的时刻；`0` = 从没暖过（冷启要优先铺开）。 */
  fetchedAt: number;
  /** 上次失败的时刻；`0` = 没失败过。 */
  failedAt: number;
}

export interface WarmPlan {
  /** 该取的周期。 */
  interval: string;
  /** 什么时候该取（`<= now` 表示现在就可以）。 */
  dueAt: number;
}

/**
 * 选出**下一个**该预热的周期。
 *
 * 排序口径：先看「到期时间」，再按周期条顺序（并列时靠前的先来）。
 * - 从没暖过的到期时间视为 `0`（最饿），于是冷启会从周期条第一个开始依次铺开；
 * - 失败且还在冷却期的，到期时间 = `failedAt + 退避`，不参与抢跑，但**全部都在冷却**时
 *   返回其中最早的那个 —— 调用方据此睡到那一刻，而不是空转轮询。
 *
 * 返回 `null` = 没什么可暖的（列表为空 / 只剩当前周期）。
 */
export function planWarm(
  slots: readonly WarmSlot[],
  now: number,
  currentInterval: string,
): WarmPlan | null {
  let best: WarmPlan | null = null;
  for (const slot of slots) {
    if (slot.interval === currentInterval) continue;
    const dueAt = dueAtOf(slot, now);
    if (best == null || dueAt < best.dueAt) best = { interval: slot.interval, dueAt };
  }
  return best;
}

/** 这一条什么时候该重暖（`0` = 从没暖过，立刻可动）。 */
function dueAtOf(slot: WarmSlot, now: number): number {
  const cooling = slot.failedAt > 0 && now - slot.failedAt < WARM_FAIL_BACKOFF_MS;
  // 冷却期内哪怕数据更旧也不动它：刚失败过的多半是接口/网络问题，反复重试只会更糟
  if (cooling) return slot.failedAt + WARM_FAIL_BACKOFF_MS;
  if (slot.fetchedAt <= 0) return 0;
  return slot.fetchedAt + WARM_FRESH_MS;
}
