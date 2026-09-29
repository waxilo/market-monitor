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

/** 按标的索引的价目表（键 = `市场:标的`）。 */
export type LineStore = Record<string, number[]>;

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
    const prices = value.filter(
      (v): v is number => typeof v === 'number' && Number.isFinite(v),
    );
    if (prices.length > 0) out[key] = prices;
  }
  return out;
}

/**
 * 加一条线。**同价不重复加** —— 右键落在已有线上是「删除」而不是「再加一条」，
 * 这条规则保证了两条线不会重合成一根（重合了删一条会看着像没反应）。
 */
export function addPriceLine(lines: readonly number[], price: number): number[] {
  if (!Number.isFinite(price)) return [...lines];
  if (lines.includes(price)) return [...lines];
  return [...lines, price];
}

/** 删第 index 条。越界返回原样（调用方可能拿着上一帧的下标）。 */
export function removePriceLine(lines: readonly number[], index: number): number[] {
  if (index < 0 || index >= lines.length) return [...lines];
  return lines.filter((_, i) => i !== index);
}

/** 把第 index 条改到 price（拖拽用；下标不变，见文件头）。 */
export function movePriceLine(
  lines: readonly number[],
  index: number,
  price: number,
): number[] {
  if (index < 0 || index >= lines.length || !Number.isFinite(price)) return [...lines];
  return lines.map((p, i) => (i === index ? price : p));
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
