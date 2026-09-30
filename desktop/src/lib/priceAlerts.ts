/**
 * 价格告警的判定：**纯函数 + 零依赖**（形状与 `priceLines.ts` 同一套理由 ——
 * `.mts` 核对靠 Node 直接跑，碰不得 `@tauri-apps/*` 与 `lib/api.ts` 那条取数链）。
 *
 * 一拍的全部规矩都收在这里：静默建档、同侧不重复报、取不到价不动档案、
 * 删掉/拖走的线自动退出。告警最容易出的事故是「一夜起来弹二十条」和「拖一次线报一次」，
 * 这两条都只能靠把判定写成可断言的纯函数来钉住。
 *
 * 对 `priceLines` 只留**类型**导入：运行时导入会带上相对路径，而 Node 跑 `.mts` 时
 * 不接受无扩展名的相对导入（那边每个纯模块都得是叶子）。
 */
import type { LineStore } from './priceLines';

/** 现价相对某条线在哪一侧。 */
export type Side = 'above' | 'below';

/**
 * 一条线的稳定标识：`市场:标的@价位`，就是「当前在哪一侧」那张档案的键。
 *
 * 价位写进键里是**故意的**：拖动改价会得到新键，于是那一拍按「新线」静默建档 ——
 * 不会因为在拖的过程中越过了自己的旧价位就报一条出来。
 */
export function lineId(key: string, price: number): string {
  return `${key}@${price}`;
}

/** 一条要盯的告警线：`市场:标的` + 价位。 */
export interface WatchLine {
  key: string;
  price: number;
}

/** 判定出来的穿越（调用方据此弹通知；文案不归这里管）。 */
export interface Crossing {
  key: string;
  /** 告警线的价位（用户在那儿画的那条）。 */
  linePrice: number;
  /** 这一拍的现价。 */
  price: number;
  /** true = 上破（从线下方翻到上方），false = 下破。 */
  up: boolean;
}

/** 一拍的输出：要报的穿越 + 更新后的档案（档案不落盘，只活在这一轮会话里）。 */
export interface TickResult {
  crossings: Crossing[];
  sides: Map<string, Side>;
}

/**
 * 从价目表里挑出开了告警的线。
 *
 * 只认 `alert === true`（`parseLineStore` 已把旧格式的数字迁成「不告警」），
 * 所以升级上来的用户不会莫名其妙开始收通知。
 */
export function alertLines(store: LineStore): WatchLine[] {
  const out: WatchLine[] = [];
  for (const [key, lines] of Object.entries(store)) {
    for (const line of lines) {
      if (line.alert) out.push({ key, price: line.price });
    }
  }
  return out;
}

/**
 * 判一拍。
 *
 * 规矩四条，逐条都有代价：
 * - **档案里没有这条线 = 静默建档**（刚画上线、或刚重启）：不然每次启动都会把
 *   「早就越过去」的线集体报一遍；
 * - **只有翻到另一侧才报**：同侧的连续拍不重复报，上破之后要再报就得先下破；
 * - **`prices` 里没有这个标的 = 这一拍什么都不动**（旧档案原样带过去）：
 *   清掉它会让下一拍变成静默建档，等于把断线期间发生的穿越悄悄吞了；
 * - **档案按「线还在不在」重建**：删掉的、拖走换价位的线（`lineId` 变了）自然退出，
 *   不会越积越多。
 *
 * 现价正好压在价位上不算穿越，也不改档案（贴着线的抖动会报两遍）。
 */
export function judgeTick(
  lines: readonly WatchLine[],
  prices: ReadonlyMap<string, number>,
  before: ReadonlyMap<string, Side>,
): TickResult {
  const crossings: Crossing[] = [];
  const sides = new Map<string, Side>();
  for (const line of lines) {
    const id = lineId(line.key, line.price);
    const price = prices.get(line.key);
    if (price == null || !Number.isFinite(price)) {
      // 取价失败：原样带着旧档案，等下一拍
      const carried = before.get(id);
      if (carried) sides.set(id, carried);
      continue;
    }
    if (price === line.price) {
      const held = before.get(id);
      if (held) sides.set(id, held);
      continue;
    }
    const side: Side = price > line.price ? 'above' : 'below';
    sides.set(id, side);
    const was = before.get(id);
    if (was === undefined || was === side) continue;
    crossings.push({ key: line.key, linePrice: line.price, price, up: side === 'above' });
  }
  return { crossings, sides };
}
