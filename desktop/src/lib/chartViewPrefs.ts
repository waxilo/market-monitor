/**
 * K 线视角偏好：**全局一份**（与指标、周期条同口径）—— 看多宽（可见根数）、
 * 右端停哪（右侧留白 / 回看多少根）、纵向刻度（缩放倍数 + 平移比例）。
 *
 * 存的就是「用户意图」这四个数（见 chartMath 的 Viewport / PriceView）：
 * 与序列长度无关，所以换标的、切周期、冷启动后依然成立；越界交给
 * chartMath 的 `clampViewport` / `clampPriceView` 在用时钳制，存储里保留原意图。
 *
 * 双击复位 = 回默认**并遗忘**（写回默认），否则换一处又把旧视角带回来，
 * 「复位」就只是个临时假象。
 *
 * 读的时候认不出/非法的字段一律回默认 —— 手改坏的 localStorage 不会把界面弄崩
 * （与 intervals 的落盘口径一致）。
 */
import {
  clampNum,
  initialPriceView,
  initialViewport,
  MAX_BARS,
  MAX_SPAN_RATIO,
  MIN_BARS,
  MIN_SPAN_RATIO,
  type PriceView,
  type Viewport,
} from './chartMath';

export const CHART_VIEW_KEY = 'mm.chartView';

export interface ChartViewPrefs {
  viewport: Viewport;
  price: PriceView;
}

/** 落盘节流：滚轮一停就写（连续滚动期间合并成一次）。 */
const SAVE_DEBOUNCE_MS = 250;

export function defaultChartViewPrefs(): ChartViewPrefs {
  return { viewport: initialViewport(), price: initialPriceView() };
}

function finiteNum(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** 坏值兜底都在这：每个字段独立回默认，一个字段坏不影响其他字段。 */
export function parseChartViewPrefs(raw: string | null): ChartViewPrefs {
  const fallback = defaultChartViewPrefs();
  if (raw == null) return fallback;
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return fallback;
  }
  if (typeof data !== 'object' || data === null) return fallback;
  const d = data as Record<string, unknown>;

  const bars = finiteNum(d.visibleBars);
  const offset = finiteNum(d.rightOffset);
  const zoom = finiteNum(d.priceZoom);
  const pan = finiteNum(d.pricePan);

  return {
    viewport: {
      visibleBars: bars !== null && bars > 0
        ? clampNum(bars, MIN_BARS, MAX_BARS)
        : fallback.viewport.visibleBars,
      rightOffset: offset !== null ? offset : fallback.viewport.rightOffset,
    },
    price: {
      zoom: zoom !== null && zoom > 0
        ? clampNum(zoom, MIN_SPAN_RATIO, MAX_SPAN_RATIO)
        : fallback.price.zoom,
      pan: pan !== null ? pan : fallback.price.pan,
    },
  };
}

function serialize(viewport: Viewport, price: PriceView): string {
  return JSON.stringify({
    visibleBars: viewport.visibleBars,
    rightOffset: viewport.rightOffset,
    priceZoom: price.zoom,
    pricePan: price.pan,
  });
}

/** 进程内缓存：载入只解析一次（读取发生在每轮渲染上，重建对象没有必要）。 */
let cached: ChartViewPrefs | null = null;

export function loadChartViewPrefs(): ChartViewPrefs {
  if (cached) return cached;
  cached = parseChartViewPrefs(localStorage.getItem(CHART_VIEW_KEY));
  return cached;
}

export function saveChartViewPrefs(viewport: Viewport, price: PriceView): void {
  cached = { viewport: { ...viewport }, price: { ...price } };
  localStorage.setItem(CHART_VIEW_KEY, serialize(viewport, price));
}

let saveTimer: number | undefined;
let pending: ChartViewPrefs | null = null;

/** 滚轮缩放用：连续事件合并，停手 [SAVE_DEBOUNCE_MS] 后落盘一次（取最后的意图）。 */
export function scheduleChartViewPrefsSave(viewport: Viewport, price: PriceView): void {
  pending = { viewport: { ...viewport }, price: { ...price } };
  if (saveTimer !== undefined) window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    saveTimer = undefined;
    if (pending) {
      saveChartViewPrefs(pending.viewport, pending.price);
      pending = null;
    }
  }, SAVE_DEBOUNCE_MS);
}
