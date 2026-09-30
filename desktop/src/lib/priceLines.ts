/**
 * 水平线（画线）的领域模型：**纯函数 + 零依赖**。
 *
 * 一条线只用一个价格表示 —— 纵向位置由「当前价格量程」实时算出来（见 KlineCanvas
 * 的 yOfPrice），所以缩放/平移刻度时线会跟着价走，而不是钉在屏幕上。
 *
 * 顺序就是**添加顺序**，不排序：拖拽期间下标必须稳定（`movePriceLine` 按下标改一笔），
 * 排一次序就会让正在拖的那条线换位置、后续 pointermove 改到别人身上。
 * 命中判定每次都按当前 y 重新算下标，所以顺序对交互没有任何影响。
 *
 * 零依赖是硬要求：`.mts` 单测靠 Node 直接跑（Node 的 ESM 解析不接受无扩展名相对导入）。
 */

/** 判定「鼠标落在某条线上」的纵向容差（像素）。太小则难点中，太大则两条近线分不开。 */
export const LINE_HIT_PX = 4;

export interface PriceLine {
  price: number;
  /**
   * 告警标记：价格穿越这条线时由宿主弹系统通知（文案按穿越方向分「上破 / 下破」）。
   *
   * 告警挂在**已有的水平线**上，而不是另存一份价位表 —— 同一个价既要画线又要告警是
   * 常态，拆成两份就会在图上叠出两根重合的线（重合了删一条会看着像没反应）。
   */
  alert: boolean;
}

/** 按标的索引的价目表（键 = `市场:标的`）。 */
export type LineStore = Record<string, PriceLine[]>;

/** 读一份价目表：坏数据一律丢弃而不是抛错 —— 它是用户右键点出来的，重建成本极低。 */
export function parseLineStore(raw: string | null): LineStore {
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
  const out: LineStore = {};
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!Array.isArray(value)) continue;
    // 旧格式是一条线存一个数字（`mm.priceLines` 最早就这么存的）：当成「不告警」迁上来
    const lines = value
      .map((v): PriceLine | null => {
        if (typeof v === 'number') return Number.isFinite(v) ? { price: v, alert: false } : null;
        if (v && typeof v === 'object') {
          const o = v as { price?: unknown; alert?: unknown };
          return typeof o.price === 'number' && Number.isFinite(o.price)
            ? { price: o.price, alert: o.alert === true }
            : null;
        }
        return null;
      })
      .filter((l): l is PriceLine => l != null);
    if (lines.length > 0) out[key] = lines;
  }
  return out;
}

/**
 * 加一条线。**同价不重复加** —— 右键落在已有线上走菜单里的「删掉这条线」，
 * 这条规则保证了两条线不会重合成一根（重合了删一条会看着像没反应）。
 */
export function addPriceLine(lines: readonly PriceLine[], price: number, alert = false): PriceLine[] {
  if (!Number.isFinite(price)) return [...lines];
  if (lines.some((l) => l.price === price)) return [...lines];
  return [...lines, { price, alert }];
}

/** 删第 index 条。越界返回原样（调用方可能拿着上一帧的下标）。 */
export function removePriceLine(lines: readonly PriceLine[], index: number): PriceLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  return lines.filter((_, i) => i !== index);
}

/** 把第 index 条改到 price（拖拽用；下标不变，见文件头）。 */
export function movePriceLine(
  lines: readonly PriceLine[],
  index: number,
  price: number,
): PriceLine[] {
  if (index < 0 || index >= lines.length || !Number.isFinite(price)) return [...lines];
  return lines.map((l, i) => (i === index ? { ...l, price } : l));
}

/** 开关第 index 条的告警。 */
export function setLineAlert(
  lines: readonly PriceLine[],
  index: number,
  alert: boolean,
): PriceLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  return lines.map((l, i) => (i === index ? { ...l, alert } : l));
}

/**
 * 离 y 最近且在 tolerance 以内的那条线的下标，没有则 -1。
 * 入参是**已经算好的各条线的 y**（价格 → 像素的换算在画布层，那边才知道量程）。
 */
export function nearestLineWithin(
  ys: readonly number[],
  y: number,
  tolerance = LINE_HIT_PX,
): number {
  let best = -1;
  let bestDistance = tolerance;
  for (let i = 0; i < ys.length; i++) {
    const distance = Math.abs(ys[i] - y);
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = i;
    }
  }
  return best;
}
