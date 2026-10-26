/**
 * K 线视角偏好：**全局一份**（与指标、周期条同口径）—— 横向缩放（可见根数）
 * 与纵向刻度（缩放倍数 + 平移比例）。
 *
 * 口径（2026-10-26 用户拍板，与 App 一致）：
 * - 切周期、换标的、重启都记住**横纵缩放**；
 * - **横向位置（右端停哪 / 回看多少根）不落盘** —— 重新打开一个标的的详情只回到最新价；
 * - 「复位」把横纵缩放一起清回默认并回到最新价，写回存档（否则换一处又把旧视角带回来）。
 *
 * 存的是与序列长度无关的「用户意图」，越界交给 chartMath 的 `clampViewport` /
 * `clampPriceView` 在用时钳制。读的时候认不出/非法的字段一律回默认 ——
 * 手改坏的 localStorage 不会把界面弄崩（与 intervals 的落盘口径一致）。
 */
import {
  clampNum,
  DEFAULT_VISIBLE,
  initialPriceView,
  MAX_BARS,
  MAX_SPAN_RATIO,
  MIN_BARS,
  MIN_SPAN_RATIO,
  type PriceView,
  type Viewport,
} from './chartMath';

export const CHART_VIEW_KEY = 'mm.chartView';

export interface ChartViewPrefs {
  /** 横向缩放：可见根数。 */
  visibleBars: number;
  /** 纵向刻度。 */
  price: PriceView;
}

/** 落盘节流：滚轮一停就写（连续滚动期间合并成一次）。 */
const SAVE_DEBOUNCE_MS = 250;

export function defaultChartViewPrefs(): ChartViewPrefs {
  return { visibleBars: DEFAULT_VISIBLE, price: initialPriceView() };
}

function finiteNum(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * 坏值兜底都在这：每个字段独立回默认，一个字段坏不影响其他字段。
 * 老版本存过的 `rightOffset` 直接忽略（横向位置不再跨会话）。
 */
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
  const zoom = finiteNum(d.priceZoom);
  const pan = finiteNum(d.pricePan);

  return {
    visibleBars: bars !== null && bars > 0
      ? clampNum(bars, MIN_BARS, MAX_BARS)
      : fallback.visibleBars,
    price: {
      zoom: zoom !== null && zoom > 0
        ? clampNum(zoom, MIN_SPAN_RATIO, MAX_SPAN_RATIO)
        : fallback.price.zoom,
      pan: pan !== null ? pan : fallback.price.pan,
    },
  };
}

function snapshot(viewport: Viewport, price: PriceView): ChartViewPrefs {
  return { visibleBars: viewport.visibleBars, price: { ...price } };
}

function serialize(prefs: ChartViewPrefs): string {
  return JSON.stringify({
    visibleBars: prefs.visibleBars,
    priceZoom: prefs.price.zoom,
    pricePan: prefs.price.pan,
  });
}

/** 进程内缓存：载入只解析一次（读取发生在每轮渲染上，重建对象没有必要）。 */
let cached: ChartViewPrefs | null = null;

export function loadChartViewPrefs(): ChartViewPrefs {
  if (cached) return cached;
  cached = parseChartViewPrefs(localStorage.getItem(CHART_VIEW_KEY));
  return cached;
}

/** 只落横纵缩放；`viewport.rightOffset` 不进存档。 */
export function saveChartViewPrefs(viewport: Viewport, price: PriceView): void {
  cached = snapshot(viewport, price);
  localStorage.setItem(CHART_VIEW_KEY, serialize(cached));
}

let saveTimer: number | undefined;
let pending: ChartViewPrefs | null = null;

/** 滚轮缩放用：连续事件合并，停手 [SAVE_DEBOUNCE_MS] 后落盘一次（取最后的意图）。 */
export function scheduleChartViewPrefsSave(viewport: Viewport, price: PriceView): void {
  pending = snapshot(viewport, price);
  if (saveTimer !== undefined) window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    saveTimer = undefined;
    if (pending) {
      cached = pending;
      localStorage.setItem(CHART_VIEW_KEY, serialize(pending));
      pending = null;
    }
  }, SAVE_DEBOUNCE_MS);
}
