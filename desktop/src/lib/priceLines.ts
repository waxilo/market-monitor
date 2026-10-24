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
 * 唯一的例外是 `lineNote.ts` —— 它本身也是零依赖、只做文本归一化。
 */

import { normalizeLineNote } from './lineNote';

/** 判定「鼠标落在某条线上」的纵向容差（像素）。太小则难点中，太大则两条近线分不开。 */
export const LINE_HIT_PX = 4;

/**
 * 系统划线的**身份**：这条线由哪个分析面板生成、在面板里绑的是哪一行。
 *
 * 只有「生成它的那个面板」认这个字段：画布渲染、命中、拖拽、告警判定一概不看它 ——
 * 一条系统线在图上与用户手画的线没有任何区别。它的唯一用途是**回显**：
 * 面板再打开时据此认出「这一档我已经在图上划过线了」，而不是再叠一条。
 *
 * `kind` 做成联合而不是布尔：以后 MA / 布林带面板也可能代画线，多一个来源只加一支，
 * 不会出现「是不是系统线」与「哪个系统的线」两处判断各写一遍。
 */
export type LineOrigin = { kind: 'ema'; interval: string; period: number };

export interface PriceLine {
  price: number;
  /**
   * 告警标记：价格穿越这条线时由宿主弹系统通知（文案按穿越方向分「上破 / 下破」）。
   *
   * 告警挂在**已有的水平线**上，而不是另存一份价位表 —— 同一个价既要画线又要告警是
   * 常态，拆成两份就会在图上叠出两根重合的线（重合了删一条会看着像没反应）。
   */
  alert: boolean;
  /**
   * 备注（图上线旁那几个字，如「缺口上沿」）。
   *
   * 挂在线上而不是另开一张标注表：删线就该连备注一起消失，两份数据必然出现
   * 「线没了字还飘在图上」。**空备注不落字段**（不是空串）—— 存档里 `note: ""`
   * 与「没有备注」是同一件事，留两个表示法只会让判断多一处。
   */
  note?: string;
  /**
   * 系统划线身份（见 `LineOrigin`）。**字段缺失 = 用户划线** —— 右键菜单画出来的、
   * 老版本存档里的线全都落在这一类，所以判断一律是 `line.origin == null`，
   * 不要指望有一个 `origin: 'user'` 的显式取值（那会让每条老线在解析时被补一次字段）。
   */
  origin?: LineOrigin;
}

/** 按标的索引的价目表（键 = `市场:标的`）。 */
export type LineStore = Record<string, PriceLine[]>;

/**
 * 在一条线上落「备注 / 来源」这两个可缺省字段：**空值 = 删字段**（不存空串、不存 null）。
 * 两条写入路径（`setLineNote` / `setLineOrigin`）都走它，免得一处记得删、另一处忘了。
 */
function withLineFields(
  base: PriceLine,
  note: string | null,
  origin: LineOrigin | null,
): PriceLine {
  const next: PriceLine = { ...base };
  if (note) next.note = note;
  else delete next.note;
  if (origin) next.origin = origin;
  else delete next.origin;
  return next;
}

/**
 * 读一条线的来源字段。认不出的 `kind`（未来版本写的、或存档被手改过）**一律当用户划线**：
 * 系统划线只是回显用的附注，丢掉它不影响这条线的价位与告警，硬保留反而会让面板
 * 拿一个不认识的来源去匹配行。
 */
function parseOrigin(v: unknown): LineOrigin | null {
  if (!v || typeof v !== 'object') return null;
  const o = v as { kind?: unknown; interval?: unknown; period?: unknown };
  if (o.kind !== 'ema') return null;
  if (typeof o.interval !== 'string' || o.interval === '') return null;
  if (typeof o.period !== 'number' || !Number.isFinite(o.period) || o.period <= 0) return null;
  return { kind: 'ema', interval: o.interval, period: o.period };
}

/** 这条线是不是系统画的（缺省 `origin` = 用户画的）。 */
export function isSystemLine(line: PriceLine): boolean {
  return line.origin != null;
}

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
          const o = v as { price?: unknown; alert?: unknown; note?: unknown; origin?: unknown };
          if (typeof o.price !== 'number' || !Number.isFinite(o.price)) return null;
          const line: PriceLine = { price: o.price, alert: o.alert === true };
          const note = normalizeLineNote(o.note);
          if (note) line.note = note;
          const origin = parseOrigin(o.origin);
          if (origin) line.origin = origin;
          return line;
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
 *
 * `note` 是**出生备注**（EMA 分析里点一行画线会自动写上「1时EMA50」），
 * `origin` 是**出生身份**（同上，记下它属于哪一档 EMA；手画的不传）。
 * 两者走与 `setLineNote` / `setLineOrigin` 同一套归一化：空值不落字段。
 */
export function addPriceLine(
  lines: readonly PriceLine[],
  price: number,
  alert = false,
  note = '',
  origin?: LineOrigin | null,
): PriceLine[] {
  if (!Number.isFinite(price)) return [...lines];
  if (lines.some((l) => l.price === price)) return [...lines];
  const text = normalizeLineNote(note);
  return [...lines, withLineFields({ price, alert }, text, origin ?? null)];
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
 * 改第 index 条的备注。空（或全是空白）**等于删掉备注** —— 删字段而不是存空串，
 * 见 `PriceLine.note` 的注释；超长按 `LINE_NOTE_MAX` 截断，不报错（用户是边看边改，
 * 弹窗里已经有字数提示，图省事直接按上限收）。
 */
export function setLineNote(
  lines: readonly PriceLine[],
  index: number,
  note: string,
): PriceLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  const next = normalizeLineNote(note);
  return lines.map((l, i) => (i === index ? withLineFields(l, next, l.origin ?? null) : l));
}

/**
 * 给第 index 条线打上 / 摘掉系统划线身份（`null` = 摘掉，它变回一条普通的用户划线）。
 * 摘掉是**不删线**的：用户想摆脱某个面板的绑定，线本身仍是他画在那儿的价位。
 */
export function setLineOrigin(
  lines: readonly PriceLine[],
  index: number,
  origin: LineOrigin | null,
): PriceLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  return lines.map((l, i) => (i === index ? withLineFields(l, l.note ?? null, origin) : l));
}

/**
 * 把第 index 条线**改绑**：价位 / 告警 / 来源一次改到位，备注只在原来没有时补。
 *
 * 「改到新价 + 打开告警 + 打上系统身份」这三件事在面板里永远同时发生（那一档漂了，
 * 把线拉回当前值），拆成三个接口逐次调用就会落一份中间态存档。
 * 备注不覆盖：用户写过的「缺口上沿」比自动生成的值钱。
 */
export function bindPriceLine(
  lines: readonly PriceLine[],
  index: number,
  target: { price: number; alert: boolean; origin: LineOrigin; note?: string },
): PriceLine[] {
  if (index < 0 || index >= lines.length) return [...lines];
  if (!Number.isFinite(target.price)) return [...lines];
  return lines.map((l, i) => {
    if (i !== index) return l;
    const note = l.note ?? normalizeLineNote(target.note);
    return withLineFields({ ...l, price: target.price, alert: target.alert }, note, target.origin);
  });
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
